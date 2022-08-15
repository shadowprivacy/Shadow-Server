/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AcknowledgeWebsocketMessage extends IncomingWebsocketMessage {

  @JsonProperty
  private long id;

  public AcknowledgeWebsocketMessage() {}

  public AcknowledgeWebsocketMessage(long id) {
    this.type = TYPE_ACKNOWLEDGE_MESSAGE;
    this.id   = id;
  }

  public long getId() {
    return id;
  }

}
