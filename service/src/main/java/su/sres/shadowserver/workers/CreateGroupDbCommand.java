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

public class CreateGroupDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreateGroupDbCommand.class);

  static final String KEY_GROUP_ID = "ID";
  static final String KEY_GROUP_VERSION = "V";
  static final String KEY_GROUP_DATA = "D";

  public CreateGroupDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "creategroupdb", "Creates the Alternator groupdb table");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ScyllaDbConfiguration scyllaGroupConfig = config.getGroupsScyllaDbConfiguration();

    AmazonDynamoDBClientBuilder clientBuilder = AmazonDynamoDBClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(scyllaGroupConfig.getEndpoint(), scyllaGroupConfig.getRegion()))
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(((int) scyllaGroupConfig.getClientExecutionTimeout().toMillis()))
            .withRequestTimeout((int) scyllaGroupConfig.getClientRequestTimeout().toMillis()))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(scyllaGroupConfig.getAccessKey(), scyllaGroupConfig.getAccessSecret())));

    DynamoDB groupDynamoDb = new DynamoDB(clientBuilder.build());

    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_GROUP_ID).withAttributeType("B"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_GROUP_VERSION).withAttributeType("N"));
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(KEY_GROUP_DATA).withAttributeType("B"));

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(new KeySchemaElement().withAttributeName(KEY_GROUP_ID).withKeyType(KeyType.HASH));
    
    CreateTableRequest request = new CreateTableRequest()
        .withTableName(scyllaGroupConfig.getTableName())
        .withKeySchema(keySchema)
        .withAttributeDefinitions(attributeDefinitions)
        .withBillingMode("PAY_PER_REQUEST");

    logger.info("Creating the groupdb table...");

    Table table = groupDynamoDb.createTable(request);

    table.waitForActive();

    logger.info("Done");

  }
}
