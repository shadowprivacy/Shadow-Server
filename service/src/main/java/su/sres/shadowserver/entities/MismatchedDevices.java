/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

public class MismatchedDevices {

  @JsonProperty
  public List<Long> missingDevices;

  @JsonProperty
  public List<Long> extraDevices;

  @VisibleForTesting
  public MismatchedDevices() {}

  public MismatchedDevices(List<Long> missingDevices, List<Long> extraDevices) {
    this.missingDevices = missingDevices;
    this.extraDevices   = extraDevices;
  }

}
