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
package su.sres.shadowserver.providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import su.sres.dispatch.io.RedisPubSubConnectionFactory;
import su.sres.dispatch.redis.PubSubConnection;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.util.Util;

public class RedisClientFactory implements RedisPubSubConnectionFactory {

  private final Logger logger = LoggerFactory.getLogger(RedisClientFactory.class);

  private final String    host;
  private final int       port;
  private final ReplicatedJedisPool jedisPool;

  public RedisClientFactory(String name, String url, List<String> replicaUrls, CircuitBreakerConfiguration circuitBreakerConfiguration)
	      throws URISyntaxException
	  {
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setTestOnBorrow(true);
    poolConfig.setMaxWaitMillis(10000);

    URI redisURI = new URI(url);

    this.host      = redisURI.getHost();
    this.port      = redisURI.getPort();

    JedisPool       masterPool   = new JedisPool(poolConfig, host, port, Protocol.DEFAULT_TIMEOUT, null);
    List<JedisPool> replicaPools = new LinkedList<>();

    for (String replicaUrl : replicaUrls) {
      URI replicaURI = new URI(replicaUrl);

      replicaPools.add(new JedisPool(poolConfig, replicaURI.getHost(), replicaURI.getPort(),
                                     500, Protocol.DEFAULT_TIMEOUT, null,
                                     Protocol.DEFAULT_DATABASE, null, false, null ,
                                     null, null));
    }

    this.jedisPool = new ReplicatedJedisPool(name, masterPool, replicaPools, circuitBreakerConfiguration);
  }

  public ReplicatedJedisPool getRedisClientPool() {
    return jedisPool;
  }

  @Override
  public PubSubConnection connect() {
    while (true) {
      try {
        Socket socket = new Socket(host, port);
        return new PubSubConnection(socket);
      } catch (IOException e) {
        logger.warn("Error connecting", e);
        Util.sleep(200);
      }
    }
  }
}
