/*
 * Copyright (C) 2014 Open WhisperSystems
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

import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.util.SystemMapper;

public class PendingDevicesManager {

    private final Logger logger = LoggerFactory.getLogger(PendingDevicesManager.class);

    private static final String CACHE_PREFIX = "pending_devices2::";

    private final PendingDevices pendingDevices;
    private final FaultTolerantRedisCluster cacheCluster;
    private final ObjectMapper mapper;

    public PendingDevicesManager(PendingDevices pendingDevices, FaultTolerantRedisCluster cacheCluster) {
	this.pendingDevices = pendingDevices;
	this.cacheCluster = cacheCluster;
	this.mapper = SystemMapper.getMapper();
    }

    public void store(String userLogin, StoredVerificationCode code) {
	memcacheSet(userLogin, code);
	pendingDevices.insert(userLogin, code.getCode(), code.getTimestamp());
    }

    public void remove(String userLogin) {
	memcacheDelete(userLogin);
	pendingDevices.remove(userLogin);
    }

    public Optional<StoredVerificationCode> getCodeForUserLogin(String userLogin) {
	Optional<StoredVerificationCode> code = memcacheGet(userLogin);

	if (!code.isPresent()) {
	    code = pendingDevices.getCodeForNumber(userLogin);
	    code.ifPresent(storedVerificationCode -> memcacheSet(userLogin, storedVerificationCode));
	}

	return code;
    }

    private void memcacheSet(String userLogin, StoredVerificationCode code) {
	try {
	    final String verificationCodeJson = mapper.writeValueAsString(code);

	    cacheCluster.useCluster(connection -> connection.sync().set(CACHE_PREFIX + userLogin, verificationCodeJson));
	} catch (JsonProcessingException e) {
	    throw new IllegalArgumentException(e);
	}
    }

    private Optional<StoredVerificationCode> memcacheGet(String userLogin) {
	try {
	    final String json = cacheCluster.withCluster(connection -> connection.sync().get(CACHE_PREFIX + userLogin));

	    if (json == null)
		return Optional.empty();
	    else
		return Optional.of(mapper.readValue(json, StoredVerificationCode.class));
	} catch (IOException e) {
	    logger.warn("Could not parse pending device stored verification json");
	    return Optional.empty();
	}
    }

    private void memcacheDelete(String userLogin) {
	cacheCluster.useCluster(connection -> connection.sync().del(CACHE_PREFIX + userLogin));
    }

}
