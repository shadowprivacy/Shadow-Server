package su.sres.shadowserver.websocket;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.google.protobuf.ByteString;

import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.PushSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.PubSubManager;
import su.sres.shadowserver.storage.PubSubProtos.PubSubMessage;
import su.sres.shadowserver.util.Constants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.sres.websocket.session.WebSocketSessionContext;
import su.sres.websocket.setup.WebSocketConnectListener;

import java.security.SecureRandom;

import static com.codahale.metrics.MetricRegistry.name;

public class AuthenticatedConnectListener implements WebSocketConnectListener {

	  private static final Logger         logger                       = LoggerFactory.getLogger(WebSocketConnection.class);
	  private static final MetricRegistry metricRegistry               = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
	  private static final Timer          durationTimer                = metricRegistry.timer(name(WebSocketConnection.class, "connected_duration"                 ));
	  private static final Timer          unauthenticatedDurationTimer = metricRegistry.timer(name(WebSocketConnection.class, "unauthenticated_connection_duration"));

  private final PushSender         pushSender;
  private final ReceiptSender      receiptSender;
  private final MessagesManager    messagesManager;
  private final PubSubManager      pubSubManager;
  private final ApnFallbackManager apnFallbackManager;

  public AuthenticatedConnectListener(PushSender pushSender,
                                      ReceiptSender receiptSender,
                                      MessagesManager messagesManager,
                                      PubSubManager pubSubManager,
                                      ApnFallbackManager apnFallbackManager)
  {
    this.pushSender         = pushSender;
    this.receiptSender      = receiptSender;
    this.messagesManager    = messagesManager;
    this.pubSubManager      = pubSubManager;
    this.apnFallbackManager = apnFallbackManager;
  }

  @Override
  public void onWebSocketConnect(WebSocketSessionContext context) {
	  if (context.getAuthenticated() != null) {
	      final Account                 account        = context.getAuthenticated(Account.class);
	      final Device                  device         = account.getAuthenticatedDevice().get();
	      final String                  connectionId   = String.valueOf(new SecureRandom().nextLong());
	      final Timer.Context           timer          = durationTimer.time();
	      final WebsocketAddress        address        = new WebsocketAddress(account.getNumber(), device.getId());
	      final WebSocketConnection     connection     = new WebSocketConnection(pushSender, receiptSender,
	                                                                             messagesManager, account, device,
	                                                                             context.getClient(), connectionId);
	      final PubSubMessage           connectMessage = PubSubMessage.newBuilder().setType(PubSubMessage.Type.CONNECTED)
	                                                                  .setContent(ByteString.copyFrom(connectionId.getBytes()))
	                                                                  .build();
try {
    RedisOperation.unchecked(() -> apnFallbackManager.cancel(account, device));
} catch(Exception e) {
// log nothing, just ignore the exception
}
    pubSubManager.publish(address, connectMessage);
    pubSubManager.subscribe(address, connection);

    context.addListener(new WebSocketSessionContext.WebSocketEventListener() {
        @Override
        public void onWebSocketClose(WebSocketSessionContext context, int statusCode, String reason) {
          pubSubManager.unsubscribe(address, connection);
          timer.stop();
        }
      });
    } else {
      final Timer.Context timer = unauthenticatedDurationTimer.time();
      context.addListener((context1, statusCode, reason) -> timer.stop());
    }
  }
}

