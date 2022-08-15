/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import org.hibernate.validator.constraints.NotEmpty;

public class ApnRegistrationId {

  @JsonProperty
  @NotEmpty
  private String apnRegistrationId;

  @JsonProperty
  private String voipRegistrationId;
  
  public ApnRegistrationId() {}

  @VisibleForTesting
  public ApnRegistrationId(String apnRegistrationId, String voipRegistrationId) {
    this.apnRegistrationId  = apnRegistrationId;
    this.voipRegistrationId = voipRegistrationId;
  }


  public String getApnRegistrationId() {
    return apnRegistrationId;
  }

  public String getVoipRegistrationId() {
    return voipRegistrationId;
  }
}
