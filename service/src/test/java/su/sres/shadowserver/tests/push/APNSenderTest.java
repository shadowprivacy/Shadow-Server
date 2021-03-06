package su.sres.shadowserver.tests.push;

import com.google.common.util.concurrent.ListenableFuture;
import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsPushNotification;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import su.sres.shadowserver.push.APNSender;
import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.ApnMessage;
import su.sres.shadowserver.push.RetryingApnsClient;
import su.sres.shadowserver.push.RetryingApnsClient.ApnResult;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.tests.util.SynchronousExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class APNSenderTest {

  private static final String DESTINATION_NUMBER = "+14151231234";
  private static final String DESTINATION_APN_ID = "foo";

  private final AccountsManager accountsManager = mock(AccountsManager.class);

  private final Account            destinationAccount = mock(Account.class);
  private final Device             destinationDevice  = mock(Device.class);
  private final ApnFallbackManager fallbackManager    = mock(ApnFallbackManager.class);

  private final DefaultEventExecutor executor = new DefaultEventExecutor();

  @Before
  public void setup() {
    when(destinationAccount.getDevice(1)).thenReturn(Optional.of(destinationDevice));
    when(destinationDevice.getApnId()).thenReturn(DESTINATION_APN_ID);
    when(accountsManager.get(DESTINATION_NUMBER)).thenReturn(Optional.of(destinationAccount));
  }

  @Test
  public void testSendVoip() throws Exception {
    ApnsClient      apnsClient      = mock(ApnsClient.class);

    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
    when(response.isAccepted()).thenReturn(true);
   
    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
    .thenAnswer((Answer) invocationOnMock -> new MockPushNotificationFuture<>(executor, invocationOnMock.getArgument(0), response));

    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient);
    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, true, Optional.empty());
    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);

    apnSender.setApnFallbackManager(fallbackManager);
    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
    ApnResult apnResult = sendFuture.get();

    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
    verify(apnsClient, times(1)).sendNotification(notification.capture());

    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(ApnMessage.MAX_EXPIRATION));
    assertThat(notification.getValue().getPayload()).isEqualTo(ApnMessage.APN_NOTIFICATION_PAYLOAD);
    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);
    assertThat(notification.getValue().getTopic()).isEqualTo("foo.voip");

    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.SUCCESS);

    verifyNoMoreInteractions(apnsClient);
    verifyNoMoreInteractions(accountsManager);
    verifyNoMoreInteractions(fallbackManager);
  }

  @Test
  public void testSendApns() throws Exception {
    ApnsClient apnsClient = mock(ApnsClient.class);

    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
    when(response.isAccepted()).thenReturn(true);
   
    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
    .thenAnswer((Answer) invocationOnMock -> new MockPushNotificationFuture<>(executor, invocationOnMock.getArgument(0), response));

    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient);
    ApnMessage message = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, false, Optional.empty());
    APNSender apnSender = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
    apnSender.setApnFallbackManager(fallbackManager);

    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
    ApnResult apnResult = sendFuture.get();

    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
    verify(apnsClient, times(1)).sendNotification(notification.capture());

    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(ApnMessage.MAX_EXPIRATION));
    assertThat(notification.getValue().getPayload()).isEqualTo(ApnMessage.APN_NOTIFICATION_PAYLOAD);
    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);
    assertThat(notification.getValue().getTopic()).isEqualTo("foo");

    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.SUCCESS);

    verifyNoMoreInteractions(apnsClient);
    verifyNoMoreInteractions(accountsManager);
    verifyNoMoreInteractions(fallbackManager);
  }

  @Test
  public void testUnregisteredUser() throws Exception {
    ApnsClient      apnsClient      = mock(ApnsClient.class);

    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
    when(response.isAccepted()).thenReturn(false);
    when(response.getRejectionReason()).thenReturn("Unregistered");
   
    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
    .thenAnswer((Answer) invocationOnMock -> new MockPushNotificationFuture<>(executor, invocationOnMock.getArgument(0), response));

    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient);
    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, true, Optional.empty());
    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
    apnSender.setApnFallbackManager(fallbackManager);

    when(destinationDevice.getApnId()).thenReturn(DESTINATION_APN_ID);
    when(destinationDevice.getPushTimestamp()).thenReturn(System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(11));

    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
    ApnResult apnResult = sendFuture.get();

    Thread.sleep(1000); // =(

    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
    verify(apnsClient, times(1)).sendNotification(notification.capture());

    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(ApnMessage.MAX_EXPIRATION));
    assertThat(notification.getValue().getPayload()).isEqualTo(ApnMessage.APN_NOTIFICATION_PAYLOAD);
    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);

    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.NO_SUCH_USER);

    verifyNoMoreInteractions(apnsClient);
    verify(accountsManager, times(1)).get(eq(DESTINATION_NUMBER));
    verify(destinationAccount, times(1)).getDevice(1);
    verify(destinationDevice, times(1)).getApnId();
    verify(destinationDevice, times(1)).getPushTimestamp();
