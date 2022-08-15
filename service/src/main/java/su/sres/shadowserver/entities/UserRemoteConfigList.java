/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class UserRemoteConfigList {

  @JsonProperty
  private List<UserRemoteConfig> config;

  public UserRemoteConfigList() {}

  public UserRemoteConfigList(List<UserRemoteConfig> config) {
    this.config = config;
  }

  public List<UserRemoteConfig> getConfig() {
    return config;
  }
}
