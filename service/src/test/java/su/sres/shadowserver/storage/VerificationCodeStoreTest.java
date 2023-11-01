/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import su.sres.shadowserver.auth.StoredVerificationCode;

import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationCodeStoreTest {

  private VerificationCodeStore verificationCodeStore;

  private static final String TABLE_NAME = "verification_code_test";

  private static final String USER_LOGIN = "+14151112222";
  
  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = DynamoDbExtension.builder()
      .tableName(TABLE_NAME)
      .hashKey(VerificationCodeStore.KEY_USER_LOGIN)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(VerificationCodeStore.KEY_USER_LOGIN)
          .attributeType(ScalarAttributeType.S)
          .build())
      .build();

  @BeforeEach
  void setUp() {
    verificationCodeStore = new VerificationCodeStore(DYNAMO_DB_EXTENSION.getDynamoDbClient(), TABLE_NAME);
  }
  
  @Test
  void testStoreAndFind() {
    assertEquals(Optional.empty(), verificationCodeStore.findForUserLogin(USER_LOGIN));

    final StoredVerificationCode originalCode = new StoredVerificationCode("1234", 1111, "abcd");
    final StoredVerificationCode secondCode = new StoredVerificationCode("5678", 2222, "efgh");
    final int lifetime = 24;

    verificationCodeStore.insert(USER_LOGIN, originalCode, lifetime);

    {
      final Optional<StoredVerificationCode> maybeCode = verificationCodeStore.findForUserLogin(USER_LOGIN);

      assertTrue(maybeCode.isPresent());
      assertTrue(storedVerificationCodesAreEqual(originalCode, maybeCode.get()));
    }

    verificationCodeStore.insert(USER_LOGIN, secondCode, lifetime);

    {
      final Optional<StoredVerificationCode> maybeCode = verificationCodeStore.findForUserLogin(USER_LOGIN);

      assertTrue(maybeCode.isPresent());
      assertTrue(storedVerificationCodesAreEqual(secondCode, maybeCode.get()));
    }
  }

  @Test
  void testRemove() {
    assertEquals(Optional.empty(), verificationCodeStore.findForUserLogin(USER_LOGIN));
    
    final int lifetime = 24;

    verificationCodeStore.insert(USER_LOGIN, new StoredVerificationCode("1234", 1111, "abcd"), lifetime);
    assertTrue(verificationCodeStore.findForUserLogin(USER_LOGIN).isPresent());

    verificationCodeStore.remove(USER_LOGIN);
    assertFalse(verificationCodeStore.findForUserLogin(USER_LOGIN).isPresent());
  }

  private static boolean storedVerificationCodesAreEqual(final StoredVerificationCode first, final StoredVerificationCode second) {
    if (first == null && second == null) {
      return true;
    } else if (first == null || second == null) {
      return false;
    }
    return Objects.equals(first.getCode(), second.getCode()) &&
        first.getTimestamp() == second.getTimestamp() &&
        Objects.equals(first.getPushCode(), second.getPushCode());
  }
}
