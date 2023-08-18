/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public class AccountStaleDevices {
  @JsonProperty
  public final UUID uuid;

  @JsonProperty
  public final StaleDevices devices;

  public AccountStaleDevices(final UUID uuid, final StaleDevices devices) {
    this.uuid = uuid;
    this.devices = devices;
  }
}
