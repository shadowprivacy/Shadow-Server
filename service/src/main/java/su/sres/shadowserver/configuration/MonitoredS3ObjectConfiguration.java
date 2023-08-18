/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.Duration;

public class MonitoredS3ObjectConfiguration extends CdnConfiguration {

  @JsonProperty
  @NotBlank
  private String objectKey;
  
  @JsonProperty
  private long maxSize = 16 * 1024 * 1024;

  @JsonProperty
  private Duration refreshInterval = Duration.ofMinutes(5);
 
  public String getObjectKey() {
    return objectKey;
  }
  
  public long getMaxSize() {
    return maxSize;
  }

  @VisibleForTesting
  public void setMaxSize(final long maxSize) {
    this.maxSize = maxSize;
  }

  public Duration getRefreshInterval() {
    return refreshInterval;
  }
}