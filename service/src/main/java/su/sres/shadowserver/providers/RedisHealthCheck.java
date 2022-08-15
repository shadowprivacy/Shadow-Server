/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.providers;

import com.codahale.metrics.health.HealthCheck;

import redis.clients.jedis.Jedis;
import su.sres.shadowserver.redis.ReplicatedJedisPool;

public class RedisHealthCheck extends HealthCheck {

  private final ReplicatedJedisPool clientPool;

  public RedisHealthCheck(ReplicatedJedisPool clientPool) {
    this.clientPool = clientPool;
  }

  @Override
  protected Result check() throws Exception {
    try (Jedis client = clientPool.getWriteResource()) {
      client.set("HEALTH", "test");

      if (!"test".equals(client.get("HEALTH"))) {
        return Result.unhealthy("fetch failed");
      }

      return Result.healthy();
    }
  }
}
