/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.providers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public class SystemCertsVersion {

  @JsonProperty
  @JsonSerialize
  private int certsVersion;
 
  public SystemCertsVersion(int certsVersion) {
    this.certsVersion = certsVersion;    
  }
  
  public int getCertsVersion() {
		return certsVersion;
	}
  
  public void setCertsVersion(int version) {
	  certsVersion = version;
	}
}