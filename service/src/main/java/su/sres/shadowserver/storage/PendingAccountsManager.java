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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

import redis.clients.jedis.Jedis;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.util.SystemMapper;

public class PendingAccountsManager {

  private final Logger logger = LoggerFactory.getLogger(PendingAccountsManager.class);

  private static final String CACHE_PREFIX = "pending_account2::";

  private final PendingAccounts              pendingAccounts;
  private final ReplicatedJedisPool          cacheClient;
  private final ObjectMapper                 mapper;
  
  public PendingAccountsManager(PendingAccounts pendingAccounts, ReplicatedJedisPool cacheClient)
  {
    this.pendingAccounts = pendingAccounts;
    this.cacheClient     = cacheClient;
    this.mapper          = SystemMapper.getMapper();
    
  }

  public void store(String userLogin, StoredVerificationCode code) {
    memcacheSet(userLogin, code);
    pendingAccounts.insert(userLogin, code.getCode(), code.getTimestamp(), code.getPushCode());
  }

  public void remove(String userLogin) {
    memcacheDelete(userLogin);
    pendingAccounts.remove(userLogin);
  }

  public Optional<StoredVerificationCode> getCodeForUserLogin(String userLogin) {
    Optional<StoredVerificationCode> code = memcacheGet(userLogin);

    if (!code.isPresent()) {
    	 code = pendingAccounts.getCodeForUserLogin(userLogin);
         code.ifPresent(storedVerificationCode -> memcacheSet(userLogin, storedVerificationCode));
    }

    return code;
  }

  private void memcacheSet(String userLogin, StoredVerificationCode code) {
    try (Jedis jedis = cacheClient.getWriteResource()) {
      jedis.set(CACHE_PREFIX + userLogin, mapper.writeValueAsString(code));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private Optional<StoredVerificationCode> memcacheGet(String userLogin) {
    try (Jedis jedis = cacheClient.getReadResource()) {
      String json = jedis.get(CACHE_PREFIX + userLogin);

      if (json == null) return Optional.empty();
      else              return Optional.of(mapper.readValue(json, StoredVerificationCode.class));
    } catch (IOException e) {
      logger.warn("Error deserializing value...", e);
      return Optional.empty();
    }
  }

  private void memcacheDelete(String userLogin) {
    try (Jedis jedis = cacheClient.getWriteResource()) {
      jedis.del(CACHE_PREFIX + userLogin);
    }
  }
}
