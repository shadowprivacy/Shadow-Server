/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AccountCount {

  @JsonProperty
  private int count;

  public AccountCount(int count) {
    this.count = count;
  }

  public AccountCount() {}

  public int getCount() {
    return count;
  }
}
