/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

class PushChallengeScyllaDbTest {

  private PushChallengeScyllaDb pushChallengeScyllaDb;

  private static final long CURRENT_TIME_MILLIS = 1_000_000_000;

  private static final Random RANDOM = new Random();
  private static final String TABLE_NAME = "push_challenge_test";

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = DynamoDbExtension.builder()
      .tableName(TABLE_NAME)
      .hashKey(PushChallengeScyllaDb.KEY_ACCOUNT_UUID)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(PushChallengeScyllaDb.KEY_ACCOUNT_UUID)
          .attributeType(ScalarAttributeType.B)
          .build())
      .build();

  @BeforeEach
  void setUp() {
    this.pushChallengeScyllaDb = new PushChallengeScyllaDb(dynamoDbExtension.getDynamoDbClient(), TABLE_NAME, Clock.fixed(
        Instant.ofEpochMilli(CURRENT_TIME_MILLIS), ZoneId.systemDefault()));
  }

  @Test
  void add() {
    final UUID uuid = UUID.randomUUID();

    assertTrue(pushChallengeScyllaDb.add(uuid, generateRandomToken(), Duration.ofMinutes(1)));
    assertFalse(pushChallengeScyllaDb.add(uuid, generateRandomToken(), Duration.ofMinutes(1)));
  }

  @Test
  void remove() {
    final UUID uuid = UUID.randomUUID();
    final byte[] token = generateRandomToken();

    assertFalse(pushChallengeScyllaDb.remove(uuid, token));
    assertTrue(pushChallengeScyllaDb.add(uuid, token, Duration.ofMinutes(1)));
    assertTrue(pushChallengeScyllaDb.remove(uuid, token));
  }

  @Test
  void getExpirationTimestamp() {
    assertEquals((CURRENT_TIME_MILLIS / 1000) + 3600,
        pushChallengeScyllaDb.getExpirationTimestamp(Duration.ofHours(1)));
  }

  private static byte[] generateRandomToken() {
    final byte[] token = new byte[16];
    RANDOM.nextBytes(token);

    return token;
  }
}
