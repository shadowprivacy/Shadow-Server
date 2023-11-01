/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit5.EmbeddedPostgresExtension;
import com.opentable.db.postgres.junit5.PreparedDbExtension;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicAccountsScyllaDbMigrationConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.redis.RedisClusterExtension;
import su.sres.shadowserver.util.SynchronousExecutorService;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

class AccountsDynamoDbMigrationCrawlerIntegrationTest {

  private static final int CHUNK_SIZE = 20;
  private static final long CHUNK_INTERVAL_MS = 0;

  private static final String ACCOUNTS_TABLE_NAME = "accounts_test";
  private static final String KEYS_TABLE_NAME = "keys_test";
  private static final String MIGRATION_DELETED_ACCOUNTS_TABLE_NAME = "migration_deleted_accounts_test";
  private static final String MIGRATION_RETRY_ACCOUNTS_TABLE_NAME = "migration_retry_accounts_test";
  private static final String NUMBERS_TABLE_NAME = "numbers_test";
  private static final String MISC_TABLE_NAME = "misc_test";
  private static final String VERIFICATION_CODE_TABLE_NAME = "verification_code_test";

  @RegisterExtension
  static final RedisClusterExtension REDIS_CLUSTER_EXTENSION = RedisClusterExtension.builder().build();

