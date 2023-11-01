/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit5.EmbeddedPostgresExtension;
import com.opentable.db.postgres.junit5.PreparedDbExtension;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicAccountsScyllaDbMigrationConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.util.JsonHelpers;
import su.sres.shadowserver.util.RedisClusterHelper;
import su.sres.shadowserver.util.Pair;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

class AccountsManagerConcurrentModificationIntegrationTest {

  @RegisterExtension
  static PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

  private static final String ACCOUNTS_TABLE_NAME = "accounts_test";
  private static final String NUMBERS_TABLE_NAME = "numbers_test";
  private static final String MISC_TABLE_NAME = "misc_test";
  static final String KEY_PARAMETER_NAME = "PN";
  static final String ATTR_PARAMETER_VALUE = "PV";

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = DynamoDbExtension.builder()
      .tableName(ACCOUNTS_TABLE_NAME)
      .hashKey(AccountsScyllaDb.KEY_ACCOUNT_UUID)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(AccountsScyllaDb.KEY_ACCOUNT_UUID)
          .attributeType(ScalarAttributeType.B)
          .build())
      .build();

  private Accounts accounts;

  private AccountsScyllaDb accountsDynamoDb;

  private AccountsManager accountsManager;

  private RedisAdvancedClusterCommands<String, String> commands;

  private Executor mutationExecutor = new ThreadPoolExecutor(20, 20, 5, TimeUnit.SECONDS, new LinkedBlockingDeque<>(20));
  
  private DirectoryManager directoryManager = new DirectoryManager(mock(ReplicatedJedisPool.class));

  @BeforeEach
  void setup() {

    {
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
      
      List<KeySchemaElement> keySchemaMisc = new ArrayList<KeySchemaElement>();
      keySchemaMisc.add(KeySchemaElement.builder().attributeName(KEY_PARAMETER_NAME).keyType(KeyType.HASH).build());
      
      List<AttributeDefinition> attributeDefinitionsMisc = new ArrayList<AttributeDefinition>();
      attributeDefinitionsMisc.add(AttributeDefinition.builder().attributeName(KEY_PARAMETER_NAME).attributeType("S").build());
            
      CreateTableRequest requestMisc = CreateTableRequest.builder()
          .tableName(MISC_TABLE_NAME)
          .keySchema(keySchemaMisc)
          .attributeDefinitions(attributeDefinitionsMisc)
          .billingMode("PAY_PER_REQUEST")
          .build();

      dynamoDbExtension.getDynamoDbClient().createTable(requestMisc);
    }

    accountsDynamoDb = new AccountsScyllaDb(
        dynamoDbExtension.getDynamoDbClient(),
        dynamoDbExtension.getDynamoDbAsyncClient(),
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingDeque<>()),
        dynamoDbExtension.getTableName(),
        NUMBERS_TABLE_NAME,
        MISC_TABLE_NAME,
        mock(MigrationDeletedAccounts.class),
        mock(MigrationRetryAccounts.class));

    {
      final CircuitBreakerConfiguration circuitBreakerConfiguration = new CircuitBreakerConfiguration();
      circuitBreakerConfiguration.setIgnoredExceptions(List.of("su.sres.shadowserver.storage.ContestedOptimisticLockException"));
      FaultTolerantDatabase faultTolerantDatabase = new FaultTolerantDatabase("accountsTest",
          Jdbi.create(db.getTestDatabase()),
          circuitBreakerConfiguration);

      accounts = new Accounts(faultTolerantDatabase);
    }

    {
      
      DynamicConfiguration dynamicConfiguration = new DynamicConfiguration();
            
      final DynamicAccountsScyllaDbMigrationConfiguration config = dynamicConfiguration
          .getAccountsScyllaDbMigrationConfiguration();

      config.setDeleteEnabled(true);
      config.setReadEnabled(true);
      config.setWriteEnabled(true);    
            
      commands = mock(RedisAdvancedClusterCommands.class);      
      MessagesManager messagesManager = mock(MessagesManager.class);
      
      accountsManager = new AccountsManager(
          accounts,
          accountsDynamoDb,
          directoryManager,
          RedisClusterHelper.buildMockRedisCluster(commands),
          mock(DeletedAccounts.class),          
          mock(KeysScyllaDb.class),
          messagesManager,
          mock(MigrationMismatchedAccounts.class),
          mock(UsernamesManager.class),
          mock(ProfilesManager.class),
          mock(StoredVerificationCodeManager.class));
    }
  }

  @Test
  void testConcurrentUpdate() throws IOException {
   
    Jedis jedis = mock(Jedis.class);    
    when(directoryManager.accessDirectoryCache().getWriteResource()).thenReturn(jedis);

    final UUID uuid;
    {
      final Account account = accountsManager.update(
          accountsManager.create("+14155551212", "password", null, new AccountAttributes()),
          a -> {
            a.setUnidentifiedAccessKey(new byte[16]);

            final Random random = new Random();
            final SignedPreKey signedPreKey = new SignedPreKey(random.nextInt(), "testPublicKey-" + random.nextInt(),
                "testSignature-" + random.nextInt());

            a.removeDevice(1);
            a.addDevice(new Device(1, "testName-" + random.nextInt(), "testAuthToken-" + random.nextInt(),
                "testSalt-" + random.nextInt(),
                "testGcmId-" + random.nextInt(), "testApnId-" + random.nextInt(), "testVoipApnId-" + random.nextInt(),
                random.nextBoolean(), random.nextInt(), signedPreKey, random.nextInt(), random.nextInt(),
                "testUserAgent-" + random.nextInt(), 0,
                new Device.DeviceCapabilities(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean(), random.nextBoolean(), random.nextBoolean())));
          });

      uuid = account.getUuid();
    }

    final String profileName = "name";
    final String avatar = "avatar";
    final boolean discoverableByPhoneNumber = false;
    final String currentProfileVersion = "cpv";
    final String identityKey = "ikey";
    final byte[] unidentifiedAccessKey = new byte[]{1};
    final String pin = "1234";
    final String registrationLock = "reglock";
    final AuthenticationCredentials credentials = new AuthenticationCredentials(registrationLock);
    final boolean unrestrictedUnidentifiedAccess = true;
    final long lastSeen = Instant.now().getEpochSecond();

    CompletableFuture.allOf(
        modifyAccount(uuid, account -> account.setProfileName(profileName)),
        modifyAccount(uuid, account -> account.setAvatar(avatar)),
        modifyAccount(uuid, account -> account.setDiscoverableByUserLogin(discoverableByPhoneNumber)),
        modifyAccount(uuid, account -> account.setCurrentProfileVersion(currentProfileVersion)),
        modifyAccount(uuid, account -> account.setIdentityKey(identityKey)),
        modifyAccount(uuid, account -> account.setUnidentifiedAccessKey(unidentifiedAccessKey)),       
        modifyAccount(uuid, account -> account.setUnrestrictedUnidentifiedAccess(unrestrictedUnidentifiedAccess)),
        modifyDevice(uuid, Device.MASTER_ID, device-> device.setLastSeen(lastSeen)),
        modifyDevice(uuid, Device.MASTER_ID, device-> device.setName("deviceName"))
    ).join();

    final Account managerAccount = accountsManager.get(uuid).get();
    final Account dbAccount = accounts.get(uuid).get();
    final Account dynamoAccount = accountsDynamoDb.get(uuid).get();

    final Account redisAccount = getLastAccountFromRedisMock(commands);

    Stream.of(
        new Pair<>("manager", managerAccount),
        new Pair<>("db", dbAccount),
        new Pair<>("dynamo", dynamoAccount),
        new Pair<>("redis", redisAccount)
    ).forEach(pair ->
          verifyAccount(pair.first(), pair.second(), profileName, avatar, discoverableByPhoneNumber,
              currentProfileVersion, identityKey, unidentifiedAccessKey, pin, registrationLock,
              unrestrictedUnidentifiedAccess, lastSeen)
        );
  }

  private Account getLastAccountFromRedisMock(RedisAdvancedClusterCommands<String, String> commands) throws IOException {
    ArgumentCaptor<String> redisSetArgumentCapture = ArgumentCaptor.forClass(String.class);

    verify(commands, atLeast(20)).set(anyString(), redisSetArgumentCapture.capture());

    return JsonHelpers.fromJson(redisSetArgumentCapture.getValue(), Account.class);
  }

  private void verifyAccount(final String name, final Account account, final String profileName, final String avatar, final boolean discoverableByPhoneNumber, final String currentProfileVersion, final String identityKey, final byte[] unidentifiedAccessKey, final String pin, final String clientRegistrationLock, final boolean unrestrictedUnidentifiedAcces, final long lastSeen) {

    assertAll(name,
        () -> assertEquals(profileName, account.getProfileName()),
        () -> assertEquals(avatar, account.getAvatar()),
        () -> assertEquals(discoverableByPhoneNumber, account.isDiscoverableByUserLogin()),
        () -> assertEquals(currentProfileVersion, account.getCurrentProfileVersion().get()),
        () -> assertEquals(identityKey, account.getIdentityKey()),
        () -> assertArrayEquals(unidentifiedAccessKey, account.getUnidentifiedAccessKey().get()),        
        () -> assertEquals(unrestrictedUnidentifiedAcces, account.isUnrestrictedUnidentifiedAccess())
    );
  }

  private CompletableFuture<?> modifyAccount(final UUID uuid, final Consumer<Account> accountMutation) {

    return CompletableFuture.runAsync(() -> {
      final Account account = accountsManager.get(uuid).get();
      accountsManager.update(account, accountMutation);
    }, mutationExecutor);
  }

  private CompletableFuture<?> modifyDevice(final UUID uuid, final long deviceId, final Consumer<Device> deviceMutation) {

    return CompletableFuture.runAsync(() -> {
      final Account account = accountsManager.get(uuid).get();
      accountsManager.updateDevice(account, deviceId, deviceMutation);
    }, mutationExecutor);
  }
}
