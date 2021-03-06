package su.sres.shadowserver.tests.push;

import su.sres.shadowserver.push.GCMSender;
import su.sres.shadowserver.push.GcmMessage;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.tests.util.SynchronousExecutorService;
import su.sres.shadowserver.util.Util;

import org.junit.Test;
import su.sres.gcm.server.Message;
import su.sres.gcm.server.Result;
import su.sres.gcm.server.Sender;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class GCMSenderTest {

  @Test
  public void testSendMessage() {
    AccountsManager            accountsManager = mock(AccountsManager.class);
    Sender                     sender          = mock(Sender.class         );
    Result                     successResult   = mock(Result.class         );
    SynchronousExecutorService executorService = new SynchronousExecutorService();

    when(successResult.isInvalidRegistrationId()).thenReturn(false);
    when(successResult.isUnregistered()).thenReturn(false);
    when(successResult.hasCanonicalRegistrationId()).thenReturn(false);
    when(successResult.isSuccess()).thenReturn(true);

    GcmMessage message = new GcmMessage("foo", "+12223334444", 1, GcmMessage.Type.NOTIFICATION, Optional.empty());
    GCMSender gcmSender = new GCMSender(accountsManager, sender, executorService);

    CompletableFuture<Result> successFuture = CompletableFuture.completedFuture(successResult);

    when(sender.send(any(Message.class))).thenReturn(successFuture);

    gcmSender.sendMessage(message);

    verify(sender, times(1)).send(any(Message.class));
  }
  @Test
  public void testSendUninstalled() {
    String destinationNumber = "+12223334444";
    String gcmId             = "foo";

    AccountsManager            accountsManager = mock(AccountsManager.class);
    Sender                     sender          = mock(Sender.class         );
    Result                     invalidResult   = mock(Result.class         );
    SynchronousExecutorService executorService = new SynchronousExecutorService();

    Account destinationAccount = mock(Account.class);
    Device  destinationDevice  = mock(Device.class );

    when(destinationAccount.getDevice(1)).thenReturn(Optional.of(destinationDevice));
    when(accountsManager.get(destinationNumber)).thenReturn(Optional.of(destinationAccount));
    when(destinationDevice.getGcmId()).thenReturn(gcmId);

    when(invalidResult.isInvalidRegistrationId()).thenReturn(true);
    when(invalidResult.isUnregistered()).thenReturn(false);
    when(invalidResult.hasCanonicalRegistrationId()).thenReturn(false);
    when(invalidResult.isSuccess()).thenReturn(true);

    GcmMessage message = new GcmMessage(gcmId, destinationNumber, 1, GcmMessage.Type.NOTIFICATION, Optional.empty());
    GCMSender gcmSender = new GCMSender(accountsManager, sender, executorService);

    CompletableFuture<Result> invalidFuture = CompletableFuture.completedFuture(invalidResult);

    when(sender.send(any(Message.class))).thenReturn(invalidFuture);

    gcmSender.sendMessage(message);

    verify(sender, times(1)).send(any(Message.class));
    verify(accountsManager, times(1)).get(eq(destinationNumber));
    verify(accountsManager, times(1)).update(eq(destinationAccount));
    verify(destinationDevice, times(1)).setUninstalledFeedbackTimestamp(eq(Util.todayInMillis()));
  }
  
  @Test
  public void testCanonicalId() {
    String destinationNumber = "+12223334444";
    String gcmId             = "foo";
    String canonicalId       = "bar";

    AccountsManager            accountsManager = mock(AccountsManager.class);
    Sender                     sender          = mock(Sender.class         );
    Result                     canonicalResult = mock(Result.class         );
    SynchronousExecutorService executorService = new SynchronousExecutorService();

    Account destinationAccount = mock(Account.class);
    Device  destinationDevice  = mock(Device.class );

    when(destinationAccount.getDevice(1)).thenReturn(Optional.of(destinationDevice));
    when(accountsManager.get(destinationNumber)).thenReturn(Optional.of(destinationAccount));
    when(destinationDevice.getGcmId()).thenReturn(gcmId);

    when(canonicalResult.isInvalidRegistrationId()).thenReturn(false);
    when(canonicalResult.isUnregistered()).thenReturn(false);
    when(canonicalResult.hasCanonicalRegistrationId()).thenReturn(true);
    when(canonicalResult.isSuccess()).thenReturn(false);
    when(canonicalResult.getCanonicalRegistrationId()).thenReturn(canonicalId);

    GcmMessage message = new GcmMessage(gcmId, destinationNumber, 1, GcmMessage.Type.NOTIFICATION, Optional.empty());
    GCMSender gcmSender = new GCMSender(accountsManager, sender, executorService);

    CompletableFuture<Result> invalidFuture = CompletableFuture.completedFuture(canonicalResult);

    when(sender.send(any(Message.class))).thenReturn(invalidFuture);

    gcmSender.sendMessage(message);

    verify(sender, times(1)).send(any(Message.class));
    verify(accountsManager, times(1)).get(eq(destinationNumber));
    verify(accountsManager, times(1)).update(eq(destinationAccount));
    verify(destinationDevice, times(1)).setGcmId(eq(canonicalId));
  }

}
