/**
 * Copyright (C) 2020 Anton Alipov, sole trader
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

import java.util.List;

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

    try {
      logger.info("Updating from local DB.");
      int offset = 0;

      for (;;) {
        List<Account> accounts = accountsManager.getAll(offset, CHUNK_SIZE);

        if (accounts == null || accounts.isEmpty()) break;
        else offset += accounts.size();

        for (Account account : accounts) {
          
          // TODO: do we need this check now?  
          if (account.isEnabled()) {
            
            directory.redisUpdatePlainDirectory(batchOperation, account.getUserLogin(), objectMapper.writeValueAsString(new PlainDirectoryEntryValue(account.getUuid())));
            contactsAdded++;
          } else {
            directory.redisRemoveFromPlainDirectory(batchOperation, account.getUserLogin());
            contactsRemoved++;
          }
        }

        logger.info("Processed the chunk of local accounts...");
      }
    } catch (JsonProcessingException e) {
    	logger.error("There were errors while updating the local directory from PostgreSQL!", e);    	
    } finally {
      directory.stopBatchOperation(batchOperation);
    }    

    logger.info(String.format("Local directory is updated (%d added, %d removed).", contactsAdded, contactsRemoved));
        
	int backoff = directory.calculateBackoff(accountsManager.getDirectoryVersion());	

	try (Jedis jedis = directory.accessDirectoryCache().getWriteResource()) {
		directory.flushIncrementalUpdates(backoff, jedis);
	}
	
	logger.info(String.format("All incremental updates flushed."));
    
    accountsManager.releaseDirectoryRestoreLock();
  }
}
