/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;


public class DeviceLimitExceededException extends Exception {

  private final int currentDevices;
  private final int maxDevices;

  public DeviceLimitExceededException(int currentDevices, int maxDevices) {
    this.currentDevices = currentDevices;
    this.maxDevices     = maxDevices;
  }

  public int getCurrentDevices() {
    return currentDevices;
  }

  public int getMaxDevices() {
    return maxDevices;
  }
}
