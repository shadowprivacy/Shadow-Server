/*
 Copyright 2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

import java.time.Duration;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

public class MinioConfiguration {
  @NotEmpty
  @JsonProperty
  private String uri;

  @NotEmpty
  @JsonProperty
  private String accessKey;

  @NotEmpty
  @JsonProperty
  private String accessSecret;

  @NotEmpty
  @JsonProperty
  protected String region;

  @NotEmpty
  @JsonProperty
  private String attachmentBucket;

  @NotEmpty
  @JsonProperty
  private String profileBucket;

  @NotEmpty
  @JsonProperty
  private String debuglogBucket;

  @NotEmpty
  @JsonProperty
  private String serviceBucket;

  @JsonProperty
  @NotBlank
  private String torExitNodeListObject;

  @JsonProperty
  private long torExitNodeListMaxSize = 16 * 1024 * 1024;

  @JsonProperty
  @NotBlank
  private String asnListObject;

  @JsonProperty
  private long asnListMaxSize = 16 * 1024 * 1024;

  @JsonProperty
  private Duration torRefreshInterval = Duration.ofMinutes(5);

  @JsonProperty
  private Duration asnRefreshInterval = Duration.ofMinutes(5);

  public String getUri() {
    return uri;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public String getAccessSecret() {
    return accessSecret;
  }

  public String getRegion() {
    return region;
  }

  @VisibleForTesting
  public void setRegion(final String region) {
    this.region = region;
  }

  @VisibleForTesting
  public void torExitNodeListMaxSize(final long maxSize) {
    this.torExitNodeListMaxSize = torExitNodeListMaxSize;
  }

  @VisibleForTesting
  public void asnListMaxSize(final long maxSize) {
    this.asnListMaxSize = asnListMaxSize;
  }

  public String getAttachmentBucket() {
    return attachmentBucket;
  }

  public String getProfileBucket() {
    return profileBucket;
  }

  public String getDebuglogBucket() {
    return debuglogBucket;
  }

  public String getServiceBucket() {
    return serviceBucket;
  }

  public String getTorExitNodeListObject() {
    return torExitNodeListObject;
  }

  public long getTorExitNodeListMaxSize() {
    return torExitNodeListMaxSize;
  }

  public String getAsnListObject() {
    return asnListObject;
  }

  public long getAsnListMaxSize() {
    return asnListMaxSize;
  }

  public Duration getTorRefreshInterval() {
    return torRefreshInterval;
  }

  public Duration getAsnRefreshInterval() {
    return asnRefreshInterval;
  }

}
