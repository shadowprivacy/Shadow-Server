/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.google.protobuf.ByteString;
import io.lettuce.core.cluster.SlotHash;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.entities.MessageProtos;
import su.sres.shadowserver.entities.MessageProtos.Envelope.Type;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.redis.RedisClusterExtension;
import su.sres.shadowserver.util.AttributeValues;
import su.sres.shadowserver.util.MessagesDynamoDbExtension;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagePersisterIntegrationTest {

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = MessagesDynamoDbExtension.build();

  @RegisterExtension
  static final RedisClusterExtension REDIS_CLUSTER_EXTENSION = RedisClusterExtension.builder().build();

  private ExecutorService notificationExecutorService;
  private MessagesCache messagesCache;
  private MessagesManager messagesManager;
  private MessagePersister messagePersister;
  private Account account;

  private static final Duration PERSIST_DELAY = Duration.ofMinutes(10);

  @BeforeEach
  void setUp() throws Exception {
    REDIS_CLUSTER_EXTENSION.getRedisCluster().useCluster(connection -> {
      connection.sync().flushall();
      connection.sync().upstream().commands().configSet("notify-keyspace-events", "K$glz");
    });

    final MessagesScyllaDb messagesDynamoDb = new MessagesScyllaDb(dynamoDbExtension.getDynamoDbClient(),
        MessagesDynamoDbExtension.TABLE_NAME, Duration.ofDays(14));
    final AccountsManager accountsManager = mock(AccountsManager.class);
    final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);

    notificationExecutorService = Executors.newSingleThreadExecutor();
    messagesCache = new MessagesCache(REDIS_CLUSTER_EXTENSION.getRedisCluster(),
        REDIS_CLUSTER_EXTENSION.getRedisCluster(), notificationExecutorService);
    messagesManager = new MessagesManager(messagesDynamoDb, messagesCache, mock(PushLatencyManager.class),
        mock(ReportMessageManager.class));
    messagePersister = new MessagePersister(messagesCache, messagesManager, accountsManager,
        dynamicConfiguration, PERSIST_DELAY);

    account = mock(Account.class);

    final UUID accountUuid = UUID.randomUUID();

    when(account.getUserLogin()).thenReturn("+18005551234");
    when(account.getUuid()).thenReturn(accountUuid);
    when(accountsManager.get(accountUuid)).thenReturn(Optional.of(account));

    messagesCache.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    notificationExecutorService.shutdown();
    notificationExecutorService.awaitTermination(15, TimeUnit.SECONDS);
  }

  @Test
  void testScheduledPersistMessages() {

    final int messageCount = 377;
    final List<MessageProtos.Envelope> expectedMessages = new ArrayList<>(messageCount);
    final Instant now = Instant.now();

    assertTimeout(Duration.ofSeconds(15), () -> {

      for (int i = 0; i < messageCount; i++) {
        final UUID messageGuid = UUID.randomUUID();
        final long timestamp = now.minus(PERSIST_DELAY.multipliedBy(2)).toEpochMilli() + i;

        final MessageProtos.Envelope message = generateRandomMessage(messageGuid, timestamp);

        messagesCache.insert(messageGuid, account.getUuid(), 1, message);
        expectedMessages.add(message);
      }

      REDIS_CLUSTER_EXTENSION.getRedisCluster()
          .useCluster(connection -> connection.sync().set(MessagesCache.NEXT_SLOT_TO_PERSIST_KEY,
              String.valueOf(SlotHash.getSlot(MessagesCache.getMessageQueueKey(account.getUuid(), 1)) - 1)));

      final AtomicBoolean messagesPersisted = new AtomicBoolean(false);

      messagesManager.addMessageAvailabilityListener(account.getUuid(), 1, new MessageAvailabilityListener() {
        @Override
        public void handleNewMessagesAvailable() {
        }

        @Override
        public void handleMessagesPersisted() {
          synchronized (messagesPersisted) {
            messagesPersisted.set(true);
            messagesPersisted.notifyAll();
          }
        }
      });

      messagePersister.start();

      synchronized (messagesPersisted) {
        while (!messagesPersisted.get()) {
          messagesPersisted.wait();
        }
      }

      messagePersister.stop();

      final List<MessageProtos.Envelope> persistedMessages = new ArrayList<>(messageCount);

      DynamoDbClient dynamoDB = dynamoDbExtension.getDynamoDbClient();
      for (Map<String, AttributeValue> item : dynamoDB
          .scan(ScanRequest.builder().tableName(MessagesDynamoDbExtension.TABLE_NAME).build()).items()) {
        persistedMessages.add(MessageProtos.Envelope.newBuilder()
            .setServerGuid(AttributeValues.getUUID(item, "U", null).toString())
            .setType(Type.forNumber(AttributeValues.getInt(item, "T", -1)))
            .setTimestamp(AttributeValues.getLong(item, "TS", -1))
            .setServerTimestamp(extractServerTimestamp(AttributeValues.getByteArray(item, "S", null)))
            .setContent(ByteString.copyFrom(AttributeValues.getByteArray(item, "C", null)))
            .build());
      }

      assertEquals(expectedMessages, persistedMessages);
    });
  }

  private static long extractServerTimestamp(byte[] bytes) {
    ByteBuffer bb = ByteBuffer.wrap(bytes);
    bb.getLong();
    return bb.getLong();
  }

  private MessageProtos.Envelope generateRandomMessage(final UUID messageGuid, final long timestamp) {
    return MessageProtos.Envelope.newBuilder()
        .setTimestamp(timestamp)
        .setServerTimestamp(timestamp)
        .setContent(ByteString.copyFromUtf8(RandomStringUtils.randomAlphanumeric(256)))
        .setType(MessageProtos.Envelope.Type.CIPHERTEXT)
        .setServerGuid(messageGuid.toString())
        .build();
  }
}
