/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import javax.validation.constraints.NotEmpty;

public class GcmRegistrationId {

  @JsonProperty
  @NotEmpty
  private String gcmRegistrationId;
  
  public GcmRegistrationId() {}

  @VisibleForTesting
  public GcmRegistrationId(String id) {
    this.gcmRegistrationId = id;
  }
 
  public String getGcmRegistrationId() {
    return gcmRegistrationId;
  }  
}

