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
import com.amazonaws.services.dynamodbv2.model.LocalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.Projection;
import com.amazonaws.services.dynamodbv2.model.ProjectionType;
import com.fasterxml.jackson.databind.DeserializationFeature;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.MessageScyllaDbConfiguration;

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

    AmazonDynamoDBClientBuilder clientBuilder = AmazonDynamoDBClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(scyllaMessageConfig.getEndpoint(), scyllaMessageConfig.getRegion()))
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(((int) scyllaMessageConfig.getClientExecutionTimeout().toMillis()))
            .withRequestTimeout((int) scyllaMessageConfig.getClientRequestTimeout().toMillis()))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(scyllaMessageConfig.getAccessKey(), scyllaMessageConfig.getAccessSecret())));

    DynamoDB messageDynamoDb = new DynamoDB(clientBuilder.build());

    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_PARTITION).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_SORT).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(LOCAL_INDEX_MESSAGE_UUID_KEY_SORT).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_TYPE).withAttributeType("N"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_RELAY).withAttributeType("S"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_TIMESTAMP).withAttributeType("N"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_SOURCE).withAttributeType("S"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_SOURCE_UUID).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_SOURCE_DEVICE).withAttributeType("N"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_MESSAGE).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_CONTENT).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_TTL).withAttributeType("N"));

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(new KeySchemaElement().withAttributeName(KEY_PARTITION).withKeyType(KeyType.HASH));
    keySchema.add(new KeySchemaElement().withAttributeName(KEY_SORT).withKeyType(KeyType.RANGE));

    List<KeySchemaElement> indexKeySchema = new ArrayList<KeySchemaElement>();
    indexKeySchema.add(new KeySchemaElement().withAttributeName(KEY_PARTITION).withKeyType(KeyType.HASH));
    indexKeySchema.add(new KeySchemaElement().withAttributeName(LOCAL_INDEX_MESSAGE_UUID_KEY_SORT).withKeyType(KeyType.RANGE));

    Projection projection = new Projection().withProjectionType(ProjectionType.INCLUDE);
    ArrayList<String> nonKeyAttributes = new ArrayList<String>();
    nonKeyAttributes.add("KEY_SORT");
    projection.setNonKeyAttributes(nonKeyAttributes);

    LocalSecondaryIndex index = new LocalSecondaryIndex()
        .withIndexName(LOCAL_INDEX_MESSAGE_UUID_NAME)
        .withKeySchema(indexKeySchema)
        .withProjection(projection);

    CreateTableRequest request = new CreateTableRequest()
        .withTableName(scyllaMessageConfig.getTableName())
        .withKeySchema(keySchema)
        .withAttributeDefinitions(attributeDefinitions)
        .withLocalSecondaryIndexes(index)
        .withBillingMode("PAY_PER_REQUEST");    

    logger.info("Creating the messagedb table...");

    Table table = messageDynamoDb.createTable(request);

    table.waitForActive();

    logger.info("Done");

  }
}
