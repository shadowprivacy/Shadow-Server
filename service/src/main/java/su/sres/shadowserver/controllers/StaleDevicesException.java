/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import java.util.List;


public class StaleDevicesException extends Exception {
  private final List<Long> staleDevices;

  public StaleDevicesException(List<Long> staleDevices) {
    this.staleDevices = staleDevices;
  }

  public List<Long> getStaleDevices() {
    return staleDevices;
  }
}
