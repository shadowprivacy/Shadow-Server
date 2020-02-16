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
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.db.DataSourceFactory;

import io.dropwizard.setup.Environment;
import su.sres.shadowserver.WhisperServerConfiguration;
// federation excluded, reserved for future use
// import su.sres.shadowserver.federation.FederatedClientManager;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.FaultTolerantDatabase;
import su.sres.shadowserver.configuration.DatabaseConfiguration;

public class DirectoryCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(DirectoryCommand.class);

  public DirectoryCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception
      {

      }
    }, "directory", "Update directory from DB and peers.");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
                     WhisperServerConfiguration configuration)
      throws Exception
  {
    try {
      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      DatabaseConfiguration dbConfig = configuration.getAccountsDatabaseConfiguration();
//      DBI               dbi      = new DBI(dbConfig.getUrl(), dbConfig.getUser(), dbConfig.getPassword());

// insert      
      Jdbi accountJdbi = Jdbi.create(dbConfig.getUrl(), dbConfig.getUser(), dbConfig.getPassword());  
         
      FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("account_database_directory", accountJdbi, dbConfig.getCircuitBreakerConfiguration());
      
//      dbi.registerArgumentFactory(new OptionalArgumentFactory(dbConfig.getDriverClass()));
//      dbi.registerContainerFactory(new ImmutableListContainerFactory());
//      dbi.registerContainerFactory(new ImmutableSetContainerFactory());
//      dbi.registerContainerFactory(new OptionalContainerFactory());

//      Accounts            accounts        = dbi.onDemand(Accounts.class);
      
// insert      
      Accounts        accounts        = new Accounts(accountDatabase);
      
      ReplicatedJedisPool cacheClient     = new RedisClientFactory("main_cache_directory_command", configuration.getCacheConfiguration().getUrl(), configuration.getCacheConfiguration().getReplicaUrls(), configuration.getCacheConfiguration().getCircuitBreakerConfiguration()).getRedisClientPool();
      ReplicatedJedisPool redisClient     = new RedisClientFactory("directory_cache_directory_command", configuration.getDirectoryConfiguration().getUrl(), configuration.getDirectoryConfiguration().getReplicaUrls(), configuration.getDirectoryConfiguration().getCircuitBreakerConfiguration()).getRedisClientPool();
      DirectoryManager    directory       = new DirectoryManager(redisClient);
      AccountsManager     accountsManager = new AccountsManager(accounts, directory, cacheClient);

// This was already commented, left at federation removal for future use
//      FederatedClientManager federatedClientManager = new FederatedClientManager(environment,
//                                                                                 configuration.getJerseyClientConfiguration(),
//                                                                                 configuration.getFederationConfiguration());

      DirectoryUpdater update = new DirectoryUpdater(accountsManager, directory);

      update.updateFromLocalDatabase();
// This was already commented, left at federation removal for future use
//      update.updateFromPeers();
    } catch (Exception ex) {
      logger.warn("Directory Exception", ex);
      throw new RuntimeException(ex);
    } finally {
//      Thread.sleep(3000);
//      System.exit(0);
    }
  }
}
