/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.AccountsScyllaDbConfiguration;
import su.sres.shadowserver.configuration.MessageScyllaDbConfiguration;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.AccountsManager.DeletionReason;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.storage.DeletedAccounts;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.FaultTolerantDatabase;
import su.sres.shadowserver.storage.KeysScyllaDb;
import su.sres.shadowserver.storage.MessagesCache;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.MessagesScyllaDb;
import su.sres.shadowserver.storage.Profiles;
import su.sres.shadowserver.storage.ProfilesManager;
import su.sres.shadowserver.storage.ReportMessageManager;
import su.sres.shadowserver.storage.ReportMessageScyllaDb;
import su.sres.shadowserver.storage.ReservedUsernames;
import su.sres.shadowserver.storage.StoredVerificationCodeManager;
import su.sres.shadowserver.storage.Usernames;
import su.sres.shadowserver.storage.UsernamesManager;
import su.sres.shadowserver.storage.VerificationCodeStore;
import su.sres.shadowserver.util.ScyllaDbFromConfig;

import static com.codahale.metrics.MetricRegistry.name;

public class DeleteUserCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(DeleteUserCommand.class);

  public DeleteUserCommand() {
    super(new Application<>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment) {
      }
    }, "rmuser", "remove user");
  }

  @Override
  public void configure(Subparser subparser) {
    super.configure(subparser);
    subparser.addArgument("-u", "--user")
        .dest("user")
        .type(String.class)
        .required(true)
        .help("The user to remove");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration configuration)
      throws Exception {
    try {
      String[] users = namespace.getString("user").split(",");

      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      JdbiFactory jdbiFactory = new JdbiFactory();
      Jdbi accountJdbi = jdbiFactory.build(environment, configuration.getAccountsDatabaseConfiguration(), "accountdb");
      FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("account_database_delete_user", accountJdbi, configuration.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
      ClientResources redisClusterClientResources = ClientResources.builder().build();

      MessageScyllaDbConfiguration scyllaMessageConfig = configuration.getMessageScyllaDbConfiguration();
      ScyllaDbConfiguration scyllaKeysConfig = configuration.getKeysScyllaDbConfiguration();
      AccountsScyllaDbConfiguration scyllaAccountsConfig = configuration.getAccountsScyllaDbConfiguration();
                
      ScyllaDbConfiguration scyllaDeletedAccountsConfig = configuration.getDeletedAccountsScyllaDbConfiguration();
      ScyllaDbConfiguration scyllaPendingAccountsConfig = configuration.getPendingAccountsScyllaDbConfiguration();
      
      ScyllaDbConfiguration scyllaReportMessageConfig = configuration.getReportMessageScyllaDbConfiguration();          
                      
      DynamoDbClient reportMessagesScyllaDb = ScyllaDbFromConfig.client(scyllaReportMessageConfig);
      DynamoDbClient messageScyllaDb = ScyllaDbFromConfig.client(scyllaMessageConfig);
      DynamoDbClient preKeysScyllaDb = ScyllaDbFromConfig.client(scyllaKeysConfig);
      DynamoDbClient accountsClient = ScyllaDbFromConfig.client(scyllaAccountsConfig);
      DynamoDbClient deletedAccountsScyllaDbClient = ScyllaDbFromConfig.client(scyllaDeletedAccountsConfig);               

      FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster", configuration.getCacheClusterConfiguration(), redisClusterClientResources);

      ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle().executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(4).build();
          
      DynamoDbClient pendingAccountsScyllaDbClient = ScyllaDbFromConfig.client(scyllaPendingAccountsConfig);
      
      DeletedAccounts deletedAccounts = new DeletedAccounts(deletedAccountsScyllaDbClient, scyllaDeletedAccountsConfig.getTableName());      
      VerificationCodeStore pendingAccounts = new VerificationCodeStore(pendingAccountsScyllaDbClient, scyllaPendingAccountsConfig.getTableName());
           
      Accounts accounts = new Accounts(accountsClient, scyllaAccountsConfig.getTableName(), scyllaAccountsConfig.getUserLoginTableName(),scyllaAccountsConfig.getMiscTableName(), scyllaAccountsConfig.getScanPageSize());
      Usernames usernames = new Usernames(accountDatabase);
      Profiles profiles = new Profiles(accountDatabase);
      ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
      KeysScyllaDb keysScyllaDb = new KeysScyllaDb(preKeysScyllaDb, scyllaKeysConfig.getTableName());
      MessagesScyllaDb messagesScyllaDb = new MessagesScyllaDb(messageScyllaDb, scyllaMessageConfig.getTableName(), scyllaMessageConfig.getTimeToLive());

      ReplicatedJedisPool redisClient = new RedisClientFactory("directory_cache_delete_command", configuration.getDirectoryConfiguration().getUrl(), configuration.getDirectoryConfiguration().getReplicaUrls(), configuration.getDirectoryConfiguration().getCircuitBreakerConfiguration())
          .getRedisClientPool();
      FaultTolerantRedisCluster messageInsertCacheCluster = new FaultTolerantRedisCluster("message_insert_cluster", configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
      FaultTolerantRedisCluster messageReadDeleteCluster = new FaultTolerantRedisCluster("message_read_delete_cluster", configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
      FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster", configuration.getMetricsClusterConfiguration(), redisClusterClientResources);
      MessagesCache messagesCache = new MessagesCache(messageInsertCacheCluster, messageReadDeleteCluster, keyspaceNotificationDispatchExecutor);
      PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster);
      UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
      ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
      
      ReportMessageScyllaDb reportMessageScyllaDb = new ReportMessageScyllaDb(reportMessagesScyllaDb, scyllaReportMessageConfig.getTableName());
      
      ReportMessageManager reportMessageManager = new ReportMessageManager(reportMessageScyllaDb, Metrics.globalRegistry);
      MessagesManager messagesManager = new MessagesManager(messagesScyllaDb, messagesCache, pushLatencyManager, reportMessageManager);
      DirectoryManager directory = new DirectoryManager(redisClient);
      final int lifetime = configuration.getLocalParametersConfiguration().getAccountLifetime();
      StoredVerificationCodeManager pendingAccountsManager = new StoredVerificationCodeManager(pendingAccounts, lifetime);
      AccountsManager accountsManager = new AccountsManager(accounts, directory, cacheCluster, deletedAccounts, keysScyllaDb, messagesManager, usernamesManager, profilesManager, pendingAccountsManager);

      if (accountsManager.getAccountCreationLock() ||
          directory.getDirectoryReadLock() ||
          accountsManager.getDirectoryRestoreLock()) {

        logger.warn("There's a pending operation on directory right now, please try again a bit later");
        return;
      }

      HashSet<Account> accountsToDelete = new HashSet<Account>();

      for (String user : users) {
        Optional<Account> account = accountsManager.get(user);

        if (account.isPresent()) {

          accountsToDelete.add(account.get());
          logger.warn("Removing account " + user);

        } else {
          logger.warn("Account " + user + " not found");
        }
      }

      if (!accountsToDelete.isEmpty())
        accountsManager.delete(accountsToDelete, DeletionReason.ADMIN_DELETED);

    } catch (Exception ex) {
      logger.warn("Removal Exception!", ex);
      throw new RuntimeException(ex);
    }
  }
}
