/*
 * Copyright 2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.controllers;

import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit.ResourceTestRule;
import org.junit.Before;
import org.junit.Rule;
import su.sres.shadowserver.auth.ExternalGroupCredentialGenerator;
import su.sres.shadowserver.auth.GroupUser;
import su.sres.shadowserver.configuration.GroupConfiguration;
import su.sres.shadowserver.providers.InvalidProtocolBufferExceptionMapper;
import su.sres.shadowserver.providers.ProtocolBufferMessageBodyProvider;
import su.sres.shadowserver.providers.ProtocolBufferValidationErrorMessageBodyWriter;
import su.sres.shadowserver.s3.PolicySigner;
import su.sres.shadowserver.s3.PostPolicyGenerator;
import su.sres.shadowserver.storage.GroupsManager;
import su.sres.shadowserver.groups.protos.AccessControl;
import su.sres.shadowserver.groups.protos.Group;
import su.sres.shadowserver.groups.protos.Member;
import su.sres.shadowserver.util.GroupAuthHelper;
import su.sres.shadowserver.util.SystemMapper;
import su.sres.shadowserver.util.Util;
import org.signal.zkgroup.groups.GroupPublicParams;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.signal.zkgroup.profiles.ClientZkProfileOperations;
import org.signal.zkgroup.profiles.ProfileKeyCredentialPresentation;

import java.security.SecureRandom;
import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

public abstract class BaseGroupsControllerTest {
  protected final ExternalGroupCredentialGenerator groupCredentialGenerator = new ExternalGroupCredentialGenerator(
      Util.generateSecretBytes(32), Clock.systemUTC());
  protected final GroupSecretParams groupSecretParams = GroupSecretParams.generate();
  protected final GroupPublicParams groupPublicParams = groupSecretParams.getPublicParams();
  protected final ProfileKeyCredentialPresentation validUserPresentation = new ClientZkProfileOperations(GroupAuthHelper.GROUPS_SERVER_KEY.getPublicParams()).createProfileKeyCredentialPresentation(groupSecretParams, GroupAuthHelper.VALID_USER_PROFILE_CREDENTIAL);
  protected final ProfileKeyCredentialPresentation validUserTwoPresentation = new ClientZkProfileOperations(GroupAuthHelper.GROUPS_SERVER_KEY.getPublicParams()).createProfileKeyCredentialPresentation(groupSecretParams, GroupAuthHelper.VALID_USER_TWO_PROFILE_CREDENTIAL);
  protected final ProfileKeyCredentialPresentation validUserThreePresentation = new ClientZkProfileOperations(GroupAuthHelper.GROUPS_SERVER_KEY.getPublicParams()).createProfileKeyCredentialPresentation(groupSecretParams, GroupAuthHelper.VALID_USER_THREE_PROFILE_CREDENTIAL);
  protected final ProfileKeyCredentialPresentation validUserFourPresentation = new ClientZkProfileOperations(GroupAuthHelper.GROUPS_SERVER_KEY.getPublicParams()).createProfileKeyCredentialPresentation(groupSecretParams, GroupAuthHelper.VALID_USER_FOUR_PROFILE_CREDENTIAL);
  protected final GroupsManager groupsManager = mock(GroupsManager.class);
  protected final PostPolicyGenerator postPolicyGenerator = new PostPolicyGenerator("us-west-1", "profile-bucket", "accessKey");
  protected final PolicySigner policySigner = new PolicySigner("accessSecret", "us-west-1");
  protected final Group group = Group.newBuilder()
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
          .setPresentation(ByteString.copyFrom(validUserPresentation.serialize()))
          .setRole(Member.Role.ADMINISTRATOR)
          .setJoinedAtVersion(0)
          .build())
      .addMembers(Member.newBuilder()
          .setUserId(ByteString.copyFrom(validUserTwoPresentation.getUuidCiphertext().serialize()))
          .setProfileKey(ByteString.copyFrom(validUserTwoPresentation.getProfileKeyCiphertext().serialize()))
          .setPresentation(ByteString.copyFrom(validUserTwoPresentation.serialize()))
          .setRole(Member.Role.DEFAULT)
          .setJoinedAtVersion(0)
          .build())
      .build();

  @Rule
  public final ResourceTestRule resources = ResourceTestRule.builder()
      .addProvider(GroupAuthHelper.getAuthFilter())
      .addProvider(new AuthValueFactoryProvider.Binder<>(GroupUser.class))
      .addProvider(new ProtocolBufferMessageBodyProvider())
      .addProvider(new ProtocolBufferValidationErrorMessageBodyWriter())
      .addProvider(new InvalidProtocolBufferExceptionMapper())
      .setMapper(SystemMapper.getMapper())
      .addResource(new GroupsController(groupsManager, GroupAuthHelper.GROUPS_SERVER_KEY, policySigner, postPolicyGenerator, getGroupConfiguration(), groupCredentialGenerator))
      .build();

  protected GroupConfiguration getGroupConfiguration() {
    final GroupConfiguration groupConfiguration = new GroupConfiguration();
    groupConfiguration.setMaxGroupSize(42);
    groupConfiguration.setMaxGroupTitleLengthBytes(1024);
    groupConfiguration.setMaxGroupDescriptionLengthBytes(8192);
    return groupConfiguration;
  }

  protected String avatarFor(byte[] groupId) {
    byte[] object = new byte[16];
    new SecureRandom().nextBytes(object);

    return "groups/" + encodeBase64URLSafeString(groupId) + "/" + encodeBase64URLSafeString(object);
  }

  @Before
  public void resetGroupsManager() {
    reset(groupsManager);
  }

  /**
   * Encodes binary data using a URL-safe variation of the base64 algorithm but
   * does not chunk the output. The url-safe variation emits - and _ instead of +
   * and / characters.
   *
   * @param binaryData binary data to encode or {@code null} for {@code null}
   *                   result
   * @return String containing Base64 characters or {@code null} for {@code null}
   *         input
   */
  public static String encodeBase64URLSafeString(byte[] binaryData) {
    if (binaryData == null) {
      return null;
    }
    return BaseEncoding.base64Url().omitPadding().encode(binaryData);
  }
}
