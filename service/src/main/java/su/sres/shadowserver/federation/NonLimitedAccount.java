/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.federation;


import com.fasterxml.jackson.annotation.JsonIgnore;
//import com.google.common.base.Optional;
import java.util.Optional;

import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;

public class NonLimitedAccount extends Account {

  @JsonIgnore
  private final String userLogin;

  @JsonIgnore
  private final String relay;

  @JsonIgnore
  private final long deviceId;

  public NonLimitedAccount(String userLogin, long deviceId, String relay) {
    this.userLogin = userLogin;
    this.deviceId  = deviceId;
    this.relay     = relay;
  }

  @Override
  public String getUserLogin() {
    return userLogin;
  }

  @Override
  public boolean isRateLimited() {
    return false;
  }

  @Override
  public Optional<String> getRelay() {
    return Optional.of(relay);
  }
/* TODO correct this constructor and uncomment in case of future use of federation 
 * 
  @Override
  public Optional<Device> getAuthenticatedDevice() {
    return Optional.of(new Device(deviceId, null, null, null, null, null, null, null, false, 0, null, System.currentTimeMillis(), System.currentTimeMillis(), false, false, "NA"));
  }
  */
}
