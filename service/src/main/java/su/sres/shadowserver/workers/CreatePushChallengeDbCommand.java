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

public class CreatePushChallengeDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreateKeysDbCommand.class);

  static final String KEY_ACCOUNT_UUID = "U";
  static final String ATTR_CHALLENGE_TOKEN = "C";
  static final String ATTR_TTL = "T";

  public CreatePushChallengeDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "createpushchallengedb", "Creates the Alternator pushchallengedb table");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ScyllaDbConfiguration scyllaPushChallengeConfig = config.getPushChallengeScyllaDbConfiguration();

    String tableName = scyllaPushChallengeConfig.getTableName();

    DynamoDbClient pushChallengeScyllaDb = ScyllaDbFromConfig.client(scyllaPushChallengeConfig);

    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_ACCOUNT_UUID).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(ATTR_CHALLENGE_TOKEN).attributeType("B").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(ATTR_TTL).attributeType("N").build());

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(KeySchemaElement.builder().attributeName(KEY_ACCOUNT_UUID).keyType(KeyType.HASH).build());

    CreateTableRequest request = CreateTableRequest.builder()
        .tableName(tableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .billingMode("PAY_PER_REQUEST")
        .build();

    logger.info("Creating the pushchallengedb table...");

    DynamoDbWaiter waiter = pushChallengeScyllaDb.waiter();

    pushChallengeScyllaDb.createTable(request);

    WaiterResponse<DescribeTableResponse> waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(tableName));

    if (waiterResponse.matched().response().isPresent()) {
      logger.info("Done");
    }

  }
}
