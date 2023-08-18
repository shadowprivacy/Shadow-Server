package su.sres.shadowserver.storage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import su.sres.shadowserver.util.UUIDUtil;

class ReportMessageScyllaDbTest {

  private ReportMessageScyllaDb reportMessageDynamoDb;

  private static final String TABLE_NAME = "report_message_test";

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = DynamoDbExtension.builder()
      .tableName(TABLE_NAME)
      .hashKey(ReportMessageScyllaDb.KEY_HASH)
      .attributeDefinition(AttributeDefinition.builder()
          .attributeName(ReportMessageScyllaDb.KEY_HASH)
          .attributeType(ScalarAttributeType.B)
          .build())
      .build();


  @BeforeEach
  void setUp() {
    this.reportMessageDynamoDb = new ReportMessageScyllaDb(dynamoDbExtension.getDynamoDbClient(), TABLE_NAME);
  }

  @Test
  void testStore() {

    final byte[] hash1 = UUIDUtil.toBytes(UUID.randomUUID());
    final byte[] hash2 = UUIDUtil.toBytes(UUID.randomUUID());

    assertAll("database should be empty",
        () -> assertFalse(reportMessageDynamoDb.remove(hash1)),
        () -> assertFalse(reportMessageDynamoDb.remove(hash2))
    );

    reportMessageDynamoDb.store(hash1);
    reportMessageDynamoDb.store(hash2);

    assertAll("both hashes should be found",
        () -> assertTrue(reportMessageDynamoDb.remove(hash1)),
        () -> assertTrue(reportMessageDynamoDb.remove(hash2))
    );

    assertAll( "database should be empty",
        () -> assertFalse(reportMessageDynamoDb.remove(hash1)),
        () -> assertFalse(reportMessageDynamoDb.remove(hash2))
    );
  }

}
