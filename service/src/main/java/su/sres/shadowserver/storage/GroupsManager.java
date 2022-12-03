/*
 * Copyright 2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import com.google.protobuf.ByteString;
import su.sres.shadowserver.groups.protos.Group;
import su.sres.shadowserver.groups.protos.GroupChange;
import su.sres.shadowserver.groups.protos.GroupChanges.GroupChangeState;

import java.util.List;
import java.util.Optional;

public class GroupsManager {

  private final GroupsScyllaDb groupsScyllaDb;
  private final GroupLogsScyllaDb groupLogsScyllaDb;

  public GroupsManager(GroupsScyllaDb groupsScyllaDb, GroupLogsScyllaDb groupLogsScyllaDb) {
    this.groupsScyllaDb = groupsScyllaDb;
    this.groupLogsScyllaDb = groupLogsScyllaDb;
  }

  public Optional<Group> getGroup(ByteString groupId) {
    return groupsScyllaDb.getGroup(groupId.toByteArray());
  }

  public boolean createGroup(ByteString groupId, Group group) {
    return groupsScyllaDb.createGroup(groupId.toByteArray(), group);
  }

  public Optional<Group> updateGroup(ByteString groupId, Group group) {

    if (groupsScyllaDb.updateGroup(groupId.toByteArray(), group)) {
      return Optional.empty();
    } else {
      return Optional.of(getGroup(groupId).orElseThrow());
    }
  }

  public List<GroupChangeState> getChangeRecords(ByteString groupId, Group group, int fromVersionInclusive, int toVersionExclusive) {
    if (fromVersionInclusive >= toVersionExclusive) {
      throw new IllegalArgumentException("Version to read from (" + fromVersionInclusive + ") must be less than version to read to (" + toVersionExclusive + ")");
    }

    List<GroupChangeState> groupChangeStates = groupLogsScyllaDb.getRecordsFromVersion(groupId.toByteArray(), fromVersionInclusive, toVersionExclusive);

    if (isGroupInRange(group, fromVersionInclusive, toVersionExclusive) && groupVersionMissing(group, groupChangeStates) && toVersionExclusive - 1 == group.getVersion()) {
      groupChangeStates.add(GroupChangeState.newBuilder().setGroupState(group).build());
    }

    return groupChangeStates;
  }

  public boolean appendChangeRecord(ByteString groupId, int version, GroupChange change, Group state) {
    return groupLogsScyllaDb.append(groupId.toByteArray(), version, change, state);
  }

  private static boolean isGroupInRange(Group group, int fromVersionInclusive, int toVersionExclusive) {
    return fromVersionInclusive <= group.getVersion() && group.getVersion() < toVersionExclusive;
  }

  private static boolean groupVersionMissing(Group group, List<GroupChangeState> groupChangeStates) {
    return groupChangeStates.stream().noneMatch(groupChangeState -> groupChangeState.getGroupState().getVersion() == group.getVersion());
  }
}