  @RegisterExtension
  static final DynamoDbExtension KEYS_DYNAMODB_EXTENSION = DynamoDbExtension.builder()
      .tableName(KEYS_TABLE_NAME)
      .hashKey("U")
      .rangeKey("DK")
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName("U")
          .attributeType(ScalarAttributeType.B)
          .build())
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName("DK")
          .attributeType(ScalarAttributeType.B)
          .build())
      .build();

  @RegisterExtension
  static final DynamoDbExtension VERIFICATION_CODE_DYNAMODB_EXTENSION = DynamoDbExtension.builder()
      .tableName(VERIFICATION_CODE_TABLE_NAME)
      .hashKey("P")
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName("P")
          .attributeType(ScalarAttributeType.S)
          .build())
      .build();

  @RegisterExtension
  static PreparedDbExtension db = EmbeddedPostgresExtension
      .preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

  @RegisterExtension
  static DynamoDbExtension ACCOUNTS_DYNAMODB_EXTENSION = DynamoDbExtension.builder()
      .tableName(ACCOUNTS_TABLE_NAME)
      .hashKey("U")
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName("U")
          .attributeType(ScalarAttributeType.B)
          .build())
      .build();

  private static final String NEEDS_RECONCILIATION_INDEX_NAME = "needs_reconciliation_test";

  @RegisterExtension
  static final DynamoDbExtension DELETED_ACCOUNTS_DYNAMODB_EXTENSION = DynamoDbExtension.builder()
      .tableName("deleted_accounts_test")
      .hashKey(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN)
          .attributeType(ScalarAttributeType.S).build())      
      .globalSecondaryIndex(GlobalSecondaryIndex.builder()
          .indexName(NEEDS_RECONCILIATION_INDEX_NAME)
          .keySchema(
              KeySchemaElement.builder().attributeName(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN).keyType(KeyType.HASH).build())
          .projection(Projection.builder().projectionType(ProjectionType.INCLUDE)
              .nonKeyAttributes(DeletedAccounts.ATTR_ACCOUNT_UUID).build())
          .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(10L).build())
          .build())
      .build();

  @RegisterExtension
  static DynamoDbExtension DELETED_ACCOUNTS_LOCK_DYNAMODB_EXTENSION = DynamoDbExtension.builder()
      .tableName("deleted_accounts_lock_test")
      .hashKey(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(DeletedAccounts.KEY_ACCOUNT_USER_LOGIN)
          .attributeType(ScalarAttributeType.S).build())
      .build();

  private DynamicAccountsScyllaDbMigrationConfiguration accountMigrationConfiguration;

  private AccountsManager accountsManager;
  private AccountDatabaseCrawler accountDatabaseCrawler;
  private Accounts accounts;
  private AccountsScyllaDb accountsDynamoDb;

  @BeforeEach
  void setUp() throws Exception {

    createAdditionalDynamoDbTables();

    final DeletedAccounts deletedAccounts = new DeletedAccounts(DELETED_ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient(),
        DELETED_ACCOUNTS_DYNAMODB_EXTENSION.getTableName());

    MigrationDeletedAccounts migrationDeletedAccounts = new MigrationDeletedAccounts(
        ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient(), MIGRATION_DELETED_ACCOUNTS_TABLE_NAME);

    MigrationRetryAccounts migrationRetryAccounts = new MigrationRetryAccounts(
        (ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient()),
        MIGRATION_RETRY_ACCOUNTS_TABLE_NAME);

    accountsDynamoDb = new AccountsScyllaDb(
        ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient(),
        ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbAsyncClient(),
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingDeque<>()),
        ACCOUNTS_DYNAMODB_EXTENSION.getTableName(),
        NUMBERS_TABLE_NAME,
        MISC_TABLE_NAME,
        migrationDeletedAccounts,
        migrationRetryAccounts);

    final KeysScyllaDb keysDynamoDb = new KeysScyllaDb(KEYS_DYNAMODB_EXTENSION.getDynamoDbClient(), KEYS_TABLE_NAME);

    accounts = new Accounts(new FaultTolerantDatabase("accountsTest",
        Jdbi.create(db.getTestDatabase()),
        new CircuitBreakerConfiguration()));    

    final RateLimiters rateLimiters = mock(RateLimiters.class);
    when(rateLimiters.getVerifyLimiter()).thenReturn(mock(RateLimiter.class));
    
    final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);
    accountMigrationConfiguration = new DynamicAccountsScyllaDbMigrationConfiguration();
    accountMigrationConfiguration.setBackgroundMigrationEnabled(true);
    accountMigrationConfiguration.setLogMismatches(true);
  
    final DirectoryManager directory = mock(DirectoryManager.class);

    accountsManager = new AccountsManager(
        accounts,
        accountsDynamoDb,
        directory,
        REDIS_CLUSTER_EXTENSION.getRedisCluster(),
        deletedAccounts,        
        keysDynamoDb,
        mock(MessagesManager.class),
        mock(MigrationMismatchedAccounts.class),
        mock(UsernamesManager.class),
        mock(ProfilesManager.class),
        mock(StoredVerificationCodeManager.class));

    final AccountsScyllaDbMigrator dynamoDbMigrator = new AccountsScyllaDbMigrator(accountsDynamoDb);
    final PushFeedbackProcessor pushFeedbackProcessor = new PushFeedbackProcessor(accountsManager);

    final AccountDatabaseCrawlerCache crawlerCache = new AccountDatabaseCrawlerCache(
        REDIS_CLUSTER_EXTENSION.getRedisCluster());

    // Using a synchronous service doesn’t meaningfully impact the test
    final ExecutorService chunkPreReadExecutorService = new SynchronousExecutorService();
    accountDatabaseCrawler = new AccountDatabaseCrawler(accountsManager, crawlerCache, List.of(dynamoDbMigrator, pushFeedbackProcessor), CHUNK_SIZE,
        CHUNK_INTERVAL_MS, chunkPreReadExecutorService);
  }

  void createAdditionalDynamoDbTables() {
    CreateTableRequest createNumbersTableRequest = CreateTableRequest.builder()
        .tableName(NUMBERS_TABLE_NAME)
        .keySchema(KeySchemaElement.builder()
            .attributeName("P")
            .keyType(KeyType.HASH)
            .build())
        .attributeDefinitions(AttributeDefinition.builder()
            .attributeName("P")
            .attributeType(ScalarAttributeType.S)
            .build())
        .provisionedThroughput(DynamoDbExtension.DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient().createTable(createNumbersTableRequest);

    final CreateTableRequest createMigrationDeletedAccountsTableRequest = CreateTableRequest.builder()
        .tableName(MIGRATION_DELETED_ACCOUNTS_TABLE_NAME)
        .keySchema(KeySchemaElement.builder()
            .attributeName("U")
            .keyType(KeyType.HASH)
            .build())
        .attributeDefinitions(AttributeDefinition.builder()
            .attributeName("U")
            .attributeType(ScalarAttributeType.B)
            .build())
        .provisionedThroughput(DynamoDbExtension.DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient().createTable(createMigrationDeletedAccountsTableRequest);

    final CreateTableRequest createMigrationRetryAccountsTableRequest = CreateTableRequest.builder()
        .tableName(MIGRATION_RETRY_ACCOUNTS_TABLE_NAME)
        .keySchema(KeySchemaElement.builder()
            .attributeName("U")
            .keyType(KeyType.HASH)
            .build())
        .attributeDefinitions(AttributeDefinition.builder()
            .attributeName("U")
            .attributeType(ScalarAttributeType.B)
            .build())
        .provisionedThroughput(DynamoDbExtension.DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    ACCOUNTS_DYNAMODB_EXTENSION.getDynamoDbClient().createTable(createMigrationRetryAccountsTableRequest);
  } 
}
