/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import su.sres.shadowserver.util.Pair;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

class DeletedAccountsTest {

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = DynamoDbExtension.builder()
      .tableName("deleted_accounts_test")
      .hashKey(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN)
          .attributeType(ScalarAttributeType.S).build())
      .build();

  private DeletedAccounts deletedAccounts;

  @BeforeEach
  void setUp() {
    deletedAccounts = new DeletedAccounts(dynamoDbExtension.getDynamoDbClient(),
        dynamoDbExtension.getTableName());
  }
  
  @Test
  void testPutList() {

    UUID firstUuid = UUID.randomUUID();
    UUID secondUuid = UUID.randomUUID();
    UUID thirdUuid = UUID.randomUUID();

    String firstNumber = "+14152221234";
    String secondNumber = "+14152225678";
    String thirdNumber = "+14159998765";

    assertTrue(deletedAccounts.list(1).isEmpty());

    deletedAccounts.put(firstUuid, firstNumber);
    deletedAccounts.put(secondUuid, secondNumber);
    deletedAccounts.put(thirdUuid, thirdNumber);

    assertEquals(1, deletedAccounts.list(1).size());

    assertTrue(deletedAccounts.list(10).containsAll(
        List.of(
            new Pair<>(firstUuid, firstNumber),
            new Pair<>(secondUuid, secondNumber))));

    deletedAccounts.delete(List.of(firstNumber, secondNumber));
    
    assertEquals(List.of(new Pair<>(thirdUuid, thirdNumber)), deletedAccounts.list(10));

    deletedAccounts.delete(List.of(thirdNumber));

    assertTrue(deletedAccounts.list(1).isEmpty());
    
  }
}
