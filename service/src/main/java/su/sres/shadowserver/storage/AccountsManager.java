/*
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
package su.sres.shadowserver.storage;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.DirectoryManager.BatchOperationHandle;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.SystemMapper;

import static com.codahale.metrics.MetricRegistry.name;

import static su.sres.shadowserver.storage.DirectoryManager.DIRECTORY_VERSION;
import static su.sres.shadowserver.storage.DirectoryManager.DIRECTORY_PLAIN;

public class AccountsManager {

	private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
	private static final Timer createTimer = metricRegistry.timer(name(AccountsManager.class, "create"));
	private static final Timer updateTimer = metricRegistry.timer(name(AccountsManager.class, "update"));
	private static final Timer getByUserLoginTimer = metricRegistry.timer(name(AccountsManager.class, "getByUserLogin"));
	private static final Timer getByUuidTimer = metricRegistry.timer(name(AccountsManager.class, "getByUuid"));

	private static final Timer redisSetTimer = metricRegistry.timer(name(AccountsManager.class, "redisSet"));
	private static final Timer redisUserLoginGetTimer = metricRegistry
			.timer(name(AccountsManager.class, "redisUserLoginGet"));
	private static final Timer redisUuidGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisUuidGet"));

	private final Logger logger = LoggerFactory.getLogger(AccountsManager.class);

	private final Accounts accounts;
	private final ReplicatedJedisPool cacheClient;
	private final DirectoryManager directory;
	private final ObjectMapper mapper;
	
	private static final String ACCOUNT_CREATION_LOCK_KEY       = "AccountCreationLock";
	private static final String ACCOUNT_REMOVAL_LOCK_KEY        = "AccountRemovalLock";
	private static final String DIRECTORY_RESTORE_LOCK_KEY      = "DirectoryRestoreLock";
	
	private static final int CHUNK_SIZE = 1000;
	
	private final AtomicInteger accountCreateLock;

	public AccountsManager(Accounts accounts, DirectoryManager directory, ReplicatedJedisPool cacheClient) {
		this.accounts = accounts;
		this.directory = directory;
		this.cacheClient = cacheClient;
		this.mapper = SystemMapper.getMapper();
		
		accountCreateLock = new AtomicInteger(0);
	}

	// this is used by directory restore and DirectoryUpdater
	public List<Account> getAll(int offset, int length) {
		return accounts.getAll(offset, length);
	}	

	public boolean create(Account account) {			
		
		accountCreateLock.getAndIncrement();
		setAccountCreationLock();
		
		long newDirectoryVersion = getDirectoryVersion() + 1L;
		
		try (Timer.Context ignored = createTimer.time()) {
			boolean freshUser = databaseCreate(account, newDirectoryVersion);
			redisSet(account);
			// updateDirectory(account);
			
			// recording the update
			directory.recordUpdateUpdate(account);
			
			// incrementing the directory version in Redis
			directory.setDirectoryVersion(newDirectoryVersion);
			
			// writing the account into the plain directory
			directory.redisUpdatePlainDirectory(account);
			
			// building incremental updates
			directory.buildIncrementalUpdates(newDirectoryVersion);			
					
			return freshUser;
		} finally {
			
			if (accountCreateLock.decrementAndGet() == 0) {
				
			    releaseAccountCreationLock();
			}
		}
	}

	// TODO: if directory stores anything except usernames in future, we'll need to make this more complicated and include a lock as well. Mind the calls in AccountController!
	public void update(Account account) {		
		
		try (Timer.Context ignored = updateTimer.time()) {
			redisSet(account);
			
			// isRemoval hardcoded to false for now, tbc in future
			databaseUpdate(account, false, -1L);
			// updateDirectory(account);			
		}
	}
	
    public void remove(HashSet<Account> accountsToRemove) {
    	
    	setAccountRemovalLock();
		
		long newDirectoryVersion = getDirectoryVersion() + 1L;
		
		try (Timer.Context ignored = updateTimer.time()) {
			
			for (Account account : accountsToRemove) {
			
			redisSet(account);
			databaseUpdate(account, true, newDirectoryVersion);
			// updateDirectory(account);												 
			}
			
			// recording the update
			directory.recordUpdateRemoval(accountsToRemove);
			
			// incrementing directory version in Redis and deleting the account from the plain directory				
			directory.setDirectoryVersion(newDirectoryVersion);
			directory.redisRemoveFromPlainDirectory(accountsToRemove);
			
			// building incremental updates
			directory.buildIncrementalUpdates(newDirectoryVersion);	
			
		} finally {
			
			releaseAccountRemovalLock();
		}
	}	

	public Optional<Account> get(AmbiguousIdentifier identifier) {
		if (identifier.hasUserLogin())
			return get(identifier.getUserLogin());
		else if (identifier.hasUuid())
			return get(identifier.getUuid());
		else
			throw new AssertionError();
	}

	public Optional<Account> get(String number) {
		try (Timer.Context ignored = getByUserLoginTimer.time()) {
			Optional<Account> account = redisGet(number);

			if (!account.isPresent()) {
				account = databaseGet(number);
				account.ifPresent(value -> redisSet(value));
			}

			return account;
		}
	}

	/*
	 * possibly related to federation, reserved for future use
	 * 
	 * 
	 * public boolean isRelayListed(String number) { byte[] token =
	 * Util.getContactToken(number); Optional<ClientContact> contact =
	 * directory.get(token);
	 * 
	 * return contact.isPresent() && !Util.isEmpty(contact.get().getRelay()); }
	 */

	public Optional<Account> get(UUID uuid) {
		try (Timer.Context ignored = getByUuidTimer.time()) {
			Optional<Account> account = redisGet(uuid);

			if (!account.isPresent()) {
				account = databaseGet(uuid);
				account.ifPresent(value -> redisSet(value));
			}

			return account;
		}
	}

	public List<Account> getAllFrom(int length) {
		return accounts.getAllFrom(length);
	}

	public List<Account> getAllFrom(UUID uuid, int length) {
		return accounts.getAllFrom(uuid, length);
	}

