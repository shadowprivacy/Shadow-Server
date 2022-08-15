/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class MessageCacheConfiguration {

    @JsonProperty
    @NotNull
    @Valid
    private RedisClusterConfiguration cluster;

    @JsonProperty
    private int persistDelayMinutes = 10;

    public RedisClusterConfiguration getRedisClusterConfiguration() {
	return cluster;
    }

    public int getPersistDelayMinutes() {
	return persistDelayMinutes;
    }
}
