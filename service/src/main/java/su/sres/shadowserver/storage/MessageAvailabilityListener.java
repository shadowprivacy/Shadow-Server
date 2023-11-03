/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

/**
 * A message availability listener is notified when new messages are available
 * for a specific device for a specific account. Availability listeners are also
 * notified when messages are moved from the message cache to long-term storage
 * as an optimization hint to implementing classes.
 */
public interface MessageAvailabilityListener {

  void handleNewMessagesAvailable();
 
  void handleMessagesPersisted();
}
