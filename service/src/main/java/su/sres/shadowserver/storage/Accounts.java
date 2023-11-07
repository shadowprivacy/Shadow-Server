/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static com.codahale.metrics.MetricRegistry.name;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import su.sres.shadowserver.util.AttributeValues;
import su.sres.shadowserver.util.SystemMapper;
import su.sres.shadowserver.util.UUIDUtil;

public class Accounts extends AbstractScyllaDbStore {

  // uuid, primary key
  static final String KEY_ACCOUNT_UUID = "U";
  // user login
  static final String ATTR_ACCOUNT_USER_LOGIN = "P";
  // account, serialized to JSON
  static final String ATTR_ACCOUNT_DATA = "D";

  // internal version for optimistic locking
  static final String ATTR_VERSION = "V";
  static final String ATTR_ACCOUNT_VD = "VD";

  static final String KEY_PARAMETER_NAME = "PN";
  static final String ATTR_PARAMETER_VALUE = "PV";
  static final String DIRECTORY_VERSION_PARAMETER_NAME = "directory_version";

  private final DynamoDbClient client;

  // this table stores userLogin to UUID pairs
  private final String userLoginsTableName;
  private final String accountsTableName;
  private final String miscTableName;

  private final int scanPageSize;

  private static final Timer CREATE_TIMER = Metrics.timer(name(Accounts.class, "create"));
  private static final Timer UPDATE_TIMER = Metrics.timer(name(Accounts.class, "update"));
  private static final Timer GET_BY_USER_LOGIN_TIMER = Metrics.timer(name(Accounts.class, "getByUserLogin"));
  private static final Timer GET_BY_UUID_TIMER = Metrics.timer(name(Accounts.class, "getByUuid"));
  private static final Timer GET_ALL_FROM_START_TIMER = Metrics.timer(name(Accounts.class, "getAllFrom"));
  private static final Timer GET_ALL_FROM_OFFSET_TIMER = Metrics.timer(name(Accounts.class, "getAllFromOffset"));
  private static final Timer DELETE_TIMER = Metrics.timer(name(Accounts.class, "delete"));

  public Accounts(DynamoDbClient client, String accountsTableName, String userLoginsTableName, String miscTableName, final int scanPageSize) {
    super(client);

    this.client = client;
    this.accountsTableName = accountsTableName;
    this.userLoginsTableName = userLoginsTableName;
    this.miscTableName = miscTableName;
    this.scanPageSize = scanPageSize;
  }

  public boolean create(Account account, long directoryVersion) {

    return CREATE_TIMER.record(() -> {

      try {
        PutItemRequest userLoginConstraintPut = buildPutWriteItemForUserLoginConstraint(account, account.getUuid());

        PutItemRequest accountPut = buildPutWriteItemForAccount(account, account.getUuid(), PutItemRequest.builder()
            .conditionExpression("attribute_not_exists(#number) OR #number = :number")
            .expressionAttributeNames(Map.of("#number", ATTR_ACCOUNT_USER_LOGIN))
            .expressionAttributeValues(Map.of(":number", AttributeValues.fromString(account.getUserLogin()))));

        PutItemRequest miscPut = buildPutWriteItemForMisc(directoryVersion);

        try {
          client.putItem(accountPut);
        } catch (ConditionalCheckFailedException e) {

          throw new IllegalArgumentException("uuid present with different user login");

        }

        try {
          client.putItem(userLoginConstraintPut);
        } catch (ConditionalCheckFailedException e) {

          // if the user login is found with an uuid that differs that means that the
          // account is not new (rather it's soft-deleted), and the new uuid is reset to
          // the old one. The fresh entry in the accounts table is removed in favour of
          // the already existing one.
          
          DeleteItemRequest accountDelete = DeleteItemRequest.builder()
              .tableName(accountsTableName)
              .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
              .build();

          client.deleteItem(accountDelete);          

          Optional<Account> exAcc = get(account.getUserLogin());
          UUID uuid = exAcc.get().getUuid();
          account.setUuid(uuid);

          final int version = exAcc.get().getVersion();
          account.setVersion(version);

          update(account);
          client.putItem(miscPut);

          return false;

        } catch (TransactionConflictException e) {
          // this should only happen if two clients manage to make concurrent create()
          // calls
          throw new ContestedOptimisticLockException();
        }

        client.putItem(miscPut);

      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      }

      return true;
    });
  }

