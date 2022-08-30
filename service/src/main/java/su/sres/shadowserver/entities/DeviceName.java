/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotEmpty;

public class DeviceName {

  @JsonProperty
  @NotEmpty
  @Size(max = 300, message = "This field must be less than 300 characters")
  private String deviceName;

  public DeviceName() {
  }

  public String getDeviceName() {
    return deviceName;
  }
}