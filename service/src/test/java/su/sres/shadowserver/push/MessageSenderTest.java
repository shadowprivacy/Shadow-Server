/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

import com.google.protobuf.ByteString;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import su.sres.shadowserver.entities.MessageProtos;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageSenderTest {

  private Account account;
  private Device device;
  private MessageProtos.Envelope message;

  private ClientPresenceManager clientPresenceManager;
  private MessagesManager messagesManager;
  private FcmSender fcmSender;
  private APNSender apnSender;
  private MessageSender messageSender;

  private static final UUID ACCOUNT_UUID = UUID.randomUUID();
  private static final long DEVICE_ID = 1L;

  @BeforeEach
  void setUp() {

    account = mock(Account.class);
    device = mock(Device.class);
    message = generateRandomMessage();

    clientPresenceManager = mock(ClientPresenceManager.class);
    messagesManager = mock(MessagesManager.class);
    fcmSender = mock(FcmSender.class);
    apnSender = mock(APNSender.class);
    messageSender = new MessageSender(mock(ApnFallbackManager.class),
        clientPresenceManager,
        messagesManager,
        fcmSender,
        apnSender,
        mock(PushLatencyManager.class));

    when(account.getUuid()).thenReturn(ACCOUNT_UUID);
    when(device.getId()).thenReturn(DEVICE_ID);
  }

  @Test
  void testSendOnlineMessageClientPresent() throws Exception {
    when(clientPresenceManager.isPresent(ACCOUNT_UUID, DEVICE_ID)).thenReturn(true);
    when(device.getGcmId()).thenReturn("gcm-id");

    messageSender.sendMessage(account, device, message, true);

    ArgumentCaptor<MessageProtos.Envelope> envelopeArgumentCaptor = ArgumentCaptor.forClass(
        MessageProtos.Envelope.class);

    verify(messagesManager).insert(any(), anyLong(), envelopeArgumentCaptor.capture());

    assertTrue(envelopeArgumentCaptor.getValue().getEphemeral());

    verifyNoInteractions(fcmSender);
    verifyNoInteractions(apnSender);
  }

  @Test
  void testSendOnlineMessageClientNotPresent() throws Exception {
    when(clientPresenceManager.isPresent(ACCOUNT_UUID, DEVICE_ID)).thenReturn(false);
    when(device.getGcmId()).thenReturn("gcm-id");

    messageSender.sendMessage(account, device, message, true);

    verify(messagesManager, never()).insert(any(), anyLong(), any());
    verifyNoInteractions(fcmSender);
    verifyNoInteractions(apnSender);
  }

  @Test
  void testSendMessageClientPresent() throws Exception {
    when(clientPresenceManager.isPresent(ACCOUNT_UUID, DEVICE_ID)).thenReturn(true);
    when(device.getGcmId()).thenReturn("gcm-id");

    messageSender.sendMessage(account, device, message, false);

    final ArgumentCaptor<MessageProtos.Envelope> envelopeArgumentCaptor = ArgumentCaptor.forClass(
        MessageProtos.Envelope.class);

    verify(messagesManager).insert(eq(ACCOUNT_UUID), eq(DEVICE_ID), envelopeArgumentCaptor.capture());

    assertFalse(envelopeArgumentCaptor.getValue().getEphemeral());
    assertEquals(message, envelopeArgumentCaptor.getValue());
    verifyNoInteractions(fcmSender);
    verifyNoInteractions(apnSender);
  }

  @Test
  void testSendMessageGcmClientNotPresent() throws Exception {
    when(clientPresenceManager.isPresent(ACCOUNT_UUID, DEVICE_ID)).thenReturn(false);
    when(device.getGcmId()).thenReturn("gcm-id");

    messageSender.sendMessage(account, device, message, false);

    verify(messagesManager).insert(ACCOUNT_UUID, DEVICE_ID, message);
    verify(fcmSender).sendMessage(any());
    verifyNoInteractions(apnSender);
  }

  @Test
  void testSendMessageApnClientNotPresent() throws Exception {
    when(clientPresenceManager.isPresent(ACCOUNT_UUID, DEVICE_ID)).thenReturn(false);
    when(device.getApnId()).thenReturn("apn-id");

    messageSender.sendMessage(account, device, message, false);

    verify(messagesManager).insert(ACCOUNT_UUID, DEVICE_ID, message);
    verifyNoInteractions(fcmSender);
    verify(apnSender).sendMessage(any());
  }

  @Test
  void testSendMessageFetchClientNotPresent() throws Exception {
    when(clientPresenceManager.isPresent(ACCOUNT_UUID, DEVICE_ID)).thenReturn(false);
    when(device.getFetchesMessages()).thenReturn(true);

    messageSender.sendMessage(account, device, message, false);

    verify(messagesManager).insert(ACCOUNT_UUID, DEVICE_ID, message);
    verifyNoInteractions(fcmSender);
    verifyNoInteractions(apnSender);
  }

  private MessageProtos.Envelope generateRandomMessage() {
    return MessageProtos.Envelope.newBuilder()
        .setTimestamp(System.currentTimeMillis())
        .setServerTimestamp(System.currentTimeMillis())
        .setContent(ByteString.copyFromUtf8(RandomStringUtils.randomAlphanumeric(256)))
        .setType(MessageProtos.Envelope.Type.CIPHERTEXT)
        .setServerGuid(UUID.randomUUID().toString())
        .build();
  }
}
