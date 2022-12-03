/* 
 * Copyright 2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import com.amazonaws.services.dynamodbv2.document.AttributeUpdate;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.KeyAttribute;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import su.sres.shadowserver.groups.protos.Group;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;



import javax.annotation.Nullable;

import static com.codahale.metrics.MetricRegistry.name;

public class GroupsScyllaDb extends AbstractScyllaDbStore {

  private final Table table;

  static final String KEY_GROUP_ID = "ID";
  static final String KEY_GROUP_VERSION = "V";
  static final String KEY_GROUP_DATA = "D";

  private static final Timer GET_TIMER = Metrics.timer(name(GroupsScyllaDb.class, "get"));
  private static final Timer CREATE_TIMER = Metrics.timer(name(GroupsScyllaDb.class, "create"));
  private static final Timer UPDATE_TIMER = Metrics.timer(name(GroupsScyllaDb.class, "update"));

  public GroupsScyllaDb(final DynamoDB scyllaDb, final String tableName) {
    super(scyllaDb);

    this.table = scyllaDb.getTable(tableName);
  }
 
  public Optional<Group> getGroup(byte[] groupId) {
    return GET_TIMER.record(() -> {
      
      @Nullable
      Item item = table.getItem(getPrimaryKey(KEY_GROUP_ID, groupId));

      if (item != null) {
        byte[] groupData = item.getBinary(KEY_GROUP_DATA);
        try {
          return Optional.ofNullable(Group.parseFrom(groupData));
        } catch (InvalidProtocolBufferException e) {
          throw new AssertionError(e);          
        }
      } else {
        return Optional.empty();
      }
    });
  }

  public boolean createGroup(byte[] groupId, Group group) {
    return CREATE_TIMER.record(() -> {

      final PrimaryKey primaryKey = getPrimaryKey(KEY_GROUP_ID, groupId);
      
      @Nullable
      Item item = table.getItem(primaryKey);

      if (item != null && item.getBinary(KEY_GROUP_DATA) != null) {
        return false;
      } else {
        Item itemNew = new Item().withPrimaryKey(primaryKey)
            .withBinary(KEY_GROUP_DATA, group.toByteArray())
            .withInt(KEY_GROUP_VERSION, group.getVersion());

        table.putItem(itemNew);

        return true;
      }
    });
  }

  public boolean updateGroup(byte[] groupId, Group group) {
    return UPDATE_TIMER.record(() -> {

      final PrimaryKey primaryKey = getPrimaryKey(KEY_GROUP_ID, groupId);
      int incomingVersion = group.getVersion();

      @Nullable
      Item item = table.getItem(primaryKey);

      if (item == null || item.getInt(KEY_GROUP_VERSION) != (incomingVersion - 1)) {
        return false;
      } else {

        List<AttributeUpdate> updates = new ArrayList<AttributeUpdate>();
        updates.add(new AttributeUpdate(KEY_GROUP_DATA).put(group.toByteArray()));
        updates.add(new AttributeUpdate(KEY_GROUP_VERSION).put(incomingVersion));

        UpdateItemSpec spec = new UpdateItemSpec().withPrimaryKey(primaryKey)
            .withAttributeUpdate(updates);

        table.updateItem(spec);

        return true;
      }
    });
  }
  
  private PrimaryKey getPrimaryKey(String name, byte[] value) {
    return new PrimaryKey(new KeyAttribute(name, value));
  }
}

