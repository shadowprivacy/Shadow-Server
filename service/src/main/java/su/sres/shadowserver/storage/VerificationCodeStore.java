/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.util.AttributeValues;
import su.sres.shadowserver.util.SystemMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import static com.codahale.metrics.MetricRegistry.name;

public class VerificationCodeStore {

  private final DynamoDbClient scyllaDbClient;
  private final String tableName;

  private final Timer insertTimer;
  private final Timer getTimer;
  private final Timer removeTimer;

  @VisibleForTesting
  static final String KEY_USER_LOGIN = "P";

  private static final String ATTR_STORED_CODE = "C";
  private static final String ATTR_TTL = "E";

  private static final Logger log = LoggerFactory.getLogger(VerificationCodeStore.class);

  public VerificationCodeStore(final DynamoDbClient scyllaDbClient, final String tableName) {
    this.scyllaDbClient = scyllaDbClient;
    this.tableName = tableName;

    this.insertTimer = Metrics.timer(name(getClass(), "insert"), "table", tableName);
    this.getTimer = Metrics.timer(name(getClass(), "get"), "table", tableName);
    this.removeTimer = Metrics.timer(name(getClass(), "remove"), "table", tableName);
  }

  public void insert(final String userLogin, final StoredVerificationCode verificationCode, int lifetime) {
    insertTimer.record(() -> {
      try {
        scyllaDbClient.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(Map.of(
                KEY_USER_LOGIN, AttributeValues.fromString(userLogin),
                ATTR_STORED_CODE, AttributeValues.fromString(SystemMapper.getMapper().writeValueAsString(verificationCode)),
                ATTR_TTL, AttributeValues.fromLong(getExpirationTimestamp(verificationCode, lifetime))))
            .build());
      } catch (final JsonProcessingException e) {
        // This should never happen when writing directly to a string except in cases of
        // serious misconfiguration, which
        // would be caught by tests.
        throw new AssertionError(e);
      }
    });
  }

  private long getExpirationTimestamp(final StoredVerificationCode storedVerificationCode, int lifetime) {
    return Instant.ofEpochMilli(storedVerificationCode.getTimestamp()).plus(Duration.ofHours(lifetime)).getEpochSecond();
  }

  public Optional<StoredVerificationCode> findForUserLogin(final String userLogin) {
    return getTimer.record(() -> {
      final GetItemResponse response = scyllaDbClient.getItem(GetItemRequest.builder()
          .tableName(tableName)
          .consistentRead(true)
          .key(Map.of(KEY_USER_LOGIN, AttributeValues.fromString(userLogin)))
          .build());

      try {
        return response.hasItem()
            ? Optional.of(SystemMapper.getMapper().readValue(response.item().get(ATTR_STORED_CODE).s(), StoredVerificationCode.class))
            : Optional.empty();
      } catch (final JsonProcessingException e) {
        log.error("Failed to parse stored verification code", e);
        return Optional.empty();
      }
    });
  }

  public void remove(final String userLogin) {
    removeTimer.record(() -> {
      scyllaDbClient.deleteItem(DeleteItemRequest.builder()
          .tableName(tableName)
          .key(Map.of(KEY_USER_LOGIN, AttributeValues.fromString(userLogin)))
          .build());
    });
  }
}
