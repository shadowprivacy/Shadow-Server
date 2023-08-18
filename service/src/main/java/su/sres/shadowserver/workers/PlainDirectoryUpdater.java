/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.PlainDirectoryEntryValue;
import su.sres.shadowserver.storage.DirectoryManager.BatchOperationHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

// TODO: Migrate to Scylla
public class PlainDirectoryUpdater {

  private static final int CHUNK_SIZE = 1000;

  private final Logger logger = LoggerFactory.getLogger(PlainDirectoryUpdater.class);

  private final AccountsManager  accountsManager;
  private final DirectoryManager directory;
  private final ObjectMapper     objectMapper;

  public PlainDirectoryUpdater(AccountsManager accountsManager)
  {
    this.accountsManager = accountsManager;
    this.directory       = accountsManager.getDirectoryManager();
    this.objectMapper    = accountsManager.getMapper();
  }

  public void updateFromLocalDatabase() {
	
	accountsManager.setDirectoryRestoreLock();  
	  
    int                  contactsAdded   = 0;
    int                  contactsRemoved = 0;     
       
    BatchOperationHandle batchOperation  = directory.startBatchOperation();
    
    
    // removing all entries from Redis which are not found among existing accounts
    logger.info("Cleaning up inexisting accounts.");
    HashMap<String, String> accountsInDirectory = directory.retrievePlainDirectory();
    if(!accountsInDirectory.isEmpty()) {
	Set<String> usernamesInDirectory = accountsInDirectory.keySet();
	for (String entry : usernamesInDirectory) {
	    if (!accountsManager.get(entry).isPresent()) {
		directory.redisRemoveFromPlainDirectory(batchOperation, entry);
	            contactsRemoved++;
	    }
	}
    }

    try {
      logger.info("Updating from local DB.");
      int offset = 0;

      for (;;) {
        List<Account> accounts = accountsManager.getAll(offset, CHUNK_SIZE);

        if (accounts == null || accounts.isEmpty()) break;
        else offset += accounts.size();

        for (Account account : accounts) {     
                                
            directory.redisUpdatePlainDirectory(batchOperation, account.getUserLogin(), objectMapper.writeValueAsString(new PlainDirectoryEntryValue(account.getUuid())));
            contactsAdded++;           
        }

        logger.info("Processed the chunk of local accounts...");
      }
    } catch (JsonProcessingException e) {
    	logger.error("There were errors while updating the local directory from PostgreSQL!", e);    	
    } finally {
      directory.stopBatchOperation(batchOperation);
    }    

    logger.info(String.format("Local directory is updated (%d added or confirmed, %d removed).", contactsAdded, contactsRemoved));
        
	int backoff = directory.calculateBackoff(accountsManager.getDirectoryVersion());	

	try (Jedis jedis = directory.accessDirectoryCache().getWriteResource()) {
		directory.flushIncrementalUpdates(backoff, jedis);
	}
	
	logger.info(String.format("All incremental updates flushed."));
    
    accountsManager.releaseDirectoryRestoreLock();
  }
}
