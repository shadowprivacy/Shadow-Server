package su.sres.shadowserver.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.annotations.VisibleForTesting;

import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Optional;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Environment;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.FaultTolerantDatabase;
import su.sres.shadowserver.storage.PendingAccounts;
import su.sres.shadowserver.storage.PendingAccountsManager;
import su.sres.shadowserver.util.VerificationCode;

public class CreatePendingAccountCommand extends EnvironmentCommand<WhisperServerConfiguration> {

	private final Logger logger = LoggerFactory.getLogger(CreatePendingAccountCommand.class);

	public CreatePendingAccountCommand() {
		super(new Application<WhisperServerConfiguration>() {
			@Override
			public void run(WhisperServerConfiguration configuration, Environment environment) throws Exception {

			}
		}, "adduser", "add new user as pending (unverified) account");
	}

	@Override
	public void configure(Subparser subparser) {
		super.configure(subparser);
		subparser.addArgument("-u", "--user") // supplies a comma-separated list of users
				.dest("user").type(String.class).required(true).help("The phone number of the user to add");
	}

	@Override
  protected void run(Environment environment, Namespace namespace,
                     WhisperServerConfiguration configuration)
      throws Exception
  {
    try {
      String[] users = namespace.getString("user").split(",");

      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      JdbiFactory            jdbiFactory            = new JdbiFactory();
      Jdbi                   accountJdbi            = jdbiFactory.build(environment, configuration.getAccountsDatabaseConfiguration(), "accountdb");
      FaultTolerantDatabase  accountDatabase        = new FaultTolerantDatabase("accounts_database_add_pending_user", accountJdbi, configuration.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());

      ReplicatedJedisPool    cacheClient            = new RedisClientFactory("main_cache_add_command", configuration.getCacheConfiguration().getUrl(), configuration.getCacheConfiguration().getReplicaUrls(), configuration.getCacheConfiguration().getCircuitBreakerConfiguration()).getRedisClientPool();
      ReplicatedJedisPool    redisClient            = new RedisClientFactory("directory_cache_add_command", configuration.getDirectoryConfiguration().getUrl(), configuration.getDirectoryConfiguration().getReplicaUrls(), configuration.getDirectoryConfiguration().getCircuitBreakerConfiguration()).getRedisClientPool();      
      
      Accounts               accounts               = new Accounts(accountDatabase);
      PendingAccounts        pendingAccounts        = new PendingAccounts(accountDatabase);
      PendingAccountsManager pendingAccountsManager = new PendingAccountsManager(pendingAccounts, cacheClient);
   
     
      DirectoryManager       directory              = new DirectoryManager(redisClient);
      AccountsManager        accountsManager        = new AccountsManager(accounts, directory, cacheClient);

      for (String user: users) {
        Optional<Account> existingAccount = accountsManager.get(user);

        if (!existingAccount.isPresent()) {
        	        	        	
        	VerificationCode       verificationCode       = generateVerificationCode(user);
      	    StoredVerificationCode storedVerificationCode = new StoredVerificationCode(verificationCode.getVerificationCode(),
      	    		System.currentTimeMillis(),
                    null);
      	    pendingAccountsManager.store(user, storedVerificationCode);
      	    
            logger.warn("Added new user " + user + " to pending accounts with code " + storedVerificationCode.getCode());
                    	
        }
        
        else if ((existingAccount.isPresent() && !existingAccount.get().isEnabled())) {
        	        	        	
        	VerificationCode       verificationCode       = generateVerificationCode(user);
      	    StoredVerificationCode storedVerificationCode = new StoredVerificationCode(verificationCode.getVerificationCode(),
      	    		System.currentTimeMillis(),
                    null);
      	    pendingAccountsManager.store(user, storedVerificationCode);
      	    
            logger.warn("Added existing inactive user " + user + " to pending accounts with code " + storedVerificationCode.getCode());
        	
        }

         else {
            logger.warn("Operation failed: user " + user + " already exists and is active.");
         }
      }      
    } catch (Exception ex) {
      logger.warn("Adding Exception", ex);
      throw new RuntimeException(ex);
    }
  }

	@VisibleForTesting
	private VerificationCode generateVerificationCode(String number) {

// not sure if we'll still need this	  
//	    if (testDevices.containsKey(number)) {
//	      return new VerificationCode(testDevices.get(number));
//	    }

// generates a random number between 100000 and 999999
		SecureRandom random = new SecureRandom();
		int randomInt = 100000 + random.nextInt(900000);
		return new VerificationCode(randomInt);
	}
	  
}
