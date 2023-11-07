/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
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
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import static su.sres.shadowserver.storage.DirectoryManager.DIRECTORY_VERSION;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

public class PlainDirectoryUpdater {

  private final Logger logger = LoggerFactory.getLogger(PlainDirectoryUpdater.class);

  private final AccountsManager accountsManager;
  private final DirectoryManager directory;
  private final ObjectMapper objectMapper;

  public PlainDirectoryUpdater(AccountsManager accountsManager) {
    this.accountsManager = accountsManager;
    this.directory = accountsManager.getDirectoryManager();
    this.objectMapper = accountsManager.getMapper();
  }

  public void updateFromLocalDatabase() {

    accountsManager.setDirectoryRestoreLock();

    int contactsAdded = 0;
    int contactsRemoved = 0;

    BatchOperationHandle batchOperation = directory.startBatchOperation();

    // removing all entries from Redis which are not found among existing active accounts
    logger.info("Cleaning up inexisting accounts.");
    HashMap<String, String> accountsInDirectory = directory.retrievePlainDirectory();
    if (!accountsInDirectory.isEmpty()) {
      Set<String> usernamesInDirectory = accountsInDirectory.keySet();
      for (String entry : usernamesInDirectory) {
        if (!accountsManager.get(entry).isPresent() || !accountsManager.get(entry).get().isEnabled()) {
          directory.redisRemoveFromPlainDirectory(batchOperation, entry);
          contactsRemoved++;
        }
      }
    }

    try {
      logger.info("Updating from local DB.");
      final ScanRequest.Builder accountsScanRequestBuilder = ScanRequest.builder();

      List<Account> accounts = accountsManager.getAll(accountsScanRequestBuilder);

      for (Account account : accounts) {
        if (!account.isEnabled()) continue;

        directory.redisUpdatePlainDirectory(batchOperation, account.getUserLogin(), objectMapper.writeValueAsString(new PlainDirectoryEntryValue(account.getUuid())));
        contactsAdded++;
      }

    } catch (JsonProcessingException e) {
      logger.error("There were errors while updating the local directory from Scylla!", e);
    } finally {
      directory.stopBatchOperation(batchOperation);
    }

    logger.info(String.format("Local directory is updated (%d added or confirmed, %d removed).", contactsAdded, contactsRemoved));

    int backoff = directory.calculateBackoff(accountsManager.getDirectoryVersion());

    try (Jedis jedis = directory.accessDirectoryCache().getWriteResource()) {
      directory.flushIncrementalUpdates(backoff, jedis);
    }

    logger.info("All incremental updates flushed.");

    // syncing the directory version with scylla

    try(Jedis jedis = directory.accessDirectoryCache().getWriteResource()){

      long currentVersion;

      @Nullable String currentVersionFromRedis = jedis.get(DIRECTORY_VERSION);
      long currentVersionFromScylla = accountsManager.getDirectoryVersionFromScylla(); 

      if (currentVersionFromRedis == null || "nil".equals(currentVersionFromRedis)) {
        currentVersion = currentVersionFromScylla;
      } else {
        currentVersion = Math.max(currentVersionFromScylla, Long.parseLong(currentVersionFromRedis));
      }

      // reconcile directory versions to the current version plus one, to trigger updates on clients
      directory.setDirectoryVersion(currentVersion + 1L);
      accountsManager.setDirectoryVersionInScylla(currentVersion +1L);     

    } 

    logger.info("Directory version updated to " + accountsManager.getDirectoryVersion());

    accountsManager.releaseDirectoryRestoreLock();
  }
}
