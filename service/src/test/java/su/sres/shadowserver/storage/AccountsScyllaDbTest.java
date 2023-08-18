/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jdbi.v3.core.transaction.TransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.util.AttributeValues;
import su.sres.shadowserver.util.UUIDUtil;

class AccountsScyllaDbTest {

  private static final String ACCOUNTS_TABLE_NAME = "accounts_test";
  private static final String NUMBERS_TABLE_NAME = "numbers_test";
  private static final String MISC_TABLE_NAME = "misc_test";
  
  private static final String MIGRATION_DELETED_ACCOUNTS_TABLE_NAME = "migration_deleted_accounts_test";
  private static final String MIGRATION_RETRY_ACCOUNTS_TABLE_NAME = "miration_retry_accounts_test";

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = DynamoDbExtension.builder()
      .tableName(ACCOUNTS_TABLE_NAME)
      .hashKey(AccountsScyllaDb.KEY_ACCOUNT_UUID)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(AccountsScyllaDb.KEY_ACCOUNT_UUID)
          .attributeType(ScalarAttributeType.B)
          .build())
      .build();

  private AccountsScyllaDb accountsScyllaDb;
    
  @BeforeEach
  void setupAccountsDao() {

    CreateTableRequest createNumbersTableRequest = CreateTableRequest.builder()
        .tableName(NUMBERS_TABLE_NAME)
        .keySchema(KeySchemaElement.builder()
            .attributeName(AccountsScyllaDb.ATTR_ACCOUNT_USER_LOGIN)
            .keyType(KeyType.HASH)
            .build())
        .attributeDefinitions(AttributeDefinition.builder()
            .attributeName(AccountsScyllaDb.ATTR_ACCOUNT_USER_LOGIN)
            .attributeType(ScalarAttributeType.S)
            .build())
        .provisionedThroughput(DynamoDbExtension.DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    dynamoDbExtension.getDynamoDbClient().createTable(createNumbersTableRequest);

    final CreateTableRequest createMigrationDeletedAccountsTableRequest = CreateTableRequest.builder()
        .tableName(MIGRATION_DELETED_ACCOUNTS_TABLE_NAME)
        .keySchema(KeySchemaElement.builder()
            .attributeName(MigrationDeletedAccounts.KEY_UUID)
            .keyType(KeyType.HASH)
            .build())
        .attributeDefinitions(AttributeDefinition.builder()
            .attributeName(MigrationDeletedAccounts.KEY_UUID)
            .attributeType(ScalarAttributeType.B)
            .build())
        .provisionedThroughput(DynamoDbExtension.DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    dynamoDbExtension.getDynamoDbClient().createTable(createMigrationDeletedAccountsTableRequest);

    MigrationDeletedAccounts migrationDeletedAccounts = new MigrationDeletedAccounts(
        dynamoDbExtension.getDynamoDbClient(), MIGRATION_DELETED_ACCOUNTS_TABLE_NAME);

    final CreateTableRequest createMigrationRetryAccountsTableRequest = CreateTableRequest.builder()
        .tableName(MIGRATION_RETRY_ACCOUNTS_TABLE_NAME)
        .keySchema(KeySchemaElement.builder()
            .attributeName(MigrationRetryAccounts.KEY_UUID)
            .keyType(KeyType.HASH)
            .build())
        .attributeDefinitions(AttributeDefinition.builder()
            .attributeName(MigrationRetryAccounts.KEY_UUID)
            .attributeType(ScalarAttributeType.B)
            .build())
        .provisionedThroughput(DynamoDbExtension.DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    dynamoDbExtension.getDynamoDbClient().createTable(createMigrationRetryAccountsTableRequest);

    MigrationRetryAccounts migrationRetryAccounts = new MigrationRetryAccounts((dynamoDbExtension.getDynamoDbClient()),
        MIGRATION_RETRY_ACCOUNTS_TABLE_NAME);

    this.accountsScyllaDb = new AccountsScyllaDb(
        dynamoDbExtension.getDynamoDbClient(),
        dynamoDbExtension.getDynamoDbAsyncClient(),
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingDeque<>()),
        dynamoDbExtension.getTableName(),
        NUMBERS_TABLE_NAME,
        MISC_TABLE_NAME,
        migrationDeletedAccounts,
        migrationRetryAccounts);
  }

  @Test
  void testStore() {
    Device  device  = generateDevice (1                                            );
    Account account = generateAccount("+14151112222", UUID.randomUUID(), Collections.singleton(device));
    long directoryVersion = 10L;

    boolean freshUser = accountsScyllaDb.create(account, directoryVersion);

    assertThat(freshUser).isTrue();
    verifyStoredState("+14151112222", account.getUuid(), account);
    
    freshUser = accountsScyllaDb.create(account, directoryVersion + 1L);
    assertThat(freshUser).isTrue();

    verifyStoredState("+14151112222", account.getUuid(), account);  
    
  }

  @Test
  void testStoreMulti() {
    Set<Device> devices = new HashSet<>();
    devices.add(generateDevice(1));
    devices.add(generateDevice(2));

    Account account = generateAccount("+14151112222", UUID.randomUUID(), devices);
    long directoryVersion = 10L;

    accountsScyllaDb.create(account, directoryVersion);

    verifyStoredState("+14151112222", account.getUuid(), account);
  }

  @Test
  void testRetrieve() {
    Set<Device> devicesFirst = new HashSet<>();
    devicesFirst.add(generateDevice(1));
    devicesFirst.add(generateDevice(2));

    UUID uuidFirst = UUID.randomUUID();
    Account accountFirst = generateAccount("+14151112222", uuidFirst, devicesFirst);
    long directoryVersion = 10L;

    Set<Device> devicesSecond = new HashSet<>();
    devicesSecond.add(generateDevice(1));
    devicesSecond.add(generateDevice(2));

    UUID uuidSecond = UUID.randomUUID();
    Account accountSecond = generateAccount("+14152221111", uuidSecond, devicesSecond);

    accountsScyllaDb.create(accountFirst, directoryVersion);
    accountsScyllaDb.create(accountSecond, directoryVersion + 1);

    Optional<Account> retrievedFirst = accountsScyllaDb.get("+14151112222");
    Optional<Account> retrievedSecond = accountsScyllaDb.get("+14152221111");

    assertThat(retrievedFirst.isPresent()).isTrue();
    assertThat(retrievedSecond.isPresent()).isTrue();

    verifyStoredState("+14151112222", uuidFirst, retrievedFirst.get(), accountFirst);
    verifyStoredState("+14152221111", uuidSecond, retrievedSecond.get(), accountSecond);

    retrievedFirst = accountsScyllaDb.get(uuidFirst);
    retrievedSecond = accountsScyllaDb.get(uuidSecond);

    assertThat(retrievedFirst.isPresent()).isTrue();
    assertThat(retrievedSecond.isPresent()).isTrue();

    verifyStoredState("+14151112222", uuidFirst, retrievedFirst.get(), accountFirst);
    verifyStoredState("+14152221111", uuidSecond, retrievedSecond.get(), accountSecond);
  }

  @Test
  void testOverwrite() {
    Device  device  = generateDevice (1                                            );
    UUID    firstUuid = UUID.randomUUID();
    Account account   = generateAccount("+14151112222", firstUuid, Collections.singleton(device));
    long directoryVersion = 10L;

    final boolean freshUser = accountsScyllaDb.create(account, directoryVersion);
    assertThat(freshUser).isFalse();

    verifyStoredState("+14151112222", account.getUuid(), account);

    UUID secondUuid = UUID.randomUUID();

    device = generateDevice(1);
    account = generateAccount("+14151112222", secondUuid, Collections.singleton(device));

    accountsScyllaDb.create(account, directoryVersion + 1);
    verifyStoredState("+14151112222", firstUuid, account);

    device = generateDevice(1);
    Account invalidAccount = generateAccount("+14151113333", firstUuid, Collections.singleton(device));

    assertThatThrownBy(() -> accountsScyllaDb.create(invalidAccount, directoryVersion + 2));
  }

  @Test
  void testUpdate() {
    Device  device  = generateDevice (1                                            );
    Account account = generateAccount("+14151112222", UUID.randomUUID(), Collections.singleton(device));
    long directoryVersion = 10L;    

    accountsScyllaDb.create(account, directoryVersion);

    device.setName("foobar");

    accountsScyllaDb.update(account);

    Optional<Account> retrieved = accountsScyllaDb.get("+14151112222");

    assertThat(retrieved.isPresent()).isTrue();
    verifyStoredState("+14151112222", account.getUuid(), retrieved.get(), account);

    retrieved = accountsScyllaDb.get(account.getUuid());

    assertThat(retrieved.isPresent()).isTrue();
    verifyStoredState("+14151112222", account.getUuid(), account);
    
    device = generateDevice(1);
    Account unknownAccount = generateAccount("+14151113333", UUID.randomUUID(), Collections.singleton(device));

    assertThatThrownBy(() -> accountsScyllaDb.update(unknownAccount)).isInstanceOfAny(ConditionalCheckFailedException.class);
    
    account.setScyllaDbMigrationVersion(5);

    accountsScyllaDb.update(account);

    verifyStoredState("+14151112222", account.getUuid(), account);
  }

  @Test
  void testDelete() {
    final Device  deletedDevice   = generateDevice (1);
    final Account deletedAccount  = generateAccount("+14151112222", UUID.randomUUID(), Collections.singleton(deletedDevice));
    final Device  retainedDevice  = generateDevice (1);
    final Account retainedAccount = generateAccount("+14151112345", UUID.randomUUID(), Collections.singleton(retainedDevice));
    long directoryVersion = 10L;

    accountsScyllaDb.create(deletedAccount, directoryVersion);
    accountsScyllaDb.create(retainedAccount, directoryVersion + 1);

    assertThat(accountsScyllaDb.get(deletedAccount.getUuid())).isPresent();
    assertThat(accountsScyllaDb.get(retainedAccount.getUuid())).isPresent();

    accountsScyllaDb.delete(deletedAccount.getUuid(), directoryVersion + 2);

    assertThat(accountsScyllaDb.get(deletedAccount.getUuid())).isNotPresent();

    verifyStoredState(retainedAccount.getUserLogin(), retainedAccount.getUuid(), accountsScyllaDb.get(retainedAccount.getUuid()).get(), retainedAccount);

    {
      final Account recreatedAccount = generateAccount(deletedAccount.getUserLogin(), UUID.randomUUID(),
          Collections.singleton(generateDevice(1)));

      final boolean freshUser = accountsScyllaDb.create(recreatedAccount, directoryVersion + 3L);

      assertThat(freshUser).isTrue();

      assertThat(accountsScyllaDb.get(recreatedAccount.getUuid())).isPresent();
      verifyStoredState(recreatedAccount.getUserLogin(), recreatedAccount.getUuid(),
          accountsScyllaDb.get(recreatedAccount.getUuid()).get(), recreatedAccount);
    }
    
    verifyRecentlyDeletedAccountsTableItemCount(1);

    Map<String, AttributeValue> primaryKey = MigrationDeletedAccounts.primaryKey(deletedAccount.getUuid());
    assertThat(dynamoDbExtension.getDynamoDbClient().getItem(GetItemRequest.builder()
        .tableName(MIGRATION_DELETED_ACCOUNTS_TABLE_NAME)
        .key(Map.of(MigrationDeletedAccounts.KEY_UUID, primaryKey.get(MigrationDeletedAccounts.KEY_UUID)))
        .build()))
        .isNotNull();

    accountsScyllaDb.deleteRecentlyDeletedUuids();

    verifyRecentlyDeletedAccountsTableItemCount(0);
  }

  private void verifyRecentlyDeletedAccountsTableItemCount(int expectedItemCount) {
    int totalItems = 0;

    for (ScanResponse page : dynamoDbExtension.getDynamoDbClient().scanPaginator(ScanRequest.builder()
        .tableName(MIGRATION_DELETED_ACCOUNTS_TABLE_NAME)
        .build())) {
      for (Map<String, AttributeValue> item : page.items()) {
        totalItems++;
      }
    }

    assertThat(totalItems).isEqualTo(expectedItemCount);
  }

  @Test
  void testMissing() {
    Device  device  = generateDevice (1                                            );
    Account account = generateAccount("+14151112222", UUID.randomUUID(), Collections.singleton(device));
    long directoryVersion = 10L;

    accountsScyllaDb.create(account, directoryVersion);

    Optional<Account> retrieved = accountsScyllaDb.get("+11111111");
    assertThat(retrieved.isPresent()).isFalse();

    retrieved = accountsScyllaDb.get(UUID.randomUUID());
    assertThat(retrieved.isPresent()).isFalse();
  }

  @Test
  @Disabled("Need fault tolerant Scylladb")
  void testBreaker() throws InterruptedException {

    CircuitBreakerConfiguration configuration = new CircuitBreakerConfiguration();
    configuration.setWaitDurationInOpenStateInSeconds(1);
    configuration.setRingBufferSizeInHalfOpenState(1);
    configuration.setRingBufferSizeInClosedState(2);
    configuration.setFailureRateThreshold(50);

    final DynamoDbClient client = mock(DynamoDbClient.class);

    when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(RuntimeException.class);

    when(client.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(RuntimeException.class);

    AccountsScyllaDb accounts = new AccountsScyllaDb(client, mock(DynamoDbAsyncClient.class), mock(ThreadPoolExecutor.class), ACCOUNTS_TABLE_NAME, NUMBERS_TABLE_NAME, MISC_TABLE_NAME, mock(
        MigrationDeletedAccounts.class), mock(MigrationRetryAccounts.class));
    Account  account  = generateAccount("+14151112222", UUID.randomUUID());

    try {
      accounts.update(account);
      throw new AssertionError();
    } catch (TransactionException e) {
      // good
    }

    try {
      accounts.update(account);
      throw new AssertionError();
    } catch (TransactionException e) {
      // good
    }

    try {
      accounts.update(account);
      throw new AssertionError();
    } catch (CallNotPermittedException e) {
      // good
    }

    Thread.sleep(1100);

    try {
      accounts.update(account);
      throw new AssertionError();
    } catch (TransactionException e) {
      // good
    }
  }

  @Test
  void testMigrate() throws ExecutionException, InterruptedException {

    Device  device  = generateDevice (1                                            );
    UUID    firstUuid = UUID.randomUUID();
    Account account   = generateAccount("+14151112222", firstUuid, Collections.singleton(device));

    boolean migrated = accountsScyllaDb.migrate(account).get();

    assertThat(migrated).isTrue();

    verifyStoredState("+14151112222", account.getUuid(), account);

    migrated = accountsScyllaDb.migrate(account).get();

    assertThat(migrated).isFalse();

    verifyStoredState("+14151112222", account.getUuid(), account);

    UUID secondUuid = UUID.randomUUID();

    device = generateDevice(1);
    Account accountRemigrationWithDifferentUuid = generateAccount("+14151112222", secondUuid, Collections.singleton(device));

    migrated = accountsScyllaDb.migrate(accountRemigrationWithDifferentUuid).get();

    assertThat(migrated).isFalse();
    verifyStoredState("+14151112222", firstUuid, account);


    account.setScyllaDbMigrationVersion(account.getScyllaDbMigrationVersion() + 1);

    migrated = accountsScyllaDb.migrate(account).get();

    assertThat(migrated).isTrue();
  }

  private Device generateDevice(long id) {
    Random       random       = new Random(System.currentTimeMillis());
    SignedPreKey signedPreKey = new SignedPreKey(random.nextInt(), "testPublicKey-" + random.nextInt(), "testSignature-" + random.nextInt());
    return new Device(id, "testName-" + random.nextInt(), "testAuthToken-" + random.nextInt(), "testSalt-" + random.nextInt(),
        "testGcmId-" + random.nextInt(), "testApnId-" + random.nextInt(), "testVoipApnId-" + random.nextInt(), random.nextBoolean(), random.nextInt(), signedPreKey, random.nextInt(), random.nextInt(), "testUserAgent-" + random.nextInt() , 0, new Device.DeviceCapabilities(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
            random.nextBoolean(), random.nextBoolean()));
  }

  private Account generateAccount(String number, UUID uuid) {
    Device device = generateDevice(1);
    return generateAccount(number, uuid, Collections.singleton(device));
  }

  private Account generateAccount(String number, UUID uuid, Set<Device> devices) {
    byte[]       unidentifiedAccessKey = new byte[16];
    Random random = new Random(System.currentTimeMillis());
    Arrays.fill(unidentifiedAccessKey, (byte)random.nextInt(255));

    return new Account(number, uuid, devices, unidentifiedAccessKey);
  }

  private void verifyStoredState(String number, UUID uuid, Account expecting) {
    final DynamoDbClient db = dynamoDbExtension.getDynamoDbClient();

    final GetItemResponse get = db.getItem(GetItemRequest.builder()
        .tableName(dynamoDbExtension.getTableName())
        .key(Map.of(AccountsScyllaDb.KEY_ACCOUNT_UUID, AttributeValues.fromUUID(uuid)))
        .consistentRead(true)
        .build());

    if (get.hasItem()) {
      String data = new String(get.item().get(AccountsScyllaDb.ATTR_ACCOUNT_DATA).b().asByteArray(), StandardCharsets.UTF_8);
      assertThat(data).isNotEmpty();
      
      assertThat(AttributeValues.getInt(get.item(), AccountsScyllaDb.ATTR_MIGRATION_VERSION, -1))
      .isEqualTo(expecting.getScyllaDbMigrationVersion());

      Account result = AccountsScyllaDb.fromItem(get.item());
      verifyStoredState(number, uuid, result, expecting);
    } else {
      throw new AssertionError("No data");
    }
  }

  private void verifyStoredState(String number, UUID uuid, Account result, Account expecting) {
    assertThat(result.getUserLogin()).isEqualTo(number);
    assertThat(result.getLastSeen()).isEqualTo(expecting.getLastSeen());
    assertThat(result.getUuid()).isEqualTo(uuid);
    assertThat(Arrays.equals(result.getUnidentifiedAccessKey().get(), expecting.getUnidentifiedAccessKey().get())).isTrue();

    for (Device expectingDevice : expecting.getDevices()) {
      Device resultDevice = result.getDevice(expectingDevice.getId()).get();
      assertThat(resultDevice.getApnId()).isEqualTo(expectingDevice.getApnId());
      assertThat(resultDevice.getGcmId()).isEqualTo(expectingDevice.getGcmId());
      assertThat(resultDevice.getLastSeen()).isEqualTo(expectingDevice.getLastSeen());
      assertThat(resultDevice.getSignedPreKey().getPublicKey()).isEqualTo(expectingDevice.getSignedPreKey().getPublicKey());
      assertThat(resultDevice.getSignedPreKey().getKeyId()).isEqualTo(expectingDevice.getSignedPreKey().getKeyId());
      assertThat(resultDevice.getSignedPreKey().getSignature()).isEqualTo(expectingDevice.getSignedPreKey().getSignature());
      assertThat(resultDevice.getFetchesMessages()).isEqualTo(expectingDevice.getFetchesMessages());
      assertThat(resultDevice.getUserAgent()).isEqualTo(expectingDevice.getUserAgent());
      assertThat(resultDevice.getName()).isEqualTo(expectingDevice.getName());
      assertThat(resultDevice.getCreated()).isEqualTo(expectingDevice.getCreated());
    }
  }
}
