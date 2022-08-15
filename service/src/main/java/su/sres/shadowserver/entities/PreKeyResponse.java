/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

public class PreKeyResponse {

  @JsonProperty
  private String identityKey;

  @JsonProperty
  private List<PreKeyResponseItem> devices;

  public PreKeyResponse() {}

  public PreKeyResponse(String identityKey, List<PreKeyResponseItem> devices) {
    this.identityKey = identityKey;
    this.devices     = devices;
  }

  @VisibleForTesting
  public String getIdentityKey() {
    return identityKey;
  }

  @VisibleForTesting
  @JsonIgnore
  public PreKeyResponseItem getDevice(int deviceId) {
    for (PreKeyResponseItem device : devices) {
      if (device.getDeviceId() == deviceId) return device;
    }

    return null;
  }

  @VisibleForTesting
  @JsonIgnore
  public int getDevicesCount() {
    return devices.size();
  }

}
