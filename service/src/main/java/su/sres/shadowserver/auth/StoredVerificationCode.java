/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

import su.sres.shadowserver.util.Util;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.concurrent.TimeUnit;

public class StoredVerificationCode {

  @JsonProperty
  private final String code;

  @JsonProperty
  private final long timestamp;

  @JsonProperty
  private String pushCode;
    
  @JsonCreator
  public StoredVerificationCode(
      @JsonProperty("code") final String code,
      @JsonProperty("timestamp") final long timestamp,
      @JsonProperty("pushCode") String pushCode) {
    this.code = code;
    this.timestamp = timestamp;
    this.pushCode = pushCode;
  }

  public String getCode() {
    return code;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public String getPushCode() {
    return pushCode;
  }

  // this is for storing the push code into the already existing entry during
  // pre-auth
  public void setPushCode(String inputPushCode) {
    pushCode = inputPushCode;
  }

  public boolean isValid(String theirCodeString, int lifetime) {
    return isValid(theirCodeString, lifetime, Instant.now());
  }

  @VisibleForTesting
  boolean isValid(String theirCodeString, int lifetime, Instant currentTime) {
    if (Instant.ofEpochMilli(timestamp).plus(Duration.ofHours(lifetime)).isBefore(currentTime)) {
      return false;
    }

    if (Util.isEmpty(code) || Util.isEmpty(theirCodeString)) {
      return false;
    }

    byte[] ourCode = code.getBytes();
    byte[] theirCode = theirCodeString.getBytes();

    return MessageDigest.isEqual(ourCode, theirCode);
  }
}
