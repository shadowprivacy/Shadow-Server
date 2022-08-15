/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

/**
 * A displaced presence listener is notified when a specific client's presence
 * has been displaced because the same client opened a newer connection to the
 * Signal service.
 */
@FunctionalInterface
public interface DisplacedPresenceListener {

    void handleDisplacement();
}
