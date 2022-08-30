/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.Duration;

public class ScyllaDbConfiguration {

    private String endpoint;
    private String region;
    private String tableName;
    private Duration clientExecutionTimeout = Duration.ofSeconds(30);
    private Duration clientRequestTimeout = Duration.ofSeconds(10);
        
    private String accessKey;  
    private String accessSecret;
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getEndpoint() {
    return endpoint;
    }
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getRegion() {
	return region;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getTableName() {
	return tableName;
    }

    @JsonProperty
    public Duration getClientExecutionTimeout() {
	return clientExecutionTimeout;
    }

    @JsonProperty
    public Duration getClientRequestTimeout() {
	return clientRequestTimeout;
    }
    
    @NotEmpty
    @JsonProperty
    public String getAccessKey() {
      return accessKey;
    }

    @NotEmpty
    @JsonProperty
    public String getAccessSecret() {
      return accessSecret;
    }
}