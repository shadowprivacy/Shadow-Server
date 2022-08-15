/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;
import java.util.List;

public class ClientContactTokens {

  @NotNull
  @JsonProperty
  private List<String> contacts;

  public List<String> getContacts() {
    return contacts;
  }

  public ClientContactTokens() {}

  public ClientContactTokens(List<String> contacts) {
    this.contacts = contacts;
  }

}