/*	private void updateDirectory(Account account) {
		if (account.isEnabled()) {			
			byte[] token = Util.getContactToken(account.getUserLogin());
			ClientContact clientContact = new ClientContact(token, null, true, true);
			directory.add(clientContact);
		} else {
			directory.remove(account.getUserLogin());
		}
	}  */

	private String getAccountMapKey(String userLogin) {
		return "AccountMap::" + userLogin;
	}

	private String getAccountEntityKey(UUID uuid) {
		
	    return "Account3::" + uuid.toString();		
	}   

	private void redisSet(Account account) {
		try (Jedis jedis = cacheClient.getWriteResource();
			 Timer.Context ignored = redisSetTimer.time())
		{
						
			jedis.set(getAccountMapKey(account.getUserLogin()), account.getUuid().toString());
			jedis.set(getAccountEntityKey(account.getUuid()), mapper.writeValueAsString(account));
			
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}	

	private Optional<Account> redisGet(String userLogin) {
		try (Jedis jedis = cacheClient.getReadResource();
			Timer.Context ignored = redisUserLoginGetTimer.time()) {
			
			String uuid = jedis.get(getAccountMapKey(userLogin));
						
			if (uuid != null) return redisGet(jedis, UUID.fromString(uuid));			
			else return Optional.empty();
			
		} catch (IllegalArgumentException e) {
			logger.warn("Deserialization error", e);
			return Optional.empty();
		} catch (JedisException e) {
			logger.warn("Redis failure", e);
			return Optional.empty();
		}
	}

	private Optional<Account> redisGet(UUID uuid) {
		try (Jedis jedis = cacheClient.getReadResource()) {
			return redisGet(jedis, uuid);
		}
	}

	private Optional<Account> redisGet(Jedis jedis, UUID uuid) {
		try (Timer.Context ignored = redisUuidGetTimer.time()) {
			String json = jedis.get(getAccountEntityKey(uuid));

			if (json != null) {
				Account account = mapper.readValue(json, Account.class);
				account.setUuid(uuid);

				return Optional.of(account);
			}

			return Optional.empty();
		} catch (IOException e) {
			logger.warn("Deserialization error", e);
			return Optional.empty();
		} catch (JedisException e) {
			logger.warn("Redis failure", e);
			return Optional.empty();
		}
	}

	private Optional<Account> databaseGet(String userLogin) {
		return accounts.get(userLogin);
	}

	private Optional<Account> databaseGet(UUID uuid) {
		return accounts.get(uuid);
	}

	private boolean databaseCreate(Account account, long directoryVersion) {
		return accounts.create(account, directoryVersion);
	}

	private void databaseUpdate(Account account, boolean isRemoval, long directoryVersion) {
		if (!isRemoval) {
			accounts.update(account);		
			
		} else {
			accounts.update(account, isRemoval, directoryVersion);
		}
	}
	
	public long getDirectoryVersion() {
		  Jedis jedis = directory.accessDirectoryCache().getWriteResource();
		  
		  String currentVersion = jedis.get(DIRECTORY_VERSION);
		  jedis.close();		  		  
		  
		  if (currentVersion == null || "nil".equals(currentVersion)) {			  		  
			  
			  try {
				  return accounts.restoreDirectoryVersion();
			  } catch (IllegalStateException e) {
				  logger.warn("IllegalStateException received from an SQL query for directory version, assuming 0.");
				  return 0;
			  }
			  
		  } else {
			  return Long.parseLong(currentVersion);
		  }		  
	  }
	
	public void restorePlainDirectory() {
		
		// consider for now that we shall restore the directory only if it's completely missing
		if (isPlainDirectoryExisting()) return;
		
		setDirectoryRestoreLock();
				
	    int contactsAdded   = 0;
	
	    BatchOperationHandle batchOperation  = directory.startBatchOperation();

	    try {
	      logger.info("Restoring plain directory from PostgreSQL...");
	      int offset = 0;

	      for (;;) {
	        List<Account> accounts = getAll(offset, CHUNK_SIZE);

	        if (accounts == null || accounts.isEmpty()) break;
	        else offset += accounts.size();

	        for (Account account : accounts) {
	          if (account.isEnabled()) {	            

	            directory.redisUpdatePlainDirectory(batchOperation, account.getUserLogin(), mapper.writeValueAsString(new PlainDirectoryEntryValue(account.getUuid()))); 
	            contactsAdded++;	   
	          }
	        }

	        logger.info("Processed " + CHUNK_SIZE + " local accounts...");
	      }
	    } catch (JsonProcessingException e) {
	    	logger.error("There were errors while restoring the local directory from PostgreSQL!", e);
	    } finally {
	      directory.stopBatchOperation(batchOperation);
	    }
	  
	    logger.info(String.format("Local directory restoration complete (%d contacts processed).", contactsAdded));	
	    releaseDirectoryRestoreLock();
	} 
	
	 public DirectoryManager getDirectoryManager() {		
		return directory;
	}
	 
	public ObjectMapper getMapper() {
		return mapper;
	} 
	 
	 private boolean isPlainDirectoryExisting() {
		 try (Jedis jedis = directory.accessDirectoryCache().getWriteResource()) {
			 
			 return jedis.exists(DIRECTORY_PLAIN);
		 }		 
	 }
	 
	 public void setAccountCreationLock() {
		 Jedis jedis = cacheClient.getWriteResource();
		  
		 jedis.setex(ACCOUNT_CREATION_LOCK_KEY, 60, "");
		 jedis.close();
	 }
	 
	 public void setAccountRemovalLock() {
		 Jedis jedis = cacheClient.getWriteResource();
		  
		 jedis.setex(ACCOUNT_REMOVAL_LOCK_KEY, 60, "");
		 jedis.close();
	 }
	 
	 public void setDirectoryRestoreLock() {
		 Jedis jedis = cacheClient.getWriteResource();
		  
		  jedis.setex(DIRECTORY_RESTORE_LOCK_KEY, 60, "");
		  jedis.close();
	 }
	 
	 public void releaseAccountCreationLock() {
		 Jedis jedis = cacheClient.getWriteResource();
		  
		 jedis.del(ACCOUNT_CREATION_LOCK_KEY);
		 jedis.close();
	 }
	 
	 public void releaseAccountRemovalLock() {
		 Jedis jedis = cacheClient.getWriteResource();
		  
		 jedis.del(ACCOUNT_REMOVAL_LOCK_KEY);
		 jedis.close();
	 }
	 
	 public void releaseDirectoryRestoreLock() {
		 Jedis jedis = cacheClient.getWriteResource();
		  
		  jedis.del(DIRECTORY_RESTORE_LOCK_KEY);
		  jedis.close();
	 }
	 	 
	 public boolean getAccountCreationLock() {
		 try (Jedis jedis = cacheClient.getWriteResource()) {
		  
		  return jedis.exists(ACCOUNT_CREATION_LOCK_KEY);
		 } 
	 }
	 
	 public boolean getAccountRemovalLock() {
		 try (Jedis jedis = cacheClient.getWriteResource()) {
		  
		  return jedis.exists(ACCOUNT_REMOVAL_LOCK_KEY);
		 }		  
	 }
	 
	 public boolean getDirectoryRestoreLock() {
		 try(Jedis jedis = cacheClient.getWriteResource()) {
		  
		  return jedis.exists(DIRECTORY_RESTORE_LOCK_KEY);
		 }		  
	 }
	 
	 
	
	
}
