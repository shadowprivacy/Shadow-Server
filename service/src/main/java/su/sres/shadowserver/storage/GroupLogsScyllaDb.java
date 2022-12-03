/* 
 * Copyright 2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.google.protobuf.InvalidProtocolBufferException;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import su.sres.shadowserver.groups.protos.Group;
import su.sres.shadowserver.groups.protos.GroupChange;
import su.sres.shadowserver.groups.protos.GroupChanges.GroupChangeState;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import static com.codahale.metrics.MetricRegistry.name;

public class GroupLogsScyllaDb extends AbstractScyllaDbStore {

  private final Table table;

  static final String KEY_GROUP_ID = "ID";
  static final String KEY_GROUP_VERSION = "V";
  static final String KEY_GROUP_CHANGE = "C";
  static final String KEY_GROUP_STATE = "S";

  private static final Timer APPEND_TIMER = Metrics.timer(name(GroupsScyllaDb.class, "append"));
  private static final Timer GET_FROM_VERSION_TIMER = Metrics.timer(name(GroupsScyllaDb.class, "getFromVersion"));

  public GroupLogsScyllaDb(final DynamoDB scyllaDb, final String tableName) {
    super(scyllaDb);

    this.table = scyllaDb.getTable(tableName);
  }

  // Append

  public boolean append(byte[] groupId, int version, GroupChange groupChange, Group group) {

    return APPEND_TIMER.record(() -> {

      final PrimaryKey primaryKey = getPrimaryKey(KEY_GROUP_ID, groupId, KEY_GROUP_VERSION, version);
      
      @Nullable
      Item item = table.getItem(primaryKey); 

      if (item != null && item.getBinary(KEY_GROUP_CHANGE) != null) {
        return false;
      } else {
        Item itemNew = new Item().withPrimaryKey(primaryKey)
            .withBinary(KEY_GROUP_CHANGE, groupChange.toByteArray())
            .withBinary(KEY_GROUP_STATE, group.toByteArray());

        table.putItem(itemNew);

        return true;
      }
    });
  }

  // Get From Version

  public List<GroupChangeState> getRecordsFromVersion(byte[] groupId, int fromVersionInclusive, int toVersionExclusive) {

    return GET_FROM_VERSION_TIMER.record(() -> {

      List<GroupChangeState> results = new LinkedList<>();

      final QuerySpec querySpec = new QuerySpec().withKeyConditionExpression("#gid = :gid AND (#sort BETWEEN :sortlowval AND :sorthighval)")
          .withNameMap(Map.of("#gid", KEY_GROUP_ID, "#sort", KEY_GROUP_VERSION))
          .withValueMap(Map.of(":gid", groupId, ":sortlowval", fromVersionInclusive, ":sorthighval", toVersionExclusive - 1))
          .withConsistentRead(true);

      ItemCollection<QueryOutcome> queryResult = table.query(querySpec);

      Iterator<Item> it = queryResult.iterator();

      while (it.hasNext()) {

        Item item = it.next();

        try {
          results.add(GroupChangeState.newBuilder()
              .setGroupChange(GroupChange.parseFrom(item.getBinary(KEY_GROUP_CHANGE)))
              .setGroupState(Group.parseFrom(item.getBinary(KEY_GROUP_STATE)))
              .build());
        } catch (InvalidProtocolBufferException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }

      return results;

    });
  }

  private PrimaryKey getPrimaryKey(String partition, byte[] partitionValue, String sort, int sortValue) {
    return new PrimaryKey(partition, partitionValue, sort, sortValue);
  }
}
