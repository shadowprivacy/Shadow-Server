package su.sres.shadowserver.websocket;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;

import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.ClientPresenceManager;
import su.sres.shadowserver.push.PushSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.util.Constants;

import su.sres.websocket.session.WebSocketSessionContext;
import su.sres.websocket.setup.WebSocketConnectListener;

import java.security.SecureRandom;

import static com.codahale.metrics.MetricRegistry.name;

public class AuthenticatedConnectListener implements WebSocketConnectListener {

    private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
    private static final Timer durationTimer = metricRegistry.timer(name(WebSocketConnection.class, "connected_duration"));
    private static final Timer unauthenticatedDurationTimer = metricRegistry.timer(name(WebSocketConnection.class, "unauthenticated_connection_duration"));
    private static final Counter openWebsocketCounter = metricRegistry.counter(name(WebSocketConnection.class, "open_websockets"));

    private final ReceiptSender receiptSender;
    private final MessagesManager messagesManager;
    private final ApnFallbackManager apnFallbackManager;
    private final ClientPresenceManager clientPresenceManager;

    public AuthenticatedConnectListener(ReceiptSender receiptSender,
	    MessagesManager messagesManager,
	    ApnFallbackManager apnFallbackManager,
	    ClientPresenceManager clientPresenceManager) {
	this.receiptSender = receiptSender;
	this.messagesManager = messagesManager;
	this.apnFallbackManager = apnFallbackManager;
	this.clientPresenceManager = clientPresenceManager;
    }

    @Override
    public void onWebSocketConnect(WebSocketSessionContext context) {
	if (context.getAuthenticated() != null) {
	    final Account account = context.getAuthenticated(Account.class);
	    final Device device = account.getAuthenticatedDevice().get();
	    final String connectionId = String.valueOf(new SecureRandom().nextLong());
	    final Timer.Context timer = durationTimer.time();
	    final WebSocketConnection connection = new WebSocketConnection(receiptSender,
		    messagesManager, account, device,
		    context.getClient(), connectionId);

	    openWebsocketCounter.inc();
	    try {
		RedisOperation.unchecked(() -> apnFallbackManager.cancel(account, device));
	    } catch (Exception e) {
// log nothing, just ignore the exception
	    }

	    clientPresenceManager.setPresent(account.getUuid(), device.getId(), connection);
	    messagesManager.addMessageAvailabilityListener(account.getUuid(), device.getId(), connection);
	    connection.start();

	    context.addListener(new WebSocketSessionContext.WebSocketEventListener() {
		@Override
		public void onWebSocketClose(WebSocketSessionContext context, int statusCode, String reason) {
		    clientPresenceManager.clearPresence(account.getUuid(), device.getId());
		    messagesManager.removeMessageAvailabilityListener(connection);
		    connection.stop();

		    openWebsocketCounter.dec();
		    timer.stop();
		}
	    });
	} else {
	    final Timer.Context timer = unauthenticatedDurationTimer.time();
	    context.addListener((context1, statusCode, reason) -> timer.stop());
	}
    }
}
