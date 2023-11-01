/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import com.google.protobuf.ByteString;
import su.sres.shadowserver.entities.MessageProtos;
import su.sres.shadowserver.entities.OutgoingMessageEntity;
import su.sres.shadowserver.util.MessagesDynamoDbExtension;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesScyllaDbTest {
  private static final Random random = new Random();
  private static final MessageProtos.Envelope MESSAGE1;
  private static final MessageProtos.Envelope MESSAGE2;
  private static final MessageProtos.Envelope MESSAGE3;

  static {
    final long serverTimestamp = System.currentTimeMillis();
    MessageProtos.Envelope.Builder builder = MessageProtos.Envelope.newBuilder();
    builder.setType(MessageProtos.Envelope.Type.UNIDENTIFIED_SENDER);
    builder.setTimestamp(123456789L);
    builder.setContent(ByteString.copyFrom(new byte[] { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF }));
    builder.setServerGuid(UUID.randomUUID().toString());
    builder.setServerTimestamp(serverTimestamp);

    MESSAGE1 = builder.build();

    builder.setType(MessageProtos.Envelope.Type.CIPHERTEXT);
    builder.setSource("12348675309");
    builder.setSourceUuid(UUID.randomUUID().toString());
    builder.setSourceDevice(1);
    builder.setContent(ByteString.copyFromUtf8("MOO"));
    builder.setServerGuid(UUID.randomUUID().toString());
    builder.setServerTimestamp(serverTimestamp + 1);

    MESSAGE2 = builder.build();

    builder.setType(MessageProtos.Envelope.Type.UNIDENTIFIED_SENDER);
    builder.clearSource();
    builder.clearSourceUuid();
    builder.clearSourceDevice();
    builder.setContent(ByteString.copyFromUtf8("COW"));
    builder.setServerGuid(UUID.randomUUID().toString());
    builder.setServerTimestamp(serverTimestamp); // Test same millisecond arrival for two different messages

    MESSAGE3 = builder.build();
  }

  private MessagesScyllaDb messagesScyllaDb;

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = MessagesDynamoDbExtension.build();

  @BeforeEach
  void setup() {
    messagesScyllaDb = new MessagesScyllaDb(dynamoDbExtension.getDynamoDbClient(), MessagesDynamoDbExtension.TABLE_NAME,
        Duration.ofDays(14));
  }

  @Test
  void testServerStart() {
  }

  @Test
  void testSimpleFetchAfterInsert() {
    final UUID destinationUuid = UUID.randomUUID();
    final int destinationDeviceId = random.nextInt(255) + 1;
    messagesScyllaDb.store(List.of(MESSAGE1, MESSAGE2, MESSAGE3), destinationUuid, destinationDeviceId);

    final List<OutgoingMessageEntity> messagesStored = messagesScyllaDb.load(destinationUuid, destinationDeviceId, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE);
    assertThat(messagesStored).isNotNull().hasSize(3);
    final MessageProtos.Envelope firstMessage = MESSAGE1.getServerGuid().compareTo(MESSAGE3.getServerGuid()) < 0 ? MESSAGE1 : MESSAGE3;
    final MessageProtos.Envelope secondMessage = firstMessage == MESSAGE1 ? MESSAGE3 : MESSAGE1;
    assertThat(messagesStored).element(0).satisfies(verify(firstMessage));
    assertThat(messagesStored).element(1).satisfies(verify(secondMessage));
    assertThat(messagesStored).element(2).satisfies(verify(MESSAGE2));
  }

  @Test
  void testDeleteForDestination() {
    final UUID destinationUuid = UUID.randomUUID();
    final UUID secondDestinationUuid = UUID.randomUUID();
    messagesScyllaDb.store(List.of(MESSAGE1), destinationUuid, 1);
    messagesScyllaDb.store(List.of(MESSAGE2), secondDestinationUuid, 1);
    messagesScyllaDb.store(List.of(MESSAGE3), destinationUuid, 2);

    assertThat(messagesScyllaDb.load(destinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE1));
    assertThat(messagesScyllaDb.load(destinationUuid, 2, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE3));
    assertThat(messagesScyllaDb.load(secondDestinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE2));

    messagesScyllaDb.deleteAllMessagesForAccount(destinationUuid);

    assertThat(messagesScyllaDb.load(destinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().isEmpty();
    assertThat(messagesScyllaDb.load(destinationUuid, 2, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().isEmpty();
    assertThat(messagesScyllaDb.load(secondDestinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE2));
  }

  @Test
  void testDeleteForDestinationDevice() {
    final UUID destinationUuid = UUID.randomUUID();
    final UUID secondDestinationUuid = UUID.randomUUID();
    messagesScyllaDb.store(List.of(MESSAGE1), destinationUuid, 1);
    messagesScyllaDb.store(List.of(MESSAGE2), secondDestinationUuid, 1);
    messagesScyllaDb.store(List.of(MESSAGE3), destinationUuid, 2);

    assertThat(messagesScyllaDb.load(destinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE1));
    assertThat(messagesScyllaDb.load(destinationUuid, 2, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE3));
    assertThat(messagesScyllaDb.load(secondDestinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE2));

    messagesScyllaDb.deleteAllMessagesForDevice(destinationUuid, 2);

    assertThat(messagesScyllaDb.load(destinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE1));
    assertThat(messagesScyllaDb.load(destinationUuid, 2, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().isEmpty();
    assertThat(messagesScyllaDb.load(secondDestinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE2));
  }

  @Test
  void testDeleteMessageByDestinationAndGuid() {
    final UUID destinationUuid = UUID.randomUUID();
    final UUID secondDestinationUuid = UUID.randomUUID();
    messagesScyllaDb.store(List.of(MESSAGE1), destinationUuid, 1);
    messagesScyllaDb.store(List.of(MESSAGE2), secondDestinationUuid, 1);
    messagesScyllaDb.store(List.of(MESSAGE3), destinationUuid, 2);

    assertThat(messagesScyllaDb.load(destinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE1));
    assertThat(messagesScyllaDb.load(destinationUuid, 2, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE3));
    assertThat(messagesScyllaDb.load(secondDestinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE2));

    messagesScyllaDb.deleteMessageByDestinationAndGuid(secondDestinationUuid, UUID.fromString(MESSAGE2.getServerGuid()));

    assertThat(messagesScyllaDb.load(destinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE1));
    assertThat(messagesScyllaDb.load(destinationUuid, 2, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().hasSize(1).element(0).satisfies(verify(MESSAGE3));
    assertThat(messagesScyllaDb.load(secondDestinationUuid, 1, MessagesScyllaDb.RESULT_SET_CHUNK_SIZE)).isNotNull().isEmpty();
  }

  private static void verify(OutgoingMessageEntity retrieved, MessageProtos.Envelope inserted) {
    assertThat(retrieved.getTimestamp()).isEqualTo(inserted.getTimestamp());
    assertThat(retrieved.getSource()).isEqualTo(inserted.hasSource() ? inserted.getSource() : null);
    assertThat(retrieved.getSourceUuid()).isEqualTo(inserted.hasSourceUuid() ? UUID.fromString(inserted.getSourceUuid()) : null);
    assertThat(retrieved.getSourceDevice()).isEqualTo(inserted.getSourceDevice());
    assertThat(retrieved.getRelay()).isEqualTo(inserted.hasRelay() ? inserted.getRelay() : null);
    assertThat(retrieved.getType()).isEqualTo(inserted.getType().getNumber());
    assertThat(retrieved.getContent()).isEqualTo(inserted.hasContent() ? inserted.getContent().toByteArray() : null);
    assertThat(retrieved.getMessage()).isEqualTo(inserted.hasLegacyMessage() ? inserted.getLegacyMessage().toByteArray() : null);
    assertThat(retrieved.getServerTimestamp()).isEqualTo(inserted.getServerTimestamp());
    assertThat(retrieved.getGuid()).isEqualTo(UUID.fromString(inserted.getServerGuid()));
  }

  private static VerifyMessage verify(MessageProtos.Envelope expected) {
    return new VerifyMessage(expected);
  }

  private static final class VerifyMessage implements Consumer<OutgoingMessageEntity> {
    private final MessageProtos.Envelope expected;

    public VerifyMessage(MessageProtos.Envelope expected) {
      this.expected = expected;
    }

    @Override
    public void accept(OutgoingMessageEntity outgoingMessageEntity) {
      verify(outgoingMessageEntity, expected);
    }
  }
}
