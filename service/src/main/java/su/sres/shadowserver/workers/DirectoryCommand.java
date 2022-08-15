/**
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.shadowserver.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import net.sourceforge.argparse4j.inf.Namespace;
import redis.clients.jedis.Jedis;

import static com.codahale.metrics.MetricRegistry.name;

import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Environment;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.resource.ClientResources;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.storage.AccountsManager;
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

public class DirectoryCommand extends EnvironmentCommand<WhisperServerConfiguration> {

    private final Logger logger = LoggerFactory.getLogger(DirectoryCommand.class);

    public DirectoryCommand() {
	super(new Application<WhisperServerConfiguration>() {
	    @Override
	    public void run(WhisperServerConfiguration configuration, Environment environment) throws Exception {

	    }
	}, "directory", "Update directory from PostgreSQL. WARNING: This will flush all your incremental updates!");
    }

    @Override
    protected void run(Environment environment, Namespace namespace, WhisperServerConfiguration configuration)
	    throws Exception {
	try {
	    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	    JdbiFactory jdbiFactory = new JdbiFactory();
	    Jdbi accountJdbi = jdbiFactory.build(environment, configuration.getAccountsDatabaseConfiguration(), "accountdb");
	    Jdbi messageJdbi = jdbiFactory.build(environment, configuration.getMessageStoreConfiguration(), "messagedb");
	    FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("account_database_directory", accountJdbi, configuration.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
	    FaultTolerantDatabase messageDatabase = new FaultTolerantDatabase("message_database", messageJdbi, configuration.getMessageStoreConfiguration().getCircuitBreakerConfiguration());

	    ClientResources redisClusterClientResources = ClientResources.builder().build();
	    
	    FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster", configuration.getCacheClusterConfiguration(), redisClusterClientResources);
	    FaultTolerantRedisCluster messagesCacheCluster = new FaultTolerantRedisCluster("messages_cluster", configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
	    FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster", configuration.getMetricsClusterConfiguration(), redisClusterClientResources);

	    ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle().executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(4).build();
	    
	    Accounts accounts = new Accounts(accountDatabase);
	    Usernames usernames = new Usernames(accountDatabase);
	    Profiles profiles = new Profiles(accountDatabase);
	    ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
	    Keys keys = new Keys(accountDatabase);
	    Messages messages = new Messages(messageDatabase);
	    
	    ReplicatedJedisPool redisClient = new RedisClientFactory("directory_cache_directory_command",
		    configuration.getDirectoryConfiguration().getUrl(),
		    configuration.getDirectoryConfiguration().getReplicaUrls(),
		    configuration.getDirectoryConfiguration().getCircuitBreakerConfiguration()).getRedisClientPool();

	    DirectoryManager directory = new DirectoryManager(redisClient);
	    
	    MessagesCache messagesCache = new MessagesCache(messagesCacheCluster, keyspaceNotificationDispatchExecutor);
	    PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster);
	    
	    UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
	    ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
	    MessagesManager messagesManager = new MessagesManager(messages, messagesCache, pushLatencyManager);
	    
	    AccountsManager accountsManager = new AccountsManager(accounts, directory, cacheCluster, keys, messagesManager, usernamesManager, profilesManager);

	    PlainDirectoryUpdater updater = new PlainDirectoryUpdater(accountsManager);

	    if (accountsManager.getAccountCreationLock() || directory.getDirectoryReadLock()
		    || accountsManager.getDirectoryRestoreLock()) {

		logger.warn("There's a pending operation on directory right now, please try again a bit later");
		return;
	    }

	    updater.updateFromLocalDatabase();

	} catch (Exception ex) {
	    logger.warn("Directory Exception", ex);
	    throw new RuntimeException(ex);
	}
    }
}
