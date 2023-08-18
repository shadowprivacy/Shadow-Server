package su.sres.shadowserver.workers;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.MessageScyllaDbConfiguration;
import su.sres.shadowserver.util.ScyllaDbFromConfig;

public class CreateMessageDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreateMessageDbCommand.class);

  private static final String KEY_PARTITION = "H";
  private static final String KEY_SORT = "S";
  private static final String LOCAL_INDEX_MESSAGE_UUID_NAME = "Message_UUID_Index";
  private static final String LOCAL_INDEX_MESSAGE_UUID_KEY_SORT = "U";

  private static final String KEY_TYPE = "T";
  private static final String KEY_RELAY = "R";
  private static final String KEY_TIMESTAMP = "TS";
  private static final String KEY_SOURCE = "SN";
  private static final String KEY_SOURCE_UUID = "SU";
  private static final String KEY_SOURCE_DEVICE = "SD";
  private static final String KEY_MESSAGE = "M";
  private static final String KEY_CONTENT = "C";
  private static final String KEY_TTL = "E";

  public CreateMessageDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "createmessagedb", "Creates the Alternator messagedb table with its associated index");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    MessageScyllaDbConfiguration scyllaMessageConfig = config.getMessageScyllaDbConfiguration();

    String tableName = scyllaMessageConfig.getTableName();

    DynamoDbClient messageScyllaDb = ScyllaDbFromConfig.client(scyllaMessageConfig);

    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_PARTITION).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_SORT).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(LOCAL_INDEX_MESSAGE_UUID_KEY_SORT).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_TYPE).attributeType("N").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_RELAY).attributeType("S").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_TIMESTAMP).attributeType("N").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_SOURCE).attributeType("S").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_SOURCE_UUID).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_SOURCE_DEVICE).attributeType("N").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_MESSAGE).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_CONTENT).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_TTL).attributeType("N").build());

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(KeySchemaElement.builder().attributeName(KEY_PARTITION).keyType(KeyType.HASH).build());
    keySchema.add(KeySchemaElement.builder().attributeName(KEY_SORT).keyType(KeyType.RANGE).build());

    List<KeySchemaElement> indexKeySchema = new ArrayList<KeySchemaElement>();
    indexKeySchema.add(KeySchemaElement.builder().attributeName(KEY_PARTITION).keyType(KeyType.HASH).build());
    indexKeySchema.add(KeySchemaElement.builder().attributeName(LOCAL_INDEX_MESSAGE_UUID_KEY_SORT).keyType(KeyType.RANGE).build());

    ArrayList<String> nonKeyAttributes = new ArrayList<String>();
    nonKeyAttributes.add("KEY_SORT");

    Projection projection = Projection.builder()
        .projectionType(ProjectionType.INCLUDE)
        .nonKeyAttributes(nonKeyAttributes)
        .build();

    LocalSecondaryIndex index = LocalSecondaryIndex.builder()
        .indexName(LOCAL_INDEX_MESSAGE_UUID_NAME)
        .keySchema(indexKeySchema)
        .projection(projection)
        .build();

    CreateTableRequest request = CreateTableRequest.builder()
        .tableName(tableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .localSecondaryIndexes(index)
        .billingMode("PAY_PER_REQUEST")
        .build();

    logger.info("Creating the messagedb table...");

    DynamoDbWaiter waiter = messageScyllaDb.waiter();

    messageScyllaDb.createTable(request);

    WaiterResponse<DescribeTableResponse> waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(tableName));

    if (waiterResponse.matched().response().isPresent()) {
      logger.info("Done");
    }

  }
}
