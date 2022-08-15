/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class TurnToken {

  @JsonProperty
  private String username;

  @JsonProperty
  private String password;

  @JsonProperty
  private List<String> urls;

  public TurnToken(String username, String password, List<String> urls) {
    this.username = username;
    this.password = password;
    this.urls     = urls;
  }
}
