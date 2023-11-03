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
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;
import su.sres.shadowserver.util.ScyllaDbFromConfig;

public class CreateGroupLogsDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreateGroupLogsDbCommand.class);

  static final String KEY_GROUP_ID = "ID";
  static final String KEY_GROUP_VERSION = "V";
  static final String KEY_GROUP_CHANGE = "C";
  static final String KEY_GROUP_STATE = "S";

  public CreateGroupLogsDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "creategrouplogsdb", "Creates the Alternator grouplogsdb table");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ScyllaDbConfiguration scyllaConfig = config.getScyllaDbConfiguration();

    String tableName = scyllaConfig.getGroupLogsTableName();

    DynamoDbClient groupLogsScyllaDb = ScyllaDbFromConfig.client(scyllaConfig);

    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_GROUP_ID).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_GROUP_VERSION).attributeType("N").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_GROUP_CHANGE).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_GROUP_STATE).attributeType("B").build());

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(KeySchemaElement.builder().attributeName(KEY_GROUP_ID).keyType(KeyType.HASH).build());
    keySchema.add(KeySchemaElement.builder().attributeName(KEY_GROUP_VERSION).keyType(KeyType.RANGE).build());

    CreateTableRequest request = CreateTableRequest.builder()
        .tableName(tableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .billingMode("PAY_PER_REQUEST")
        .build();

    logger.info("Creating the grouplogsdb table...");

    DynamoDbWaiter waiter = groupLogsScyllaDb.waiter();

    groupLogsScyllaDb.createTable(request);

    WaiterResponse<DescribeTableResponse> waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(tableName));

    if (waiterResponse.matched().response().isPresent()) {

      logger.info("Done");

    }

  }
}
