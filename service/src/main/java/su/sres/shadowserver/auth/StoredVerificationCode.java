/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import su.sres.shadowserver.util.Util;

import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

public class StoredVerificationCode {

  @JsonProperty
  private String code;

  @JsonProperty
  private long   timestamp;
  
  @JsonProperty
  private String pushCode;
  
  @JsonProperty
  private int lifetime;
    
  public StoredVerificationCode() {}

  public StoredVerificationCode(String code, long timestamp, String pushCode, int lifetime) {
    this.code      = code;
    this.timestamp = timestamp;
    this.pushCode  = pushCode;
    this.lifetime =  lifetime; 
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
  
  public int getLifetime() {
	    return lifetime;
	  }
 
  // this is for storing the push code into the already existing entry during pre-auth
  public void setPushCode(String inputPushCode) {
	  pushCode = inputPushCode;
  }

  public boolean isValid(String theirCodeString) {
	  if (timestamp + TimeUnit.HOURS.toMillis(lifetime) < System.currentTimeMillis()) {
	      return false;
	    }

	    if (Util.isEmpty(code) || Util.isEmpty(theirCodeString)) {
      return false;
    }

    byte[] ourCode   = code.getBytes();
    byte[] theirCode = theirCodeString.getBytes();

    return MessageDigest.isEqual(ourCode, theirCode);
  }
}
