/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.sres.gcm.server.Message;
import su.sres.gcm.server.Result;
import su.sres.gcm.server.Sender;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.util.CircuitBreakerUtil;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.SystemMapper;
import su.sres.shadowserver.util.Util;

public class GCMSender {

  private final Logger logger = LoggerFactory.getLogger(GCMSender.class);

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter success = metricRegistry.meter(name(getClass(), "sent", "success"));
  private final Meter failure = metricRegistry.meter(name(getClass(), "sent", "failure"));
  private final Meter unregistered = metricRegistry.meter(name(getClass(), "sent", "unregistered"));
  private final Meter canonical = metricRegistry.meter(name(getClass(), "sent", "canonical"));

  private final Map<String, Meter> outboundMeters = new HashMap<>() {
    {
      put("receipt", metricRegistry.meter(name(getClass(), "outbound", "receipt")));
      put("notification", metricRegistry.meter(name(getClass(), "outbound", "notification")));
      put("challenge", metricRegistry.meter(name(getClass(), "outbound", "challenge")));
      put("rateLimitChallenge", metricRegistry.meter(name(getClass(), "outbound", "rateLimitChallenge")));
    }
  };

  private final AccountsManager accountsManager;
  private final Sender signalSender;
  private final ExecutorService executor;

  public GCMSender(ExecutorService executor, AccountsManager accountsManager, String signalKey) {
    this(executor, accountsManager, new Sender(signalKey, SystemMapper.getMapper(), 6));

    CircuitBreakerUtil.registerMetrics(metricRegistry, signalSender.getRetry(), Sender.class);
  }

  @VisibleForTesting
  public GCMSender(ExecutorService executor, AccountsManager accountsManager, Sender sender) {
    this.accountsManager = accountsManager;
    this.signalSender = sender;
    this.executor = executor;
  }

  public void sendMessage(GcmMessage message) {
    Message.Builder builder = Message.newBuilder()
        .withDestination(message.getGcmId())
        .withPriority("high");

    String key;

    switch (message.getType()) {
    case NOTIFICATION:
      key = "notification";
      break;
    case CHALLENGE:
      key = "challenge";
      break;
    case RATE_LIMIT_CHALLENGE:
      key = "rateLimitChallenge";
      break;
    default:
      throw new AssertionError();
    }

    Message request = builder.withDataPart(key, message.getData().orElse("")).build();

    CompletableFuture<Result> future = signalSender.send(request);
    markOutboundMeter(key);

    future.handle((result, throwable) -> {
      if (result != null && message.getType() != GcmMessage.Type.CHALLENGE) {
        if (result.isUnregistered() || result.isInvalidRegistrationId()) {
          executor.submit(() -> handleBadRegistration(message));
        } else if (result.hasCanonicalRegistrationId()) {
          executor.submit(() -> handleCanonicalRegistrationId(message, result));
        } else if (!result.isSuccess()) {
          executor.submit(() -> handleGenericError(message, result));
        } else {
          success.mark();
        }

      } else {
        logger.warn("FCM Failed: " + throwable + ", " + throwable.getCause());
      }

      return null;
    });
  }

  private void handleBadRegistration(GcmMessage message) {

    Optional<Account> account = getAccountForEvent(message);

    if (account.isPresent()) {
      // noinspection OptionalGetWithoutIsPresent
      Device device = account.get().getDevice(message.getDeviceId()).get();
      if (device.getUninstalledFeedbackTimestamp() == 0) {
        accountsManager.updateDevice(account.get(), message.getDeviceId(), d ->
        d.setUninstalledFeedbackTimestamp(Util.todayInMillis()));
      }
    }

    unregistered.mark();
  }

  private void handleCanonicalRegistrationId(GcmMessage message, Result result) {
    logger.warn("Actually received 'CanonicalRegistrationId' ::: (canonical={}}), (original={}})",
        result.getCanonicalRegistrationId(), message.getGcmId());

    getAccountForEvent(message).ifPresent(account ->
    accountsManager.updateDevice(
        account,
        message.getDeviceId(),
        d -> d.setGcmId(result.getCanonicalRegistrationId())));

    canonical.mark();
  }

  private void handleGenericError(GcmMessage message, Result result) {
    logger.warn("Unrecoverable Error ::: (error={}}), (gcm_id={}}), (destination={}}), (device_id={}})",
        result.getError(), message.getGcmId(), message.getUuid(), message.getDeviceId());
    failure.mark();
  }

  private Optional<Account> getAccountForEvent(GcmMessage message) {
    Optional<Account> account = message.getUuid().flatMap(accountsManager::get);

    if (account.isPresent()) {
      Optional<Device> device = account.get().getDevice(message.getDeviceId());

      if (device.isPresent()) {
        if (message.getGcmId().equals(device.get().getGcmId())) {

          if (device.get().getPushTimestamp() == 0 || System.currentTimeMillis() > (device.get().getPushTimestamp() + TimeUnit.SECONDS.toMillis(10))) {

            return account;
          }
        }
      }
    }

    return Optional.empty();
  }

  private void markOutboundMeter(String key) {
    Meter meter = outboundMeters.get(key);

    if (meter != null)
      meter.mark();
    else
      logger.warn("Unknown outbound key: " + key);
  }
}
