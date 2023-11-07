/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import su.sres.shadowserver.util.AttributeValues;
import su.sres.shadowserver.util.Pair;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

public class DeletedAccounts extends AbstractScyllaDbStore {

  // user login, primary key
  static final String KEY_ACCOUNT_USER_LOGIN = "P";
  static final String ATTR_ACCOUNT_UUID = "U";

  private final String tableName;

  public DeletedAccounts(final DynamoDbClient dynamoDb, final String tableName) {

    super(dynamoDb);
    this.tableName = tableName;
  }

  public void put(UUID uuid, String userLogin) {
    db().putItem(PutItemRequest.builder()
        .tableName(tableName)
        .item(Map.of(
            KEY_ACCOUNT_USER_LOGIN, AttributeValues.fromString(userLogin),
            ATTR_ACCOUNT_UUID, AttributeValues.fromUUID(uuid)))
        .build());
  }
  
  public Optional<UUID> findUuid(final String userLogin) {
    final GetItemResponse response = db().getItem(GetItemRequest.builder()
        .tableName(tableName)
        .consistentRead(true)
        .key(Map.of(KEY_ACCOUNT_USER_LOGIN, AttributeValues.fromString(userLogin)))
        .build());

    return Optional.ofNullable(AttributeValues.getUUID(response.item(), ATTR_ACCOUNT_UUID, null));
  }

  void remove(final String userLogin) {
    db().deleteItem(DeleteItemRequest.builder()
        .tableName(tableName)
        .key(Map.of(KEY_ACCOUNT_USER_LOGIN, AttributeValues.fromString(userLogin)))
        .build());
  }

  public List<Pair<UUID, String>> list(final int max) {

    final ScanRequest scanRequest = ScanRequest.builder()
        .tableName(tableName)
        .limit(max)
        .build();

    return scan(scanRequest, max)
        .stream()
        .map(item -> new Pair<>(
            AttributeValues.getUUID(item, ATTR_ACCOUNT_UUID, null),
            AttributeValues.getString(item, KEY_ACCOUNT_USER_LOGIN, null)))
        .collect(Collectors.toList());
  }

  public void delete(final List<String> userLoginsToDelete) {    
    
    userLoginsToDelete.forEach(userLogin -> db().deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                KEY_ACCOUNT_USER_LOGIN, AttributeValues.fromString(userLogin)
            ))            
            .build()
    ));    
  }
}
