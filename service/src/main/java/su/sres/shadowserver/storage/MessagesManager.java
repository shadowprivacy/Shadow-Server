/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;

import su.sres.shadowserver.entities.OutgoingMessageEntity;
import su.sres.shadowserver.entities.OutgoingMessageEntityList;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
import su.sres.shadowserver.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;

public class MessagesManager {

  private static final int RESULT_SET_CHUNK_SIZE = 100;

  private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private static final Meter cacheHitByGuidMeter = metricRegistry.meter(name(MessagesManager.class, "cacheHitByGuid"));
  private static final Meter cacheMissByGuidMeter = metricRegistry.meter(
      name(MessagesManager.class, "cacheMissByGuid"));

  private static final Meter persistMessageMeter = metricRegistry.meter(name(MessagesManager.class, "persistMessage"));

  private final MessagesScyllaDb messagesScyllaDb;
  private final MessagesCache messagesCache;
  private final PushLatencyManager pushLatencyManager;
  private final ReportMessageManager reportMessageManager;

  public MessagesManager(MessagesScyllaDb messagesScyllaDb, MessagesCache messagesCache, PushLatencyManager pushLatencyManager, final ReportMessageManager reportMessageManager) {
    this.messagesScyllaDb = messagesScyllaDb;
    this.messagesCache = messagesCache;
    this.pushLatencyManager = pushLatencyManager;
    this.reportMessageManager = reportMessageManager;
  }

  public void insert(UUID destinationUuid, long destinationDevice, Envelope message) {
    final UUID messageGuid = UUID.randomUUID();

    messagesCache.insert(messageGuid, destinationUuid, destinationDevice, message);

    if (message.hasSource() && !destinationUuid.toString().equals(message.getSourceUuid())) {
      reportMessageManager.store(message.getSource(), messageGuid);
    }
  }  

  public boolean hasCachedMessages(final UUID destinationUuid, final long destinationDevice) {
    return messagesCache.hasMessages(destinationUuid, destinationDevice);
  }

  public OutgoingMessageEntityList getMessagesForDevice(UUID destinationUuid, long destinationDevice, final String userAgent, final boolean cachedMessagesOnly) {
    RedisOperation.unchecked(() -> pushLatencyManager.recordQueueRead(destinationUuid, destinationDevice, userAgent));

    List<OutgoingMessageEntity> messageList = new ArrayList<>();

    if (!cachedMessagesOnly) {
      messageList.addAll(messagesScyllaDb.load(destinationUuid, destinationDevice, RESULT_SET_CHUNK_SIZE));
    }

    if (messageList.size() < RESULT_SET_CHUNK_SIZE) {
      messageList.addAll(messagesCache.get(destinationUuid, destinationDevice, RESULT_SET_CHUNK_SIZE - messageList.size()));
    }

    return new OutgoingMessageEntityList(messageList, messageList.size() >= RESULT_SET_CHUNK_SIZE);
  }

  public void clear(UUID destinationUuid) {
    messagesCache.clear(destinationUuid);
    messagesScyllaDb.deleteAllMessagesForAccount(destinationUuid);
  }

  public void clear(UUID destinationUuid, long deviceId) {
    messagesCache.clear(destinationUuid, deviceId);

    messagesScyllaDb.deleteAllMessagesForDevice(destinationUuid, deviceId);
  }  

  public Optional<OutgoingMessageEntity> delete(UUID destinationUuid, long destinationDeviceId, UUID guid) {
    Optional<OutgoingMessageEntity> removed = messagesCache.remove(destinationUuid, destinationDeviceId, guid);

    if (removed.isEmpty()) {

      removed = messagesScyllaDb.deleteMessageByDestinationAndGuid(destinationUuid, guid);
      cacheMissByGuidMeter.mark();
    } else {
      cacheHitByGuidMeter.mark();
    }

    return removed;
  }

  public void persistMessages(final UUID destinationUuid, final long destinationDeviceId, final List<Envelope> messages) {

    final List<Envelope> nonEphemeralMessages = messages.stream()
        .filter(envelope -> !envelope.getEphemeral())
        .collect(Collectors.toList());

    messagesScyllaDb.store(nonEphemeralMessages, destinationUuid, destinationDeviceId);
    messagesCache.remove(destinationUuid, destinationDeviceId,
        messages.stream().map(message -> UUID.fromString(message.getServerGuid())).collect(Collectors.toList()));

    persistMessageMeter.mark(nonEphemeralMessages.size());
  }

  public void addMessageAvailabilityListener(
      final UUID destinationUuid,
      final long destinationDeviceId,
      final MessageAvailabilityListener listener) {
    messagesCache.addMessageAvailabilityListener(destinationUuid, destinationDeviceId, listener);
  }

  public void removeMessageAvailabilityListener(final MessageAvailabilityListener listener) {
    messagesCache.removeMessageAvailabilityListener(listener);
  }
}