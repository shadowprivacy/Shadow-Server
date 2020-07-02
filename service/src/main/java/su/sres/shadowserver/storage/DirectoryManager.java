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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import su.sres.shadowserver.entities.ClientContact;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.util.IterablePair;
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.Util;

public class DirectoryManager {

	private final Logger logger = LoggerFactory.getLogger(DirectoryManager.class);

	private static final byte[] DIRECTORY_KEY = { 'd', 'i', 'r', 'e', 'c', 't', 'o', 'r', 'y' };

	        static final String DIRECTORY_PLAIN             = "DirectoryPlain";
	        static final String DIRECTORY_VERSION           = "DirectoryVersion";
	private static final String CURRENT_UPDATE              = "CurrentUpdate";
	private static final String INCREMENTAL_UPDATE          = "UpdateDiff::";

	public  static final int    INCREMENTAL_UPDATES_TO_HOLD = 100;
	private static final String DIRECTORY_ACCESS_LOCK_KEY   = "DirectoryAccessLock";

	private final ObjectMapper objectMapper;
	private final ReplicatedJedisPool redisPool;

	public DirectoryManager(ReplicatedJedisPool redisPool) {
		this.redisPool = redisPool;
		this.objectMapper = new ObjectMapper();
		this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	}

	public void remove(String number) {
		remove(Util.getContactToken(number));
	}

	public void remove(BatchOperationHandle handle, String number) {
		remove(handle, Util.getContactToken(number));
	}

	public void remove(byte[] token) {
		try (Jedis jedis = redisPool.getWriteResource()) {
			jedis.hdel(DIRECTORY_KEY, token);
		}
	}

	public void remove(BatchOperationHandle handle, byte[] token) {
		Pipeline pipeline = handle.pipeline;
		pipeline.hdel(DIRECTORY_KEY, token);
	}

	public void add(ClientContact contact) {
		TokenValue tokenValue = new TokenValue(contact.getRelay(), contact.isVoice(), contact.isVideo());

		try (Jedis jedis = redisPool.getWriteResource()) {
			jedis.hset(DIRECTORY_KEY, contact.getToken(), objectMapper.writeValueAsBytes(tokenValue));
		} catch (JsonProcessingException e) {
			logger.warn("JSON Serialization", e);
		}
	}

	public void add(BatchOperationHandle handle, ClientContact contact) {
		try {
			Pipeline pipeline = handle.pipeline;
			TokenValue tokenValue = new TokenValue(contact.getRelay(), contact.isVoice(), contact.isVideo());

			pipeline.hset(DIRECTORY_KEY, contact.getToken(), objectMapper.writeValueAsBytes(tokenValue));
		} catch (JsonProcessingException e) {
			logger.warn("JSON Serialization", e);
		}
	}

	public PendingClientContact get(BatchOperationHandle handle, byte[] token) {
		Pipeline pipeline = handle.pipeline;
		return new PendingClientContact(objectMapper, token, pipeline.hget(DIRECTORY_KEY, token));
	}

	public Optional<ClientContact> get(byte[] token) {
		try (Jedis jedis = redisPool.getWriteResource()) {
			byte[] result = jedis.hget(DIRECTORY_KEY, token);

			if (result == null) {
				return Optional.empty();
			}

			TokenValue tokenValue = objectMapper.readValue(result, TokenValue.class);
			return Optional.of(new ClientContact(token, tokenValue.relay, tokenValue.voice, tokenValue.video));
		} catch (IOException e) {
			logger.warn("JSON Error", e);
			return Optional.empty();
		}
	}

	public List<ClientContact> get(List<byte[]> tokens) {
		try (Jedis jedis = redisPool.getWriteResource()) {
			Pipeline pipeline = jedis.pipelined();
			List<Response<byte[]>> futures = new LinkedList<>();
			List<ClientContact> results = new LinkedList<>();

			try {
				for (byte[] token : tokens) {
					futures.add(pipeline.hget(DIRECTORY_KEY, token));
				}
			} finally {
				pipeline.sync();
			}

			IterablePair<byte[], Response<byte[]>> lists = new IterablePair<>(tokens, futures);

			for (Pair<byte[], Response<byte[]>> pair : lists) {
				try {
					if (pair.second().get() != null) {
						TokenValue tokenValue = objectMapper.readValue(pair.second().get(), TokenValue.class);
						ClientContact clientContact = new ClientContact(pair.first(), tokenValue.relay,
								tokenValue.voice, tokenValue.video);

						results.add(clientContact);
					}
				} catch (IOException e) {
					logger.warn("Deserialization Problem: ", e);
				}
			}

			return results;
		}
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
		try (Jedis jedis = redisPool.getWriteResource()) {

			// TODO: empty values for now, to be expanded with future versions of directory
			jedis.hset(DIRECTORY_PLAIN, account.getUserLogin(), "");
		}
	}

