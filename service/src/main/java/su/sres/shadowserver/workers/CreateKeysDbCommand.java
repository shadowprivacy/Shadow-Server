package su.sres.shadowserver.workers;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.fasterxml.jackson.databind.DeserializationFeature;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;

public class CreateKeysDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreateKeysDbCommand.class);

  static final String KEY_ACCOUNT_UUID = "U";
  static final String KEY_DEVICE_ID_KEY_ID = "DK";
  static final String KEY_PUBLIC_KEY = "P";

  public CreateKeysDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "createkeysdb", "Creates the Alternator keysdb table with its associated index");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ScyllaDbConfiguration scyllaKeysConfig = config.getKeysScyllaDbConfiguration();

    AmazonDynamoDBClientBuilder clientBuilder = AmazonDynamoDBClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(scyllaKeysConfig.getEndpoint(), scyllaKeysConfig.getRegion()))
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(((int) scyllaKeysConfig.getClientExecutionTimeout().toMillis()))
            .withRequestTimeout((int) scyllaKeysConfig.getClientRequestTimeout().toMillis()))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(scyllaKeysConfig.getAccessKey(), scyllaKeysConfig.getAccessSecret())));

    DynamoDB keysDynamoDb = new DynamoDB(clientBuilder.build());

    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_ACCOUNT_UUID).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_DEVICE_ID_KEY_ID).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_PUBLIC_KEY).withAttributeType("S"));

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(new KeySchemaElement().withAttributeName(KEY_ACCOUNT_UUID).withKeyType(KeyType.HASH));
    keySchema.add(new KeySchemaElement().withAttributeName(KEY_DEVICE_ID_KEY_ID).withKeyType(KeyType.RANGE));

    CreateTableRequest request = new CreateTableRequest()
        .withTableName(scyllaKeysConfig.getTableName())
        .withKeySchema(keySchema)
        .withAttributeDefinitions(attributeDefinitions)
        .withBillingMode("PAY_PER_REQUEST");

    logger.info("Creating the keysdb table...");

    Table table = keysDynamoDb.createTable(request);

    table.waitForActive();

    logger.info("Done");

  }
}
