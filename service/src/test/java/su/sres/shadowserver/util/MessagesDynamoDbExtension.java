/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.util;

import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import su.sres.shadowserver.storage.DynamoDbExtension;

public class MessagesDynamoDbExtension {

  public static final String TABLE_NAME = "Shadow_Messages_UnitTest";

  public static DynamoDbExtension build() {
    return DynamoDbExtension.builder()
        .tableName(TABLE_NAME)
        .hashKey("H")
        .rangeKey("S")
        .attributeDefinition(
            AttributeDefinition.builder().attributeName("H").attributeType(ScalarAttributeType.B).build())
        .attributeDefinition(
            AttributeDefinition.builder().attributeName("S").attributeType(ScalarAttributeType.B).build())
        .attributeDefinition(
            AttributeDefinition.builder().attributeName("U").attributeType(ScalarAttributeType.B).build())
        .localSecondaryIndex(LocalSecondaryIndex.builder().indexName("Message_UUID_Index")
            .keySchema(KeySchemaElement.builder().attributeName("H").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("U").keyType(KeyType.RANGE).build())
            .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
            .build())
        .build();
  }
}