	// TODO: needs be made more complex if the directory stores data beyond logins
	void redisUpdatePlainDirectory(BatchOperationHandle handle, String userLogin) {

		handle.pipeline.hset(DIRECTORY_PLAIN, userLogin, "");
	}

	void redisRemoveFromPlainDirectory(HashSet<Account> accountsToRemove) {
		try (Jedis jedis = redisPool.getWriteResource()) {

			for (Account account : accountsToRemove) {

				jedis.hdel(DIRECTORY_PLAIN, account.getUserLogin());
			}
		}
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
		Jedis jedis = redisPool.getWriteResource();

		// deleting since we need to rewrite it;
		jedis.del(CURRENT_UPDATE);

		boolean alreadyInDirectory = jedis.hexists(DIRECTORY_PLAIN, account.getUserLogin());

		// simply checking if the login is already in directory
		// TODO: must be made more complicated if the directory stores anything except
		// just logins
		if (!alreadyInDirectory)
			jedis.hset(CURRENT_UPDATE, account.getUserLogin(), "");

		jedis.close();
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
		int backoff;

		if (directoryVersion < INCREMENTAL_UPDATES_TO_HOLD) {

			backoff = (int) directoryVersion;
		} else {

			backoff = INCREMENTAL_UPDATES_TO_HOLD;
		}

		// if current update is missing, we can't calculate anything; nullifying all
		// incremental updates
		if (!jedis.exists(CURRENT_UPDATE)) {

			logger.warn("The current update key is missing in Redis. Nullifying all incremental updates...");

			for (int i = 1; i <= backoff; i++) {

				jedis.del(getIncrementalUpdateKey(i));
			}

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

				logger.debug(legacyIncrementalUpdateKey
						+ " is missing in Redis. Therefore nullifying " + targetIncrementalUpdateKey
						+ " as it's impossible to calculate it");
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

	// TODO: if directory stores anything beyond usernames, this needs be more
	// complicated
	public HashMap<String, String> mergeUpdates(String olderKey, String newerKey) {

		Jedis jedis = redisPool.getWriteResource();

		Set<String> olderFields = jedis.hkeys(olderKey);
		Set<String> newerFields = jedis.hkeys(newerKey);

		HashMap<String, String> mergeFields = new HashMap<String, String>();

		for (String field : olderFields) {
			
			// If newerFields.contains(field) and the two are different we don't want to do anything, since addition and deletion negate each other
			// There won't be the case when two values are equal: if a login is to be added up to the previous step, it won't be added again on the current step; same for deletion

			if (!newerFields.contains(field)) {											

				// if the field is absent in the newer update, copy what's in the older one, neglecting the empty filler
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

	private static class TokenValue {

		@JsonProperty(value = "r")
		private String relay;

		@JsonProperty(value = "v")
		private boolean voice;

		@JsonProperty(value = "w")
		private boolean video;

		public TokenValue() {
		}

		public TokenValue(String relay, boolean voice, boolean video) {
			this.relay = relay;
			this.voice = voice;
			this.video = video;
		}
	}

	public static class PendingClientContact {
		private final ObjectMapper objectMapper;
		private final byte[] token;
		private final Response<byte[]> response;

		PendingClientContact(ObjectMapper objectMapper, byte[] token, Response<byte[]> response) {
			this.objectMapper = objectMapper;
			this.token = token;
			this.response = response;
		}

		public Optional<ClientContact> get() throws IOException {
			byte[] result = response.get();

			if (result == null) {
				return Optional.empty();
			}

			TokenValue tokenValue = objectMapper.readValue(result, TokenValue.class);
			return Optional.of(new ClientContact(token, tokenValue.relay, tokenValue.voice, tokenValue.video));
		}

	}
}
