/**
 * Copyright (C) 2013 Open WhisperSystems
 * Modifications copyright (C) 2020 Sophisticated Research
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
	private static final String CURRENT_UPDATE = "CurrentUpdate";
	private static final String INCREMENTAL_UPDATE = "UpdateDiff::";

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

	public void recordUpdateUpdate(Account account) {
		try (Jedis jedis = redisPool.getWriteResource()) {

			// deleting since we need to rewrite it;
			jedis.del(CURRENT_UPDATE);

			boolean alreadyInDirectory = jedis.hexists(DIRECTORY_PLAIN, account.getUserLogin());

			// TODO: this is simply checking if the login is already in directory, since
			// this "update" is actually limited to creation
			// so far, and UUID does not change afterwards. Needs to be made more
			// complicated if actual updates and, the more so,
			// multiple accounts at a time, are implemented
			if (!alreadyInDirectory) {
				PlainDirectoryEntryValue entryValue = new PlainDirectoryEntryValue(account.getUuid());
				jedis.hset(CURRENT_UPDATE, account.getUserLogin(), objectMapper.writeValueAsString(entryValue));
			}
		} catch (JsonProcessingException e) {
			logger.warn("JSON Error", e);
		}
	}

	public void recordUpdateRemoval(HashSet<Account> accounts) {

		Jedis jedis = redisPool.getWriteResource();

		// deleting since we need to rewrite it;
		jedis.del(CURRENT_UPDATE);

		// -1 stands for the flag of removal
		for (Account account : accounts) {
			jedis.hset(CURRENT_UPDATE, account.getUserLogin(), "-1");
		}

		jedis.close();
	}

	public void buildIncrementalUpdates(long directoryVersion) {

		Jedis jedis = redisPool.getWriteResource();

		// this is 1 or more, since this method is not invoked until the directory
		// version is incremented from 0;
		int backoff = calculateBackoff(directoryVersion);
		
		// if current update is missing, we can't calculate anything; nullifying all
		// incremental updates
		if (!jedis.exists(CURRENT_UPDATE)) {

			logger.warn("The current update key is missing in Redis. Flushing all incremental updates...");

			flushIncrementalUpdates(backoff, jedis);
			jedis.close();
			return;
		}

		for (int i = backoff; i > 0; i--) {

			// here we are making best effort to build the incremental update of depth i

			String targetIncrementalUpdateKey = getIncrementalUpdateKey(i);

			// deleting since we need to rewrite it;
			jedis.del(targetIncrementalUpdateKey);

			// for i = 1 the incremental update is just the same as the current update
			if (i == 1) {
				jedis.hmset(targetIncrementalUpdateKey, jedis.hgetAll(CURRENT_UPDATE));
				continue;
			}

			String legacyIncrementalUpdateKey = getIncrementalUpdateKey(i - 1);

			// if legacy incremental update is missing, we can't calculate the target
			// incremental update; so just nullifying it
			if (!jedis.exists(legacyIncrementalUpdateKey)) {

				logger.debug(legacyIncrementalUpdateKey + " is missing in Redis. Therefore nullifying "
						+ targetIncrementalUpdateKey + " as it's impossible to calculate it");
				continue;
			}

			HashMap<String, String> updatedIncrementalUpdate = mergeUpdates(legacyIncrementalUpdateKey, CURRENT_UPDATE);

			if (!updatedIncrementalUpdate.isEmpty()) {
				jedis.hmset(targetIncrementalUpdateKey, updatedIncrementalUpdate);
			} else {
				// just a filler for the case when the incremental update is empty
				jedis.hset(targetIncrementalUpdateKey, "", "");
			}
		}

		jedis.close();
	}

	// TODO: if directory stores anything beyond usernames and (immutable) UUIDs,
	// and there are "true" entry updates,
	// this needs be more complicated
	public HashMap<String, String> mergeUpdates(String olderKey, String newerKey) {

		Jedis jedis = redisPool.getWriteResource();

		Set<String> olderFields = jedis.hkeys(olderKey);
		Set<String> newerFields = jedis.hkeys(newerKey);

		HashMap<String, String> mergeFields = new HashMap<String, String>();

		for (String field : olderFields) {

			// If newerFields.contains(field) and the two are different we don't want to do
			// anything, since addition and deletion negate each other.
			// There won't be the case when two values are equal: if a login is to be added
			// up to the previous step, it won't be added again on the current step; same
			// for deletion

			if (!newerFields.contains(field)) {

				// if the field is absent in the newer update, copy what's in the older one,
				// neglecting the empty filler
				if (!field.equals("")) {
					mergeFields.put(field, jedis.hget(olderKey, field));
				}
			}
		}

		for (String field : newerFields) {

			// now checking fields absent in the older update but present in the newer one,
			// and copying those
			if (!olderFields.contains(field)) {

				mergeFields.put(field, jedis.hget(newerKey, field));
			}
		}

		jedis.close();

		return mergeFields;
	}

	public void flushIncrementalUpdates(int backoff, Jedis jedis) {

		for (int i = 1; i <= backoff; i++) {
			jedis.del(getIncrementalUpdateKey(i));
		}
		
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