//    verify(destinationDevice, times(1)).setApnId(eq((String)null));
//    verify(destinationDevice, times(1)).setVoipApnId(eq((String)null));
//    verify(destinationDevice, times(1)).setFetchesMessages(eq(false));
//    verify(accountsManager, times(1)).update(eq(destinationAccount));
    verify(fallbackManager, times(1)).cancel(eq(destinationAccount), eq(destinationDevice));

    verifyNoMoreInteractions(accountsManager);
    verifyNoMoreInteractions(fallbackManager);
  }

//  @Test
//  public void testVoipUnregisteredUser() throws Exception {
//    ApnsClient      apnsClient      = mock(ApnsClient.class);
//
//    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
//    when(response.isAccepted()).thenReturn(false);
//    when(response.getRejectionReason()).thenReturn("Unregistered");
//
//    DefaultPromise<PushNotificationResponse<SimpleApnsPushNotification>> result = new DefaultPromise<>(executor);
//    result.setSuccess(response);
//
//    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
//        .thenReturn(result);
//
//    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient, 10);
//    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, "message", true, 30);
//    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
//    apnSender.setApnFallbackManager(fallbackManager);
//
//    when(destinationDevice.getApnId()).thenReturn("baz");
//    when(destinationDevice.getVoipApnId()).thenReturn(DESTINATION_APN_ID);
//    when(destinationDevice.getPushTimestamp()).thenReturn(System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(11));
//
//    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
//    ApnResult apnResult = sendFuture.get();
//
//    Thread.sleep(1000); // =(
//
//    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
//    verify(apnsClient, times(1)).sendNotification(notification.capture());
//
//    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
//    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(30));
//    assertThat(notification.getValue().getPayload()).isEqualTo("message");
//    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);
//
//    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.NO_SUCH_USER);
//
//    verifyNoMoreInteractions(apnsClient);
//    verify(accountsManager, times(1)).get(eq(DESTINATION_NUMBER));
//    verify(destinationAccount, times(1)).getDevice(1);
//    verify(destinationDevice, times(1)).getApnId();
//    verify(destinationDevice, times(1)).getVoipApnId();
//    verify(destinationDevice, times(1)).getPushTimestamp();
//    verify(destinationDevice, times(1)).setApnId(eq((String)null));
//    verify(destinationDevice, times(1)).setVoipApnId(eq((String)null));
//    verify(destinationDevice, times(1)).setFetchesMessages(eq(false));
//    verify(accountsManager, times(1)).update(eq(destinationAccount));
//    verify(fallbackManager, times(1)).cancel(eq(new WebsocketAddress(DESTINATION_NUMBER, 1)));
//
//    verifyNoMoreInteractions(accountsManager);
//    verifyNoMoreInteractions(fallbackManager);
//  }

  @Test
  public void testRecentUnregisteredUser() throws Exception {
    ApnsClient      apnsClient      = mock(ApnsClient.class);

    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
    when(response.isAccepted()).thenReturn(false);
    when(response.getRejectionReason()).thenReturn("Unregistered");
    
    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
    .thenAnswer((Answer) invocationOnMock -> new MockPushNotificationFuture<>(executor, invocationOnMock.getArgument(0), response));

    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient);
    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, true, Optional.empty());
    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
    apnSender.setApnFallbackManager(fallbackManager);

    when(destinationDevice.getApnId()).thenReturn(DESTINATION_APN_ID);
    when(destinationDevice.getPushTimestamp()).thenReturn(System.currentTimeMillis());

    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
    ApnResult apnResult = sendFuture.get();

    Thread.sleep(1000); // =(

    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
    verify(apnsClient, times(1)).sendNotification(notification.capture());

    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(ApnMessage.MAX_EXPIRATION));
    assertThat(notification.getValue().getPayload()).isEqualTo(ApnMessage.APN_NOTIFICATION_PAYLOAD);
    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);

    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.NO_SUCH_USER);

    verifyNoMoreInteractions(apnsClient);
    verify(accountsManager, times(1)).get(eq(DESTINATION_NUMBER));
    verify(destinationAccount, times(1)).getDevice(1);
    verify(destinationDevice, times(1)).getApnId();
    verify(destinationDevice, times(1)).getPushTimestamp();

    verifyNoMoreInteractions(destinationDevice);
    verifyNoMoreInteractions(destinationAccount);
    verifyNoMoreInteractions(accountsManager);
    verifyNoMoreInteractions(fallbackManager);
  }

