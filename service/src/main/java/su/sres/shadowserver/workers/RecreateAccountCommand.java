/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.annotations.VisibleForTesting;

import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.codahale.metrics.MetricRegistry.name;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.push.ClientPresenceManager;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
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
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.ScyllaDbFromConfig;
import su.sres.shadowserver.util.ServerLicenseUtil;
import su.sres.shadowserver.util.VerificationCode;
import su.sres.shadowserver.util.ServerLicenseUtil.LicenseStatus;

public class RecreateAccountCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(RecreateAccountCommand.class);

  public RecreateAccountCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment) throws Exception {

      }
    }, "restoreuser", "reinstall the deleted user as pending (unverified) account");
  }

  @Override
  public void configure(Subparser subparser) {
    super.configure(subparser);
    subparser.addArgument("-u", "--user") // supplies a comma-separated list of users
        .dest("user").type(String.class).required(true).help("The user login of the user to recreate.");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration configuration)
      throws Exception {
    try {
      String[] users = namespace.getString("user").split(",");
      int amount = users.length;

      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      JdbiFactory jdbiFactory = new JdbiFactory();
      Jdbi accountJdbi = jdbiFactory.build(environment, configuration.getAccountsDatabaseConfiguration(), "accountdb");

      // start validation

      Pair<LicenseStatus, Pair<Integer, Integer>> validationResult = ServerLicenseUtil.validate(configuration);

      LicenseStatus status = validationResult.first();
      Pair<Integer, Integer> quants = validationResult.second();
      int volume = quants.first();

      if (volume == -1) {

        switch (status) {

        case ABSENT:
          logger.warn("License key is absent. Exiting.");
          break;
        case CORRUPTED:
          logger.warn("License key is corrupted. Exiting.");
          break;
        case TAMPERED:
          logger.warn("License key is invalid. Exiting.");
          break;
        case EXPIRED:
          logger.warn("License key is expired. Exiting.");
          break;
        case NYV:
          logger.warn("License key is not yet valid. Exiting.");
          break;
        case IRRELEVANT:
          logger.warn("License key is irrelevant. Exiting.");
          break;
        case UNSPECIFIED:
          logger.warn("Unspecified error while checking the License key. Please contact the technical support. Exiting.");
          break;
        default:
          logger.warn("Exception while checking the License key. Please contact the technical support. Exiting.");
          break;
        }

        return;

      } else {
        if (status != LicenseStatus.OK) {
          if (status == LicenseStatus.OVERSUBSCRIBED) {
            logger.warn(String.format("Oversubscription! Contact your distributor for expansion to be able to host more than %s accounts. Exiting.", volume));
          } else {

            /// check for surplus

            logger.warn("Unknown error while checking the License key. Please contact the technical support. Exiting.");
          }

          return;
        } else {
          int current = quants.second();

          if ((current + amount) > volume) {
            logger.warn(String.format("Adding this many accounts will result in oversubscription. Contact your distributor for expansion to be able to host more than %s accounts. Exiting.", volume));
            return;
          }
        }
      }

      // end validation

      FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("accounts_database_add_pending_user", accountJdbi, configuration.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());

      ClientResources redisClusterClientResources = ClientResources.builder().build();

      ScyllaDbConfiguration scyllaConfig = configuration.getScyllaDbConfiguration();

      DynamoDbClient scyllaDbClient = ScyllaDbFromConfig.client(scyllaConfig);

      DeletedAccounts deletedAccounts = new DeletedAccounts(scyllaDbClient, scyllaConfig.getDeletedAccountsTableName());

      FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster", configuration.getCacheClusterConfiguration(), redisClusterClientResources);
      FaultTolerantRedisCluster messageInsertCacheCluster = new FaultTolerantRedisCluster("message_insert_cluster", configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
      FaultTolerantRedisCluster messageReadDeleteCluster = new FaultTolerantRedisCluster("message_read_delete_cluster", configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
      FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster", configuration.getMetricsClusterConfiguration(), redisClusterClientResources);

      ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle().executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(4).build();

      ReplicatedJedisPool redisClient = new RedisClientFactory("directory_cache_add_command", configuration.getDirectoryConfiguration().getUrl(), configuration.getDirectoryConfiguration().getReplicaUrls(), configuration.getDirectoryConfiguration().getCircuitBreakerConfiguration())
          .getRedisClientPool();

      Accounts accounts = new Accounts(scyllaDbClient, scyllaConfig.getAccountsTableName(), scyllaConfig.getUserLoginTableName(), scyllaConfig.getMiscTableName(), scyllaConfig.getScanPageSize());
      VerificationCodeStore pendingAccounts = new VerificationCodeStore(scyllaDbClient, scyllaConfig.getPendingAccountsTableName());
      Usernames usernames = new Usernames(accountDatabase);
      Profiles profiles = new Profiles(accountDatabase);
      ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
      KeysScyllaDb keysScyllaDb = new KeysScyllaDb(scyllaDbClient, scyllaConfig.getKeysTableName());
      MessagesScyllaDb messagesScyllaDb = new MessagesScyllaDb(scyllaDbClient, scyllaConfig.getMessagesTableName(), scyllaConfig.getTimeToLive());

      final int lifetime = configuration.getLocalParametersConfiguration().getAccountLifetime();
      StoredVerificationCodeManager pendingAccountsManager = new StoredVerificationCodeManager(pendingAccounts, lifetime);

      DirectoryManager directory = new DirectoryManager(redisClient);
      FaultTolerantRedisCluster clientPresenceCluster = new FaultTolerantRedisCluster("client_presence_cluster",
          configuration.getClientPresenceClusterConfiguration(), redisClusterClientResources);
      ClientPresenceManager clientPresenceManager = new ClientPresenceManager(clientPresenceCluster,
          Executors.newSingleThreadScheduledExecutor(), keyspaceNotificationDispatchExecutor);
      MessagesCache messagesCache = new MessagesCache(messageInsertCacheCluster, messageReadDeleteCluster, keyspaceNotificationDispatchExecutor);
      PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster);

      UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
      ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);

      ReportMessageScyllaDb reportMessageScyllaDb = new ReportMessageScyllaDb(scyllaDbClient, scyllaConfig.getReportMessageTableName());

      ReportMessageManager reportMessageManager = new ReportMessageManager(reportMessageScyllaDb, Metrics.globalRegistry);
      MessagesManager messagesManager = new MessagesManager(messagesScyllaDb, messagesCache, pushLatencyManager, reportMessageManager);

      AccountsManager accountsManager = new AccountsManager(accounts, directory, cacheCluster, deletedAccounts, keysScyllaDb, messagesManager, usernamesManager, profilesManager, pendingAccountsManager, clientPresenceManager);

      for (String user : users) {

        Optional<UUID> oUUID = deletedAccounts.findUuid(user);

        if (oUUID.isEmpty()) {
          logger.warn("No such user login to restore: " + user + ", skipping.");
          continue;
        }

        Optional<Account> existingAccount = accountsManager.get(user);

        if (!existingAccount.isPresent()) {

          VerificationCode verificationCode = generateVerificationCode();
          StoredVerificationCode storedVerificationCode = new StoredVerificationCode(verificationCode.getVerificationCode(),
              System.currentTimeMillis(),
              null);
          pendingAccountsManager.store(user, storedVerificationCode);

          logger.info("Restored the user " + user + " to pending accounts with code " + storedVerificationCode.getCode());

        } else {
          logger.error("Operation failed: user " + user + " already exists.");
        }
      }
    } catch (Exception ex) {
      logger.warn("Adding Exception", ex);
      throw new RuntimeException(ex);
    }
  }

  @VisibleForTesting
  private VerificationCode generateVerificationCode() {

// generates a random number between 100000 and 999999
    SecureRandom random = new SecureRandom();
    int randomInt = 100000 + random.nextInt(900000);
    return new VerificationCode(randomInt);
  }
}
