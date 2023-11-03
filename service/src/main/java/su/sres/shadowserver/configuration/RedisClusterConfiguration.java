/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import java.time.Duration;
import java.util.List;

public class RedisClusterConfiguration {

    @JsonProperty
    @NotEmpty
    private String configurationUri;
    
    @JsonProperty
    @NotNull
    private Duration timeout = Duration.ofMillis(3_000);

    @JsonProperty
    @NotNull
    @Valid
    private CircuitBreakerConfiguration circuitBreaker = new CircuitBreakerConfiguration();

    @JsonProperty
    @NotNull
    @Valid
    private RetryConfiguration retry = new RetryConfiguration();
    
    public String getConfigurationUri() {
      return configurationUri;
    }
    
    public Duration getTimeout() {
        return timeout;
    }

    public CircuitBreakerConfiguration getCircuitBreakerConfiguration() {
        return circuitBreaker;
    }
    
    public RetryConfiguration getRetryConfiguration() {
        return retry;
    }
}