//  @Test
//  public void testUnregisteredUserOldApnId() throws Exception {
//    ApnsClient      apnsClient      = mock(ApnsClient.class);
//
//    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
//    when(response.isAccepted()).thenReturn(false);
//    when(response.getRejectionReason()).thenReturn("Unregistered");
//
//    DefaultPromise<PushNotificationResponse<SimpleApnsPushNotification>> result = new DefaultPromise<>(executor);
//    result.setSuccess(response);
//
//    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
//        .thenReturn(result);
//
//    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient, 10);
//    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, "message", true, 30);
//    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
//    apnSender.setApnFallbackManager(fallbackManager);
//
//    when(destinationDevice.getApnId()).thenReturn("baz");
//    when(destinationDevice.getPushTimestamp()).thenReturn(System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(12));
//
//    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
//    ApnResult apnResult = sendFuture.get();
//
//    Thread.sleep(1000); // =(
//
//    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
//    verify(apnsClient, times(1)).sendNotification(notification.capture());
//
//    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
//    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(30));
//    assertThat(notification.getValue().getPayload()).isEqualTo("message");
//    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);
//
//    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.NO_SUCH_USER);
//
//    verifyNoMoreInteractions(apnsClient);
//    verify(accountsManager, times(1)).get(eq(DESTINATION_NUMBER));
//    verify(destinationAccount, times(1)).getDevice(1);
//    verify(destinationDevice, times(2)).getApnId();
//    verify(destinationDevice, times(2)).getVoipApnId();
//
//    verifyNoMoreInteractions(destinationDevice);
//    verifyNoMoreInteractions(destinationAccount);
//    verifyNoMoreInteractions(accountsManager);
//    verifyNoMoreInteractions(fallbackManager);
//  }

  @Test
  public void testGenericFailure() throws Exception {
    ApnsClient      apnsClient      = mock(ApnsClient.class);

    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
    when(response.isAccepted()).thenReturn(false);
    when(response.getRejectionReason()).thenReturn("BadTopic");
  
    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
    .thenAnswer((Answer) invocationOnMock -> new MockPushNotificationFuture<>(executor, invocationOnMock.getArgument(0), response));

    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient);
    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, true, Optional.empty());
    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
    apnSender.setApnFallbackManager(fallbackManager);

    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);
    ApnResult apnResult = sendFuture.get();

    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
    verify(apnsClient, times(1)).sendNotification(notification.capture());

    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(ApnMessage.MAX_EXPIRATION));
    assertThat(notification.getValue().getPayload()).isEqualTo(ApnMessage.APN_NOTIFICATION_PAYLOAD);
    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);

    assertThat(apnResult.getStatus()).isEqualTo(ApnResult.Status.GENERIC_FAILURE);

    verifyNoMoreInteractions(apnsClient);
    verifyNoMoreInteractions(accountsManager);
    verifyNoMoreInteractions(fallbackManager);
  }

  @Test
  @Ignore
  public void testFailure() throws Exception {
    ApnsClient      apnsClient      = mock(ApnsClient.class);

    PushNotificationResponse<SimpleApnsPushNotification> response = mock(PushNotificationResponse.class);
    when(response.isAccepted()).thenReturn(true);
   
    when(apnsClient.sendNotification(any(SimpleApnsPushNotification.class)))
    .thenAnswer((Answer) invocationOnMock -> new MockPushNotificationFuture<>(executor, invocationOnMock.getArgument(0), new Exception("lost connection")));

    RetryingApnsClient retryingApnsClient = new RetryingApnsClient(apnsClient);
    ApnMessage         message            = new ApnMessage(DESTINATION_APN_ID, DESTINATION_NUMBER, 1, true, Optional.empty());
    APNSender          apnSender          = new APNSender(new SynchronousExecutorService(), accountsManager, retryingApnsClient, "foo", false);
    apnSender.setApnFallbackManager(fallbackManager);

    ListenableFuture<ApnResult> sendFuture = apnSender.sendMessage(message);

    try {
        sendFuture.get();
        throw new AssertionError();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      } catch (ExecutionException e) {
        // good
      }

    ArgumentCaptor<SimpleApnsPushNotification> notification = ArgumentCaptor.forClass(SimpleApnsPushNotification.class);
    verify(apnsClient, times(1)).sendNotification(notification.capture());

    assertThat(notification.getValue().getToken()).isEqualTo(DESTINATION_APN_ID);
    assertThat(notification.getValue().getExpiration()).isEqualTo(new Date(ApnMessage.MAX_EXPIRATION));
    assertThat(notification.getValue().getPayload()).isEqualTo(ApnMessage.APN_NOTIFICATION_PAYLOAD);
    assertThat(notification.getValue().getPriority()).isEqualTo(DeliveryPriority.IMMEDIATE);
  
    verifyNoMoreInteractions(apnsClient);
    verifyNoMoreInteractions(accountsManager);
    verifyNoMoreInteractions(fallbackManager);
  }

  private static class MockPushNotificationFuture <P extends ApnsPushNotification, V> extends DefaultPromise<V> implements PushNotificationFuture<P, V> {

	  private final P pushNotification;

	  MockPushNotificationFuture(final EventExecutor eventExecutor, final P pushNotification) {
	      super(eventExecutor);
	      this.pushNotification = pushNotification;
	    }

	  MockPushNotificationFuture(final EventExecutor eventExecutor, final P pushNotification, final V response) {
	      super(eventExecutor);
	      this.pushNotification = pushNotification;
	      setSuccess(response);
    }

	  MockPushNotificationFuture(final EventExecutor eventExecutor, final P pushNotification, final Exception exception) {
	      super(eventExecutor);
	      this.pushNotification = pushNotification;
	      setFailure(exception);
	    }

	  @Override
	    public P getPushNotification() {
	      return pushNotification;
	    }
  }
}
