package su.sres.shadowserver.workers;

import static su.sres.shadowserver.storage.DirectoryManager.DIRECTORY_VERSION;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import redis.clients.jedis.Jedis;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.AccountsScyllaDbConfiguration;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.util.AttributeValues;
import su.sres.shadowserver.util.ScyllaDbFromConfig;

public class CreateAccountsDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreateAccountsDbCommand.class);

  static final String KEY_ACCOUNT_UUID = "U";
  static final String ATTR_ACCOUNT_USER_LOGIN = "P";
  static final String ATTR_ACCOUNT_DATA = "D";
  static final String ATTR_VERSION = "V";
  static final String ATTR_ACCOUNT_VD = "VD";

  static final String KEY_PARAMETER_NAME = "PN";
  static final String ATTR_PARAMETER_VALUE = "PV";

  static final String DIRECTORY_VERSION_PARAMETER_NAME = "directory_version";

  public CreateAccountsDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "createaccountsdb", "Creates the accountsdb tables");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    AccountsScyllaDbConfiguration scyllaAccountsConfig = config.getAccountsScyllaDbConfiguration();

    ScyllaDbConfiguration scyllaMigrationDeletedAccountsConfig = config.getMigrationDeletedAccountsScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaMigrationRetryAccountsConfig = config.getMigrationRetryAccountsScyllaDbConfiguration();

    String accTableName = scyllaAccountsConfig.getTableName();
    String userLoginTableName = scyllaAccountsConfig.getUserLoginTableName();
    String miscTableName = scyllaAccountsConfig.getMiscTableName();
    String migDelTableName = scyllaMigrationDeletedAccountsConfig.getTableName();
    String migRetrTableName = scyllaMigrationRetryAccountsConfig.getTableName();

    DynamoDbClient accountsScyllaDb = ScyllaDbFromConfig.client(scyllaAccountsConfig);

    List<AttributeDefinition> attributeDefinitionsAccount = new ArrayList<AttributeDefinition>();
    attributeDefinitionsAccount.add(AttributeDefinition.builder().attributeName(KEY_ACCOUNT_UUID).attributeType("B").build());
    attributeDefinitionsAccount.add(AttributeDefinition.builder().attributeName(ATTR_ACCOUNT_USER_LOGIN).attributeType("S").build());
    attributeDefinitionsAccount.add(AttributeDefinition.builder().attributeName(ATTR_ACCOUNT_VD).attributeType("S").build());
    attributeDefinitionsAccount.add(AttributeDefinition.builder().attributeName(ATTR_ACCOUNT_DATA).attributeType("B").build());
    attributeDefinitionsAccount.add(AttributeDefinition.builder().attributeName(ATTR_VERSION).attributeType("N").build());

    List<AttributeDefinition> attributeDefinitionsUserLogin = new ArrayList<AttributeDefinition>();
    attributeDefinitionsUserLogin.add(AttributeDefinition.builder().attributeName(KEY_ACCOUNT_UUID).attributeType("B").build());
    attributeDefinitionsUserLogin.add(AttributeDefinition.builder().attributeName(ATTR_ACCOUNT_USER_LOGIN).attributeType("S").build());

    List<AttributeDefinition> attributeDefinitionsMisc = new ArrayList<AttributeDefinition>();
    attributeDefinitionsMisc.add(AttributeDefinition.builder().attributeName(KEY_PARAMETER_NAME).attributeType("S").build());
    attributeDefinitionsMisc.add(AttributeDefinition.builder().attributeName(ATTR_PARAMETER_VALUE).attributeType("S").build());

    List<AttributeDefinition> attributeDefinitionsMigration = new ArrayList<AttributeDefinition>();
    attributeDefinitionsMigration.add(AttributeDefinition.builder().attributeName(KEY_ACCOUNT_UUID).attributeType("B").build());

    List<KeySchemaElement> keySchemaAccount = new ArrayList<KeySchemaElement>();
    keySchemaAccount.add(KeySchemaElement.builder().attributeName(KEY_ACCOUNT_UUID).keyType(KeyType.HASH).build());

    List<KeySchemaElement> keySchemaUserLogin = new ArrayList<KeySchemaElement>();
    keySchemaUserLogin.add(KeySchemaElement.builder().attributeName(ATTR_ACCOUNT_USER_LOGIN).keyType(KeyType.HASH).build());

    List<KeySchemaElement> keySchemaMisc = new ArrayList<KeySchemaElement>();
    keySchemaMisc.add(KeySchemaElement.builder().attributeName(KEY_PARAMETER_NAME).keyType(KeyType.HASH).build());

    CreateTableRequest requestAccount = CreateTableRequest.builder()
        .tableName(accTableName)
        .keySchema(keySchemaAccount)
        .attributeDefinitions(attributeDefinitionsAccount)
        .billingMode("PAY_PER_REQUEST")
        .build();

    CreateTableRequest requestUserLogin = CreateTableRequest.builder()
        .tableName(userLoginTableName)
        .keySchema(keySchemaUserLogin)
        .attributeDefinitions(attributeDefinitionsUserLogin)
        .billingMode("PAY_PER_REQUEST")
        .build();

    CreateTableRequest requestMisc = CreateTableRequest.builder()
        .tableName(miscTableName)
        .keySchema(keySchemaMisc)
        .attributeDefinitions(attributeDefinitionsMisc)
        .billingMode("PAY_PER_REQUEST")
        .build();

    CreateTableRequest requestMigrationDeleted = CreateTableRequest.builder()
        .tableName(migDelTableName)
        .keySchema(keySchemaAccount)
        .attributeDefinitions(attributeDefinitionsMigration)
        .billingMode("PAY_PER_REQUEST")
        .build();

    CreateTableRequest requestMigrationRetry = CreateTableRequest.builder()
        .tableName(migRetrTableName)
        .keySchema(keySchemaAccount)
        .attributeDefinitions(attributeDefinitionsMigration)
        .billingMode("PAY_PER_REQUEST")
        .build();

    DynamoDbWaiter waiter = accountsScyllaDb.waiter();

    logger.info("Creating the accounts table...");
    accountsScyllaDb.createTable(requestAccount);

    WaiterResponse<DescribeTableResponse> waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(accTableName));

    log(waiterResponse);

    logger.info("Creating the user logins table...");
    accountsScyllaDb.createTable(requestUserLogin);

    waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(userLoginTableName));

    log(waiterResponse);

    logger.info("Creating the miscellaneous table...");
    accountsScyllaDb.createTable(requestMisc);

    waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(miscTableName));

    log(waiterResponse);

    logger.info("Filling the directory version...");

    ReplicatedJedisPool redisClient = new RedisClientFactory("create_accounts_db_command", config.getDirectoryConfiguration().getUrl(), config.getDirectoryConfiguration().getReplicaUrls(), config.getDirectoryConfiguration().getCircuitBreakerConfiguration()).getRedisClientPool();
    DirectoryManager directory = new DirectoryManager(redisClient);

    Jedis jedis = directory.accessDirectoryCache().getWriteResource();
    long currentVersion = Long.parseLong(jedis.get(DIRECTORY_VERSION));
    jedis.close();  
    
    PutItemRequest req =  PutItemRequest.builder()
        .tableName(miscTableName)
        .item(Map.of(
                    KEY_PARAMETER_NAME, AttributeValues.fromString(DIRECTORY_VERSION_PARAMETER_NAME),
                    ATTR_PARAMETER_VALUE, AttributeValues.fromString(String.valueOf(currentVersion))))
        .build();
    
    accountsScyllaDb.putItem(req);    

    logger.info("...Done");

    logger.info("Creating the migration deleted table...");
    accountsScyllaDb.createTable(requestMigrationDeleted);

    waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(migDelTableName));

    log(waiterResponse);

    logger.info("Creating the migration deleted table...");
    accountsScyllaDb.createTable(requestMigrationRetry);

    waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(migRetrTableName));

    log(waiterResponse);

  }

  private void log(WaiterResponse<DescribeTableResponse> resp) {

    if (resp.matched().response().isPresent()) {
      logger.info("Done");
    }

  }
}
