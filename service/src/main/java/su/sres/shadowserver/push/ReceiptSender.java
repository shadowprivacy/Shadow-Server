/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.controllers.NoSuchUserException;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;

import java.util.Optional;
import java.util.UUID;

public class ReceiptSender {

  private final MessageSender messageSender;
  private final AccountsManager accountManager;

  private static final Logger logger = LoggerFactory.getLogger(ReceiptSender.class);

  public ReceiptSender(final AccountsManager accountManager, final MessageSender messageSender) {
    this.accountManager = accountManager;
    this.messageSender = messageSender;
  }

  public void sendReceipt(AuthenticatedAccount source, UUID destinationUuid, long messageId) throws NoSuchUserException {
    final Account sourceAccount = source.getAccount();
    if (sourceAccount.getUuid().equals(destinationUuid)) {
      return;
    }

    final Account destinationAccount = accountManager.get(destinationUuid)
        .orElseThrow(() -> new NoSuchUserException(destinationUuid));

    final Envelope.Builder message = Envelope.newBuilder()
        .setServerTimestamp(System.currentTimeMillis())
        .setSource(sourceAccount.getUserLogin())
        .setSourceUuid(sourceAccount.getUuid().toString())
        .setSourceDevice((int) source.getAuthenticatedDevice().getId()).setTimestamp(messageId)
        .setType(Envelope.Type.SERVER_DELIVERY_RECEIPT);

    if (sourceAccount.getRelay().isPresent()) {
      message.setRelay(sourceAccount.getRelay().get());
    }

    for (final Device destinationDevice : destinationAccount.getDevices()) {
      try {
        messageSender.sendMessage(destinationAccount, destinationDevice, message.build(), false);
      } catch (final NotPushRegisteredException e) {
        logger.info("User no longer push registered for delivery receipt: " + e.getMessage());
      }
    }
  }  
}
