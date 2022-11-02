/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import su.sres.shadowserver.redis.ReplicatedJedisPool;

public class DirectoryManager {

  private final Logger logger = LoggerFactory.getLogger(DirectoryManager.class);

  static final String DIRECTORY_PLAIN = "DirectoryPlain";
  static final String DIRECTORY_VERSION = "DirectoryVersion";

  // TODO: to be deprecated
  private static final String CURRENT_UPDATE = "CurrentUpdate";

  private static final String INCREMENTAL_UPDATE = "UpdateDiff::";

  private static final String DIRECTORY_HISTORIC = "DirectoryHistoric::";

  public static final int INCREMENTAL_UPDATES_TO_HOLD = 100;
  private static final String DIRECTORY_ACCESS_LOCK_KEY = "DirectoryAccessLock";

  private final ObjectMapper objectMapper;
  private final ReplicatedJedisPool redisPool;

  public DirectoryManager(ReplicatedJedisPool redisPool) {
    this.redisPool = redisPool;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  public BatchOperationHandle startBatchOperation() {
    Jedis jedis = redisPool.getWriteResource();
    return new BatchOperationHandle(jedis, jedis.pipelined());
  }

  public void stopBatchOperation(BatchOperationHandle handle) {
    Pipeline pipeline = handle.pipeline;
    Jedis jedis = handle.jedis;

    pipeline.sync();
    jedis.close();
  }

  void redisUpdatePlainDirectory(Account account) {

    PlainDirectoryEntryValue entryValue = new PlainDirectoryEntryValue(account.getUuid());

    try (Jedis jedis = redisPool.getWriteResource()) {

      jedis.hset(DIRECTORY_PLAIN, account.getUserLogin(), objectMapper.writeValueAsString(entryValue));
    } catch (JsonProcessingException e) {
      logger.warn("JSON Error", e);
    }
  }

  public void redisUpdatePlainDirectory(BatchOperationHandle handle, String userLogin, String entryValueString) {

    handle.pipeline.hset(DIRECTORY_PLAIN, userLogin, entryValueString);
  }

  void redisRemoveFromPlainDirectory(HashSet<Account> accountsToRemove) {
    try (Jedis jedis = redisPool.getWriteResource()) {

      for (Account account : accountsToRemove) {

        jedis.hdel(DIRECTORY_PLAIN, account.getUserLogin());
      }
    }
  }

  public void redisRemoveFromPlainDirectory(BatchOperationHandle handle, String userLogin) {
    handle.pipeline.hdel(DIRECTORY_PLAIN, userLogin);
  }

  public HashMap<String, String> retrievePlainDirectory() {

    try (Jedis jedis = redisPool.getWriteResource()) {

      return (HashMap<String, String>) jedis.hgetAll(DIRECTORY_PLAIN);
    }
  }

  public HashMap<String, String> retrieveIncrementalUpdate(int backoff) {

    try (Jedis jedis = redisPool.getWriteResource()) {

      return (HashMap<String, String>) jedis.hgetAll(getIncrementalUpdateKey(backoff));
    }
  }

  public void setDirectoryVersion(long version) {
    Jedis jedis = redisPool.getWriteResource();
    jedis.set(DIRECTORY_VERSION, String.valueOf(version));
    jedis.close();
  }

  public String getIncrementalUpdateKey(int backoff) {

    return INCREMENTAL_UPDATE + String.valueOf(backoff);
  }

  public String getDirectoryHistoricKey(int backoff) {

    if (backoff == 0) {
      return DIRECTORY_PLAIN;
    } else {
      return DIRECTORY_HISTORIC + String.valueOf(backoff);
    }
  }

  public void buildIncrementalUpdates(long directoryVersion) {

    Jedis jedis = redisPool.getWriteResource();

    HashMap<String, String> freshDirectory = retrievePlainDirectory();

    // this is 1 or more, since this method is not invoked until the directory
    // version is incremented from 0;
    int backoff = calculateBackoff(directoryVersion);

    for (int i = backoff; i > 0; i--) {

      // here we are making best effort to build the incremental update of depth i

      String targetIncrementalUpdateKey = getIncrementalUpdateKey(i);

      // deleting since we need to rewrite it;
      jedis.del(targetIncrementalUpdateKey);

      String directoryHistoricKey = getDirectoryHistoricKey(i);

      // if a historic directory is missing, we can't calculate the target incremental
      // update; so just nullifying it
      if (!jedis.exists(directoryHistoricKey)) {
        logger.debug(directoryHistoricKey + " is missing in Redis. Therefore nullifying " + targetIncrementalUpdateKey + " as it's impossible to calculate it");
        continue;
      }

      HashMap<String, String> directoryHistoric = (HashMap<String, String>) jedis.hgetAll(directoryHistoricKey);

      HashMap<String, String> updatedIncrementalUpdate = calculateIncrementalUpdate(directoryHistoric, freshDirectory);

      if (!updatedIncrementalUpdate.isEmpty()) {
        jedis.hmset(targetIncrementalUpdateKey, updatedIncrementalUpdate);
      } else {
        // just a filler for the case when the incremental update is empty
        jedis.hset(targetIncrementalUpdateKey, "", "");
      }
    }

    jedis.close();
  }

  public HashMap<String, String> calculateIncrementalUpdate(HashMap<String, String> directoryHistoric, HashMap<String, String> directoryCurrent) {

    Jedis jedis = redisPool.getWriteResource();

    Set<String> olderFields = directoryHistoric.keySet();
    Set<String> newerFields = directoryCurrent.keySet();

    HashMap<String, String> merge = new HashMap<String, String>();

    for (String field : olderFields) {

      if (!newerFields.contains(field)) {
        // if current directory does not contain the login, means remove it
        merge.put(field, "-1");
      } else {
        // if current directory does contain the login, we need to compare the contents;
        // the difference means there's been a re-insertion of an account with a
        // previously deleted login

        String currentValue = directoryCurrent.get(field);

        if (!directoryHistoric.get(field).equals(currentValue)) {
          merge.put(field, currentValue);
        }
      }
    }

    for (String field : newerFields) {

      // now checking fields absent in the historic directory but present in the
      // current one, and copying those
      if (!olderFields.contains(field)) {
        merge.put(field, directoryCurrent.get(field));
      }
    }

    jedis.close();

    return merge;
  }

  public void buildHistoricDirectories(long directoryVersion) {

    Jedis jedis = redisPool.getWriteResource();
    int backoff = calculateBackoff(directoryVersion);

    for (int i = backoff; i > 0; i--) {

      String runningKey = getDirectoryHistoricKey(i);
      String upstreamKey = getDirectoryHistoricKey(i - 1);

      // delete the running historic directory since we need to recreate it
      jedis.del(runningKey);

      HashMap<String, String> upstream = (HashMap<String, String>) jedis.hgetAll(upstreamKey);

      // if the next in line historic directory exists, then the running directory is
      // rewritten with the next in line, otherwise the running one would now be
      // missing as well
      if (!upstream.isEmpty()) {
        jedis.hmset(runningKey, upstream);
      }
    }

    jedis.close();
  }

  public void flushIncrementalUpdates(int backoff, Jedis jedis) {

    for (int i = 1; i <= backoff; i++) {
      jedis.del(getIncrementalUpdateKey(i));
    }
    
    for (int i = 1; i <= backoff; i++) {
      jedis.del(getDirectoryHistoricKey(i));
    }

    // TODO: to be deprecated
    jedis.del(CURRENT_UPDATE);
  }

  public int calculateBackoff(long directoryVersion) {

    if (directoryVersion < INCREMENTAL_UPDATES_TO_HOLD) {
      return (int) directoryVersion;
    } else {
      return INCREMENTAL_UPDATES_TO_HOLD;
    }
  }

  public ReplicatedJedisPool accessDirectoryCache() {
    return redisPool;
  }

  public void setDirectoryReadLock() {
    Jedis jedis = redisPool.getWriteResource();

    jedis.setex(DIRECTORY_ACCESS_LOCK_KEY, 60, "");
    jedis.close();
  }

  public void releaseDirectoryReadLock() {
    Jedis jedis = redisPool.getWriteResource();

    jedis.del(DIRECTORY_ACCESS_LOCK_KEY);
    jedis.close();
  }

  public boolean getDirectoryReadLock() {
    try (Jedis jedis = redisPool.getWriteResource()) {

      return jedis.exists(DIRECTORY_ACCESS_LOCK_KEY);
    }
  }

  public static class BatchOperationHandle {

    public final Pipeline pipeline;
    public final Jedis jedis;

    public BatchOperationHandle(Jedis jedis, Pipeline pipeline) {
      this.pipeline = pipeline;
      this.jedis = jedis;
    }
  }
}
