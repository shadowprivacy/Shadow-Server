/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import su.sres.shadowserver.util.ByteArrayAdapter;

import javax.validation.constraints.NotEmpty;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

public class RelayMessage {

  @JsonProperty
  @NotEmpty
  private String destination;

  @JsonProperty
  @NotEmpty
  private long destinationDeviceId;

  @JsonProperty
  @NotNull
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
  private byte[] outgoingMessageSignal;

  public RelayMessage() {}

  public RelayMessage(String destination, long destinationDeviceId, byte[] outgoingMessageSignal) {
    this.destination           = destination;
    this.destinationDeviceId   = destinationDeviceId;
    this.outgoingMessageSignal = outgoingMessageSignal;
  }

  public String getDestination() {
    return destination;
  }

  public long getDestinationDeviceId() {
    return destinationDeviceId;
  }

  public byte[] getOutgoingMessageSignal() {
    return outgoingMessageSignal;
  }
}
