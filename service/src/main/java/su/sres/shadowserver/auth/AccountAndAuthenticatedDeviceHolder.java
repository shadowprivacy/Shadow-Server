/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.auth;

import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;

public interface AccountAndAuthenticatedDeviceHolder {

  Account getAccount();

  Device getAuthenticatedDevice();
}
