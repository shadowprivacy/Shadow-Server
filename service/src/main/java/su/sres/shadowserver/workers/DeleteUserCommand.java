/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Environment;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.resource.ClientResources;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.FaultTolerantDatabase;
import su.sres.shadowserver.storage.Keys;
import su.sres.shadowserver.storage.Messages;
import su.sres.shadowserver.storage.MessagesCache;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.Profiles;
import su.sres.shadowserver.storage.ProfilesManager;
import su.sres.shadowserver.storage.ReservedUsernames;
import su.sres.shadowserver.storage.Usernames;
import su.sres.shadowserver.storage.UsernamesManager;
import su.sres.shadowserver.util.Base64;

import static com.codahale.metrics.MetricRegistry.name;

public class DeleteUserCommand extends EnvironmentCommand<WhisperServerConfiguration> {

    private final Logger logger = LoggerFactory.getLogger(DeleteUserCommand.class);

    public DeleteUserCommand() {
	super(new Application<WhisperServerConfiguration>() {
	    @Override
	    public void run(WhisperServerConfiguration configuration, Environment environment)
		    throws Exception {

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
	    Jdbi messageJdbi = jdbiFactory.build(environment, configuration.getMessageStoreConfiguration(), "messagedb");
	    FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("account_database_delete_user", accountJdbi, configuration.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
	    FaultTolerantDatabase messageDatabase = new FaultTolerantDatabase("message_database", messageJdbi, configuration.getMessageStoreConfiguration().getCircuitBreakerConfiguration());
	    ClientResources redisClusterClientResources = ClientResources.builder().build();

	    FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster", configuration.getCacheClusterConfiguration(), redisClusterClientResources);

	    ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle().executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(4).build();

	    Accounts accounts = new Accounts(accountDatabase);
	    Usernames usernames = new Usernames(accountDatabase);
	    Profiles profiles = new Profiles(accountDatabase);
	    ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
	    Keys keys = new Keys(accountDatabase);
	    Messages messages = new Messages(messageDatabase);

	    ReplicatedJedisPool redisClient = new RedisClientFactory("directory_cache_delete_command", configuration.getDirectoryConfiguration().getUrl(), configuration.getDirectoryConfiguration().getReplicaUrls(), configuration.getDirectoryConfiguration().getCircuitBreakerConfiguration())
		    .getRedisClientPool();
	    FaultTolerantRedisCluster messagesCacheCluster = new FaultTolerantRedisCluster("messages_cluster", configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
	    FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster", configuration.getMetricsClusterConfiguration(), redisClusterClientResources);
	    MessagesCache messagesCache = new MessagesCache(messagesCacheCluster, keyspaceNotificationDispatchExecutor);
	    PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster);
	    UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
	    ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
	    MessagesManager messagesManager = new MessagesManager(messages, messagesCache, pushLatencyManager);
	    DirectoryManager directory = new DirectoryManager(redisClient);
	    AccountsManager accountsManager = new AccountsManager(accounts, directory, cacheCluster, keys, messagesManager, usernamesManager, profilesManager);

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
		accountsManager.delete(accountsToDelete, AccountsManager.DeletionReason.ADMIN_DELETED);

	} catch (Exception ex) {
	    logger.warn("Removal Exception!", ex);
	    throw new RuntimeException(ex);
	}
    }
}
