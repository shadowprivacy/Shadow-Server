/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class AccountCreationResult {

  @JsonProperty
  private final UUID uuid;

  @JsonProperty
  private final String userLogin;

  @JsonProperty
  private final boolean storageCapable;

  @JsonCreator
  public AccountCreationResult(
      @JsonProperty("uuid") final UUID uuid,
      @JsonProperty("number") final String userLogin,
      @JsonProperty("storageCapable") final boolean storageCapable) {

    this.uuid = uuid;
    this.userLogin = userLogin;
    this.storageCapable = storageCapable;
  }

  public UUID getUuid() {
    return uuid;
  }
  
  public String getUserLogin() {
    return userLogin;
  }

  public boolean isStorageCapable() {
    return storageCapable;
  }
}