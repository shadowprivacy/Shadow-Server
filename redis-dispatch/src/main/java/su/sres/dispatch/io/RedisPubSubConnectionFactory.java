/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.dispatch.io;

import su.sres.dispatch.redis.PubSubConnection;

public interface RedisPubSubConnectionFactory {
  PubSubConnection connect();
}
