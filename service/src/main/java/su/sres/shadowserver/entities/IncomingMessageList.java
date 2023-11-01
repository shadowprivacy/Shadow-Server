/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;

public class IncomingMessageList {

  @JsonProperty
  @NotNull
  @Valid
  private List<@NotNull IncomingMessage> messages;
 
  @JsonProperty
  private long timestamp;
  
  @JsonProperty
  private boolean online;

  public IncomingMessageList() {}

  public List<IncomingMessage> getMessages() {
    return messages;
  }
  
/*
 * excluded federation, reserved for future purposes
 
  public String getRelay() {
    return relay;
  }

  public void setRelay(String relay) {
    this.relay = relay;
  }
  
  */

  public long getTimestamp() {
    return timestamp;
  }
  
  public boolean isOnline() {
    return online;
  }
}
