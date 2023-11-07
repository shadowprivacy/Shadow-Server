/*
 * Copyright 2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

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

public class CreatePendingsDbCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(CreatePushChallengeDbCommand.class);

  static final String KEY_USER_LOGIN = "P";
  static final String ATTR_STORED_CODE = "C";
  static final String ATTR_TTL = "E";

  public CreatePendingsDbCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment)
          throws Exception {

      }
    }, "creatependingsdb", "Creates the Alternator pendingaccounts and pendingdevices tables");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ScyllaDbConfiguration scyllaConfig = config.getScyllaDbConfiguration();
    
    String accountsTableName = scyllaConfig.getPendingAccountsTableName();
    String devicesTableName = scyllaConfig.getPendingDevicesTableName();

    DynamoDbClient scyllaClient = ScyllaDbFromConfig.client(scyllaConfig);
    
    List<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(KEY_USER_LOGIN).attributeType("S").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(ATTR_STORED_CODE).attributeType("S").build());
    attributeDefinitions.add(AttributeDefinition.builder().attributeName(ATTR_TTL).attributeType("N").build());

    List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
    keySchema.add(KeySchemaElement.builder().attributeName(KEY_USER_LOGIN).keyType(KeyType.HASH).build());

    CreateTableRequest accRequest = CreateTableRequest.builder()
        .tableName(accountsTableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .billingMode("PAY_PER_REQUEST")
        .build();

    CreateTableRequest devRequest = CreateTableRequest.builder()
        .tableName(devicesTableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .billingMode("PAY_PER_REQUEST")
        .build();

    logger.info("Creating the pendingaccounts table...");

    DynamoDbWaiter waiter = scyllaClient.waiter();

    scyllaClient.createTable(accRequest);

    WaiterResponse<DescribeTableResponse> waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(accountsTableName));

    if (waiterResponse.matched().response().isPresent()) {
      logger.info("Done");
    }

    logger.info("Creating the pendingdevices table...");
    
    scyllaClient.createTable(devRequest);

    waiterResponse = waiter.waitUntilTableExists(r -> r.tableName(devicesTableName));

    if (waiterResponse.matched().response().isPresent()) {
      logger.info("Done");
    }

  }
}
