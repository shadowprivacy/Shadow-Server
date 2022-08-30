/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.websocket;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;

import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.ClientPresenceManager;
import su.sres.shadowserver.push.MessageSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.util.Constants;

import su.sres.websocket.session.WebSocketSessionContext;
import su.sres.websocket.setup.WebSocketConnectListener;

import static com.codahale.metrics.MetricRegistry.name;

import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthenticatedConnectListener implements WebSocketConnectListener {

  private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private static final Timer durationTimer = metricRegistry.timer(name(WebSocketConnection.class, "connected_duration"));
  private static final Timer unauthenticatedDurationTimer = metricRegistry.timer(name(WebSocketConnection.class, "unauthenticated_connection_duration"));
  private static final Counter openWebsocketCounter = metricRegistry.counter(name(WebSocketConnection.class, "open_websockets"));

  private static final Logger log = LoggerFactory.getLogger(AuthenticatedConnectListener.class);

  private final ReceiptSender receiptSender;
  private final MessagesManager messagesManager;
  private final MessageSender messageSender;
  private final ApnFallbackManager apnFallbackManager;
  private final ClientPresenceManager clientPresenceManager;
  private final ScheduledExecutorService retrySchedulingExecutor;

  public AuthenticatedConnectListener(ReceiptSender receiptSender,
      MessagesManager messagesManager,
      final MessageSender messageSender, ApnFallbackManager apnFallbackManager,
      ClientPresenceManager clientPresenceManager,
      ScheduledExecutorService retrySchedulingExecutor) {
    this.receiptSender = receiptSender;
    this.messagesManager = messagesManager;
    this.messageSender = messageSender;
    this.apnFallbackManager = apnFallbackManager;
    this.clientPresenceManager = clientPresenceManager;
    this.retrySchedulingExecutor = retrySchedulingExecutor;
  }

  @Override
  public void onWebSocketConnect(WebSocketSessionContext context) {
    if (context.getAuthenticated() != null) {
      final Account account = context.getAuthenticated(Account.class);
      final Device device = account.getAuthenticatedDevice().get();
      final Timer.Context timer = durationTimer.time();
      final WebSocketConnection connection = new WebSocketConnection(receiptSender,
          messagesManager, account, device,
          context.getClient(),
          retrySchedulingExecutor);

      openWebsocketCounter.inc();
      try {
        RedisOperation.unchecked(() -> apnFallbackManager.cancel(account, device));
      } catch (Exception e) {
// log nothing, just ignore the exception
      }

      context.addListener(new WebSocketSessionContext.WebSocketEventListener() {
        @Override
        public void onWebSocketClose(WebSocketSessionContext context, int statusCode, String reason) {

          openWebsocketCounter.dec();
          timer.stop();
          
          connection.stop();

          RedisOperation.unchecked(() -> clientPresenceManager.clearPresence(account.getUuid(), device.getId()));
          RedisOperation.unchecked(() -> {
            messagesManager.removeMessageAvailabilityListener(connection);

            if (messagesManager.hasCachedMessages(account.getUuid(), device.getId())) {
              messageSender.sendNewMessageNotification(account, device);
            }
          });
        }
      });
      try {
        clientPresenceManager.setPresent(account.getUuid(), device.getId(), connection);
        messagesManager.addMessageAvailabilityListener(account.getUuid(), device.getId(), connection);
        connection.start();
      } catch (final Exception e) {
        log.warn("Failed to initialize websocket", e);
        context.getClient().close(1011, "Unexpected error initializing connection");
      }
    } else {
      final Timer.Context timer = unauthenticatedDurationTimer.time();
      context.addListener((context1, statusCode, reason) -> timer.stop());
    }
  }
}
