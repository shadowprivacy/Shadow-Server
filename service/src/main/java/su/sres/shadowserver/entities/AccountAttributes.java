/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

import javax.validation.constraints.Size;

import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.Device.DeviceCapabilities;

public class AccountAttributes {

  @JsonProperty
  private boolean fetchesMessages;

  @JsonProperty
  private int registrationId;

  @JsonProperty
  @Size(max = 204, message = "This field must be less than 50 characters")
  private String name;

  @JsonProperty
  private String pin;

  @JsonProperty
  private String registrationLock;

  @JsonProperty
  private byte[] unidentifiedAccessKey;

  @JsonProperty
  private boolean unrestrictedUnidentifiedAccess;

  @JsonProperty
  private DeviceCapabilities capabilities;

  @JsonProperty
  private boolean discoverableByUserLogin = true;

  public AccountAttributes() {
  }

  @VisibleForTesting
  public AccountAttributes(boolean fetchesMessages, int registrationId, String name, String pin, String registrationLock, boolean discoverableByPhoneNumber, final DeviceCapabilities capabilities) {
    this.fetchesMessages = fetchesMessages;
    this.registrationId = registrationId;
    this.name = name;
    this.pin = pin;
    this.registrationLock = registrationLock;
    this.discoverableByUserLogin = discoverableByUserLogin;
    this.capabilities = capabilities;
  }

  public boolean getFetchesMessages() {
    return fetchesMessages;
  }

  public int getRegistrationId() {
    return registrationId;
  }

  public String getName() {
    return name;
  }

  public String getPin() {
    return pin;
  }

  public String getRegistrationLock() {
    return registrationLock;
  }

  public byte[] getUnidentifiedAccessKey() {
    return unidentifiedAccessKey;
  }

  public boolean isUnrestrictedUnidentifiedAccess() {
    return unrestrictedUnidentifiedAccess;
  }

  public DeviceCapabilities getCapabilities() {
    return capabilities;
  }

  public boolean isDiscoverableByUserLogin() {
    return discoverableByUserLogin;
  }
}
