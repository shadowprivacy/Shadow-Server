/*
 * Copyright 2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.controllers;

import com.google.protobuf.ByteString;
import org.junit.Test;
import su.sres.shadowserver.configuration.GroupConfiguration;
import su.sres.shadowserver.providers.ProtocolBufferMediaType;
import su.sres.shadowserver.groups.protos.AccessControl;
import su.sres.shadowserver.groups.protos.Group;
import su.sres.shadowserver.groups.protos.GroupChange;
import su.sres.shadowserver.groups.protos.Member;
import su.sres.shadowserver.groups.protos.MemberPendingProfileKey;
import su.sres.shadowserver.util.GroupAuthHelper;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class GroupsControllerMaxSizeTest extends BaseGroupsControllerTest {
  @Override
  protected GroupConfiguration getGroupConfiguration() {
    final GroupConfiguration groupConfiguration = super.getGroupConfiguration();
    groupConfiguration.setMaxGroupSize(2);
    return groupConfiguration;
  }

  @Test
  public void testAddMemberWhenTooMany() {
    when(groupsManager.getGroup(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize()))))
            .thenReturn(Optional.of(group));

    when(groupsManager.updateGroup(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize())), any(Group.class)))
            .thenReturn(Optional.empty());

    when(groupsManager.appendChangeRecord(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize())), eq(1), any(GroupChange.class), any(Group.class)))
            .thenReturn(true);

    GroupChange.Actions groupChange = GroupChange.Actions.newBuilder()
                                                         .setVersion(1)
                                                         .addAddMembers(GroupChange.Actions.AddMemberAction.newBuilder()
                                                                                                           .setAdded(Member.newBuilder()
                                                                                                                           .setPresentation(ByteString.copyFrom(validUserThreePresentation.serialize()))
                                                                                                                           .setRole(Member.Role.DEFAULT)
                                                                                                                           .build()))
                                                         .build();

    Response response = resources.getJerseyTest()
                                 .target("/v1/groups/")
                                 .request(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
                                 .header("Authorization", GroupAuthHelper.getAuthHeader(groupSecretParams, GroupAuthHelper.VALID_USER_AUTH_CREDENTIAL))
                                 .method("PATCH", Entity.entity(groupChange.toByteArray(), ProtocolBufferMediaType.APPLICATION_PROTOBUF));

    assertThat(response.getStatus()).isEqualTo(400);
    verify(groupsManager).getGroup(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize())));
    verifyNoMoreInteractions(groupsManager);
  }

  @Test
  public void testAddMemberWhenMembersPendingProfileKey() {
    Group group = Group.newBuilder()
                       .setPublicKey(ByteString.copyFrom(groupPublicParams.serialize()))
                       .setAccessControl(AccessControl.newBuilder()
                                                      .setMembers(AccessControl.AccessRequired.MEMBER)
                                                      .setAttributes(AccessControl.AccessRequired.MEMBER))
                       .setTitle(ByteString.copyFromUtf8("Some title"))
                       .setAvatar(avatarFor(groupPublicParams.getGroupIdentifier().serialize()))
                       .setVersion(0)
                       .addMembers(Member.newBuilder()
                                         .setUserId(ByteString.copyFrom(validUserPresentation.getUuidCiphertext().serialize()))
                                         .setProfileKey(ByteString.copyFrom(validUserPresentation.getProfileKeyCiphertext().serialize()))
                                         .setRole(Member.Role.ADMINISTRATOR)
                                         .setJoinedAtVersion(0)
                                         .build())
                       .addMembersPendingProfileKey(MemberPendingProfileKey.newBuilder()
                                                                           .setMember(Member.newBuilder()
                                                                                            .setUserId(ByteString.copyFrom(validUserTwoPresentation.getUuidCiphertext().serialize()))
                                                                                            .setRole(Member.Role.DEFAULT)
                                                                                            .build())
                                                                           .setAddedByUserId(ByteString.copyFrom(validUserPresentation.getUuidCiphertext().serialize()))
                                                                           .setTimestamp(System.currentTimeMillis())
                                                                           .build())
                       .build();

    when(groupsManager.getGroup(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize()))))
            .thenReturn(Optional.of(group));

    when(groupsManager.updateGroup(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize())), any(Group.class)))
            .thenReturn(Optional.empty());

    when(groupsManager.appendChangeRecord(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize())), eq(1), any(GroupChange.class), any(Group.class)))
            .thenReturn(true);

    GroupChange.Actions groupChange = GroupChange.Actions.newBuilder()
                                                         .setVersion(1)
                                                         .addAddMembers(GroupChange.Actions.AddMemberAction.newBuilder()
                                                                                                           .setAdded(Member.newBuilder()
                                                                                                                           .setPresentation(ByteString.copyFrom(validUserThreePresentation.serialize()))
                                                                                                                           .setRole(Member.Role.DEFAULT)
                                                                                                                           .build()))
                                                         .build();

    Response response = resources.getJerseyTest()
                                 .target("/v1/groups/")
                                 .request(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
                                 .header("Authorization", GroupAuthHelper.getAuthHeader(groupSecretParams, GroupAuthHelper.VALID_USER_AUTH_CREDENTIAL))
                                 .method("PATCH", Entity.entity(groupChange.toByteArray(), ProtocolBufferMediaType.APPLICATION_PROTOBUF));

    assertThat(response.getStatus()).isEqualTo(400);
    verify(groupsManager).getGroup(eq(ByteString.copyFrom(groupPublicParams.getGroupIdentifier().serialize())));
    verifyNoMoreInteractions(groupsManager);
  }
}
