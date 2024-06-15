/*
 * Original software copyright 2013-2022 Signal Messenger, LLC
 * Modified software copyright 2024 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.push;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.SettableApiFuture;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Ignore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.util.AccountsHelper;
import su.sres.shadowserver.util.SynchronousExecutorService;
import su.sres.shadowserver.util.Util;

class FcmSenderTest {

  private ExecutorService executorService;
  private AccountsManager accountsManager;
  private FirebaseMessaging firebaseMessaging;

  private FcmSender fcmSender;

  @BeforeEach
  void setUp() {
    executorService = new SynchronousExecutorService();
    accountsManager = mock(AccountsManager.class);
    firebaseMessaging = mock(FirebaseMessaging.class);

    fcmSender = new FcmSender(executorService, accountsManager, firebaseMessaging);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    executorService.shutdown();

    //noinspection ResultOfMethodCallIgnored
    executorService.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  void testSendMessage() {
    AccountsHelper.setupMockUpdate(accountsManager);

    final GcmMessage message = new GcmMessage("foo", UUID.randomUUID(), 1, GcmMessage.Type.NOTIFICATION, Optional.empty());

    final SettableApiFuture<String> sendFuture = SettableApiFuture.create();
    sendFuture.set("message-id");

    when(firebaseMessaging.sendAsync(any())).thenReturn(sendFuture);

    fcmSender.sendMessage(message);

    verify(firebaseMessaging).sendAsync(any(Message.class));
  }

  @Ignore
  @Test
  void testSendUninstalled() {
    final UUID destinationUuid = UUID.randomUUID();
    final String gcmId = "foo";

    final Account destinationAccount = mock(Account.class);
    final Device  destinationDevice  = mock(Device.class );

    AccountsHelper.setupMockUpdate(accountsManager);

    when(destinationAccount.getDevice(1)).thenReturn(Optional.of(destinationDevice));
    when(accountsManager.get(destinationUuid)).thenReturn(Optional.of(destinationAccount));
    when(destinationDevice.getGcmId()).thenReturn(gcmId);

    final GcmMessage message = new GcmMessage(gcmId, destinationUuid, 1, GcmMessage.Type.NOTIFICATION, Optional.empty());

    final FirebaseMessagingException unregisteredException = mock(FirebaseMessagingException.class);    
    when(unregisteredException.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);

    final SettableApiFuture<String> sendFuture = SettableApiFuture.create();
    sendFuture.setException(unregisteredException);

    when(firebaseMessaging.sendAsync(any())).thenReturn(sendFuture);

    fcmSender.sendMessage(message);

    verify(firebaseMessaging).sendAsync(any(Message.class));
    verify(accountsManager).get(destinationUuid);
    verify(accountsManager).updateDevice(eq(destinationAccount), eq(1L), any());
    verify(destinationDevice).setUninstalledFeedbackTimestamp(Util.todayInMillis());
  }
}