  private PutItemRequest buildPutWriteItemForAccount(Account account, UUID uuid, PutItemRequest.Builder putBuilder) throws JsonProcessingException {
    return putBuilder
        .tableName(accountsTableName)
        .item(Map.of(
            KEY_ACCOUNT_UUID, AttributeValues.fromUUID(uuid),
            ATTR_ACCOUNT_USER_LOGIN, AttributeValues.fromString(account.getUserLogin()),
            ATTR_ACCOUNT_VD, AttributeValues.fromString("default"),
            ATTR_ACCOUNT_DATA, AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
            ATTR_VERSION, AttributeValues.fromInt(account.getVersion())))
        .build();
  }

  private PutItemRequest buildPutWriteItemForUserLoginConstraint(Account account, UUID uuid) {
    return PutItemRequest.builder()
        .tableName(userLoginsTableName)
        .item(Map.of(
            ATTR_ACCOUNT_USER_LOGIN, AttributeValues.fromString(account.getUserLogin()),
            KEY_ACCOUNT_UUID, AttributeValues.fromUUID(uuid)))
        .conditionExpression(
            "attribute_not_exists(#number) OR (attribute_exists(#number) AND #uuid = :uuid)")
        .expressionAttributeNames(
            Map.of("#uuid", KEY_ACCOUNT_UUID,
                "#number", ATTR_ACCOUNT_USER_LOGIN))
        .expressionAttributeValues(
            Map.of(":uuid", AttributeValues.fromUUID(uuid)))
        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
        .build();
  }

  private PutItemRequest buildPutWriteItemForMisc(long directoryVersion) {
    return PutItemRequest.builder()
        .tableName(miscTableName)
        .item(Map.of(
            KEY_PARAMETER_NAME, AttributeValues.fromString(DIRECTORY_VERSION_PARAMETER_NAME),
            ATTR_PARAMETER_VALUE, AttributeValues.fromString(String.valueOf(directoryVersion))))
        .build();
  }

  // TODO: VD change
  public void update(Account account) throws ContestedOptimisticLockException {
    UPDATE_TIMER.record(() -> {
      UpdateItemRequest updateItemRequest;
      try {
        updateItemRequest = UpdateItemRequest.builder()
            .tableName(accountsTableName)
            .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(account.getUuid())))
            .updateExpression("SET #data = :data ADD #version :version_increment")
            .conditionExpression("attribute_exists(#number) AND #version = :version")
            .expressionAttributeNames(Map.of("#number", ATTR_ACCOUNT_USER_LOGIN,
                "#data", ATTR_ACCOUNT_DATA,
                "#version", ATTR_VERSION))
            .expressionAttributeValues(Map.of(
                ":data", AttributeValues.fromByteArray(SystemMapper.getMapper().writeValueAsBytes(account)),
                ":version", AttributeValues.fromInt(account.getVersion()),
                ":version_increment", AttributeValues.fromInt(1)))
            .returnValues(ReturnValue.UPDATED_NEW)
            .build();

      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      }

      try {
        UpdateItemResponse response = client.updateItem(updateItemRequest);

        account.setVersion(AttributeValues.getInt(response.attributes(), "V", account.getVersion() + 1));
      } catch (final TransactionConflictException e) {

        throw new ContestedOptimisticLockException();

      } catch (final ConditionalCheckFailedException e) {

        // the exception doesn’t give details about which condition failed,
        // but we can infer it was an optimistic locking failure if the UUID is known
        throw get(account.getUuid()).isPresent() ? new ContestedOptimisticLockException() : e;
      }
    });
  }

  public Optional<Account> get(String userLogin) {

    return GET_BY_USER_LOGIN_TIMER.record(() -> {

      final GetItemResponse response = client.getItem(GetItemRequest.builder()
          .tableName(userLoginsTableName)
          .key(Map.of(ATTR_ACCOUNT_USER_LOGIN, AttributeValues.fromString(userLogin)))
          .build());

      return Optional.ofNullable(response.item())
          .map(item -> item.get(KEY_ACCOUNT_UUID))
          .map(uuid -> accountByUuid(uuid))
          .map(Accounts::fromItem);
    });
  }

  private Map<String, AttributeValue> accountByUuid(AttributeValue uuid) {
    GetItemResponse r = client.getItem(GetItemRequest.builder()
        .tableName(accountsTableName)
        .key(Map.of(KEY_ACCOUNT_UUID, uuid))
        .consistentRead(true)
        .build());
    return r.item().isEmpty() ? null : r.item();
  }

  public Optional<Account> get(UUID uuid) {
    return GET_BY_UUID_TIMER.record(() -> Optional.ofNullable(accountByUuid(AttributeValues.fromUUID(uuid)))
        .map(Accounts::fromItem));
  }

  public AccountCrawlChunk getAllFrom(final UUID from, final int maxCount) {
    final ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
        .limit(scanPageSize)
        .exclusiveStartKey(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(from)));

    return scanForChunk(scanRequestBuilder, maxCount, GET_ALL_FROM_OFFSET_TIMER);
  }

  public AccountCrawlChunk getAllFromStart(final int maxCount) {
    final ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
        .limit(scanPageSize);

    return scanForChunk(scanRequestBuilder, maxCount, GET_ALL_FROM_START_TIMER);
  }

  private AccountCrawlChunk scanForChunk(final ScanRequest.Builder scanRequestBuilder, final int maxCount, final Timer timer) {

    scanRequestBuilder.tableName(accountsTableName);

    final List<Account> accounts = timer.record(() -> scan(scanRequestBuilder.build(), maxCount)
        .stream()
        .map(Accounts::fromItem)
        .collect(Collectors.toList()));

    return new AccountCrawlChunk(accounts, accounts.size() > 0 ? accounts.get(accounts.size() - 1).getUuid() : null);
  }

  public void delete(UUID uuid, long directoryVersion) {
    DELETE_TIMER.record(() -> {
      Optional<Account> maybeAccount = get(uuid);

      maybeAccount.ifPresent(account -> {

        DeleteItemRequest userLoginDelete = DeleteItemRequest.builder()
            .tableName(userLoginsTableName)
            .key(Map.of(ATTR_ACCOUNT_USER_LOGIN, AttributeValues.fromString(account.getUserLogin())))
            .build();

        DeleteItemRequest accountDelete = DeleteItemRequest.builder()
            .tableName(accountsTableName)
            .key(Map.of(KEY_ACCOUNT_UUID, AttributeValues.fromUUID(uuid)))
            .build();

        client.deleteItem(userLoginDelete);
        client.deleteItem(accountDelete);

        PutItemRequest miscPut = buildPutWriteItemForMisc(directoryVersion);
        client.putItem(miscPut);

      });
    });
  }

  // TODO: extract VD
  @VisibleForTesting
  static Account fromItem(Map<String, AttributeValue> item) {
    if (!item.containsKey(ATTR_ACCOUNT_DATA) ||
        !item.containsKey(ATTR_ACCOUNT_USER_LOGIN) ||
        !item.containsKey(ATTR_ACCOUNT_VD) ||
        !item.containsKey(KEY_ACCOUNT_UUID)) {
      throw new RuntimeException("item missing values");
    }
    try {
      Account account = SystemMapper.getMapper().readValue(item.get(ATTR_ACCOUNT_DATA).b().asByteArray(), Account.class);
      account.setUserLogin(item.get(ATTR_ACCOUNT_USER_LOGIN).s());
      // account.setVD(item.get(ATTR_ACCOUNT_VD).s());
      account.setUuid(UUIDUtil.fromByteBuffer(item.get(KEY_ACCOUNT_UUID).b().asByteBuffer()));
      account.setVersion(Integer.parseInt(item.get(ATTR_VERSION).n()));

      return account;

    } catch (IOException e) {
      throw new RuntimeException("Could not read stored account data", e);
    }
  }

  // for simplicity, this one gets all accounts in one pass for directories of
  // practical size
  protected List<Account> getAll(final ScanRequest.Builder scanRequestBuilder) {

    scanRequestBuilder.tableName(accountsTableName);

    return scan(scanRequestBuilder.build())
        .stream()
        .map(Accounts::fromItem)
        .collect(Collectors.toList());
  }

  protected Long retrieveDirectoryVersion() {

    final GetItemResponse response = client.getItem(GetItemRequest.builder()
        .tableName(miscTableName)
        .key(Map.of(KEY_PARAMETER_NAME, AttributeValues.fromString(DIRECTORY_VERSION_PARAMETER_NAME)))
        .build());

    return Long.parseLong(AttributeValues.getString(response.item(), ATTR_PARAMETER_VALUE, "0"));

  }

  protected void setDirectoryVersion(long version) {

    PutItemRequest miscPut = buildPutWriteItemForMisc(version);
    client.putItem(miscPut);
  }
}
