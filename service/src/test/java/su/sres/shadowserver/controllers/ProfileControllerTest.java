/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCommitment;
import org.signal.zkgroup.profiles.ServerZkProfileOperations;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.errors.MinioException;
import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.entities.CreateProfileRequest;
import su.sres.shadowserver.entities.Profile;
import su.sres.shadowserver.entities.ProfileAvatarUploadAttributes;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.s3.PolicySigner;
import su.sres.shadowserver.s3.PostPolicyGenerator;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.PaymentAddress;
import su.sres.shadowserver.storage.ProfilesManager;
import su.sres.shadowserver.storage.UsernamesManager;
import su.sres.shadowserver.storage.VersionedProfile;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.SystemMapper;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
public class ProfileControllerTest {

    private static AccountsManager accountsManager = mock(AccountsManager.class);
    private static ProfilesManager profilesManager = mock(ProfilesManager.class);
    private static UsernamesManager usernamesManager = mock(UsernamesManager.class);
    private static RateLimiters rateLimiters = mock(RateLimiters.class);
    private static RateLimiter rateLimiter = mock(RateLimiter.class);
    private static RateLimiter usernameRateLimiter = mock(RateLimiter.class);
    private static MinioClient minioClient = mock(MinioClient.class);
    private static PostPolicyGenerator postPolicyGenerator = new PostPolicyGenerator("us-east-1", "profile-bucket",
	    "accessKey");
    private static PolicySigner policySigner = new PolicySigner("accessSecret", "us-east-1");
    private static ServerZkProfileOperations zkProfileOperations = mock(ServerZkProfileOperations.class);

    public static final ResourceExtension resources;
    // static initializer for resources
    static {
	try {
	    resources = ResourceExtension.builder().addProvider(AuthHelper.getAuthFilter())
		    .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(
			    ImmutableSet.of(Account.class, DisabledPermittedAccount.class)))
		    .setMapper(SystemMapper.getMapper()).setTestContainerFactory(new GrizzlyWebTestContainerFactory())
		    .addResource(new ProfileController(rateLimiters, accountsManager, profilesManager, usernamesManager,
			    minioClient, postPolicyGenerator, policySigner, "profilesBucket", zkProfileOperations,
			    true))
		    .build();
	} catch (final Exception e) {
	    throw new RuntimeException("Failed to create ResourceExtension instance in static block.", e);
	}
    }

    @Before
    public void setup() throws Exception {

	when(rateLimiters.getProfileLimiter()).thenReturn(rateLimiter);
	when(rateLimiters.getUsernameLookupLimiter()).thenReturn(usernameRateLimiter);

	Account profileAccount = mock(Account.class);

	when(profileAccount.getIdentityKey()).thenReturn("bar");
	when(profileAccount.getProfileName()).thenReturn("baz");
	when(profileAccount.getAvatar()).thenReturn("bang");
	when(profileAccount.getUuid()).thenReturn(AuthHelper.VALID_UUID_TWO);
	when(profileAccount.isEnabled()).thenReturn(true);
	when(profileAccount.isGroupsV2Supported()).thenReturn(false);
	when(profileAccount.isGv1MigrationSupported()).thenReturn(false);
	when(profileAccount.getPayments())
		.thenReturn(List.of(new PaymentAddress("mc", "12345678901234567890123456789012")));

	Account capabilitiesAccount = mock(Account.class);

	when(capabilitiesAccount.getIdentityKey()).thenReturn("barz");
	when(capabilitiesAccount.getProfileName()).thenReturn("bazz");
	when(capabilitiesAccount.getAvatar()).thenReturn("bangz");
	when(capabilitiesAccount.isEnabled()).thenReturn(true);
	when(capabilitiesAccount.isGroupsV2Supported()).thenReturn(true);
	when(capabilitiesAccount.isGv1MigrationSupported()).thenReturn(true);

	when(accountsManager.get(AuthHelper.VALID_NUMBER_TWO)).thenReturn(Optional.of(profileAccount));
	when(accountsManager.get(AuthHelper.VALID_UUID_TWO)).thenReturn(Optional.of(profileAccount));
	when(usernamesManager.get(AuthHelper.VALID_UUID_TWO)).thenReturn(Optional.of("n00bkiller"));
	when(usernamesManager.get("n00bkiller")).thenReturn(Optional.of(AuthHelper.VALID_UUID_TWO));
	when(accountsManager.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
		&& identifier.hasUserLogin() && identifier.getUserLogin().equals(AuthHelper.VALID_NUMBER_TWO))))
			.thenReturn(Optional.of(profileAccount));
	when(accountsManager.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
		&& identifier.hasUuid() && identifier.getUuid().equals(AuthHelper.VALID_UUID_TWO))))
			.thenReturn(Optional.of(profileAccount));

	when(accountsManager.get(AuthHelper.VALID_NUMBER)).thenReturn(Optional.of(capabilitiesAccount));
	when(accountsManager.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
		&& identifier.hasUserLogin() && identifier.getUserLogin().equals(AuthHelper.VALID_NUMBER))))
			.thenReturn(Optional.of(capabilitiesAccount));

	when(profilesManager.get(eq(AuthHelper.VALID_UUID), eq("someversion"))).thenReturn(Optional.empty());
	when(profilesManager.get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion")))
		.thenReturn(Optional.of(new VersionedProfile("validversion", "validname", "profiles/validavatar",
			"validcommitmnet".getBytes())));

	clearInvocations(rateLimiter);
	clearInvocations(accountsManager);
	clearInvocations(usernamesManager);
	clearInvocations(usernameRateLimiter);
	clearInvocations(profilesManager);
    }

    @Test
    public void testProfileGetByUuid() throws RateLimitExceededException {
	Profile profile = resources.getJerseyTest().target("/v1/profile/" + AuthHelper.VALID_UUID_TWO).request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.get(Profile.class);

	assertThat(profile.getIdentityKey(), equalTo("bar"));
	assertThat(profile.getName(), equalTo("baz"));
	assertThat(profile.getAvatar(), equalTo("bang"));
	assertThat(profile.getUsername(), equalTo("n00bkiller"));
	assertThat(profile.getPayments(), equalTo(List.of(new PaymentAddress("mc", "12345678901234567890123456789012"))));

	verify(accountsManager, times(1))
		.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
			&& identifier.hasUuid() && identifier.getUuid().equals(AuthHelper.VALID_UUID_TWO)));
	verify(usernamesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO));
	verify(rateLimiter, times(1)).validate(eq(AuthHelper.VALID_NUMBER));
    }

    @Test
    public void testProfileGetByNumber() throws RateLimitExceededException {
	Profile profile = resources.getJerseyTest().target("/v1/profile/" + AuthHelper.VALID_NUMBER_TWO).request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.get(Profile.class);

	assertThat(profile.getIdentityKey(), equalTo("bar"));
	assertThat(profile.getName(), equalTo("baz"));
	assertThat(profile.getAvatar(), equalTo("bang"));
	assertThat(profile.getPayments(), equalTo(List.of(new PaymentAddress("mc", "12345678901234567890123456789012"))));
	assertThat(profile.getCapabilities().isGv2(), equalTo(false));
	assertThat(profile.getCapabilities().isGv1Migration(), equalTo(false));
	assertThat(profile.getUsername(), nullValue());
	assertThat(profile.getUuid(), nullValue());

	verify(accountsManager, times(1))
		.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
			&& identifier.hasUserLogin() && identifier.getUserLogin().equals(AuthHelper.VALID_NUMBER_TWO)));
	verifyNoMoreInteractions(usernamesManager);
	verify(rateLimiter, times(1)).validate(eq(AuthHelper.VALID_NUMBER));
    }

    @Test
    public void testProfileGetByUsername() throws RateLimitExceededException {
	Profile profile = resources.getJerseyTest().target("/v1/profile/username/n00bkiller").request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.get(Profile.class);

	assertThat(profile.getIdentityKey(), equalTo("bar"));
	assertThat(profile.getName(), equalTo("baz"));
	assertThat(profile.getAvatar(), equalTo("bang"));
	assertThat(profile.getUsername(), equalTo("n00bkiller"));
	assertThat(profile.getPayments(), equalTo(List.of(new PaymentAddress("mc", "12345678901234567890123456789012"))));
	assertThat(profile.getUuid(), equalTo(AuthHelper.VALID_UUID_TWO));

	verify(accountsManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO));
	verify(usernamesManager, times(1)).get(eq("n00bkiller"));
	verify(usernameRateLimiter, times(1)).validate(eq(AuthHelper.VALID_UUID.toString()));
    }

    @Test
    public void testProfileGetUnauthorized() throws Exception {
	Response response = resources.getJerseyTest().target("/v1/profile/" + AuthHelper.VALID_NUMBER_TWO).request()
		.get();

	assertThat(response.getStatus(), equalTo(401));
    }

    @Test
    public void testProfileGetByUsernameUnauthorized() throws Exception {
	Response response = resources.getJerseyTest().target("/v1/profile/username/n00bkiller").request().get();

	assertThat(response.getStatus(), equalTo(401));
    }

    @Test
    public void testProfileGetByUsernameNotFound() throws RateLimitExceededException {
	Response response = resources.getJerseyTest().target("/v1/profile/username/n00bkillerzzzzz").request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.get();

	assertThat(response.getStatus(), equalTo(404));

	verify(usernamesManager, times(1)).get(eq("n00bkillerzzzzz"));
	verify(usernameRateLimiter, times(1)).validate(eq(AuthHelper.VALID_UUID.toString()));
    }

    @Test
    public void testProfileGetDisabled() throws Exception {
	Response response = resources.getJerseyTest().target("/v1/profile/" + AuthHelper.VALID_NUMBER_TWO).request()
		.header("Authorization",
			AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
		.get();

	assertThat(response.getStatus(), equalTo(401));
    }

    @Test
    public void testProfileCapabilities() throws Exception {
	Profile profile = resources.getJerseyTest().target("/v1/profile/" + AuthHelper.VALID_NUMBER).request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.get(Profile.class);

	assertThat(profile.getCapabilities().isGv2(), equalTo(true));
	assertThat(profile.getCapabilities().isGv1Migration(), equalTo(true));
    }

    @Test
    public void testSetProfileNameDeprecated() {
	Response response = resources.getJerseyTest()
		.target("/v1/profile/name/123456789012345678901234567890123456789012345678901234567890123456789012")
		.request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.put(Entity.text(""));

	assertThat(response.getStatus(), equalTo(204));

	verify(accountsManager, times(1)).update(any(Account.class));
    }

    @Test
    public void testSetProfileNameExtendedDeprecated() {
	Response response = resources.getJerseyTest().target(
		"/v1/profile/name/123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678")
		.request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.put(Entity.text(""));

	assertThat(response.getStatus(), equalTo(204));

	verify(accountsManager, times(1)).update(any(Account.class));
    }

    @Test
    public void testSetProfileNameWrongSizeDeprecated() {
	Response response = resources.getJerseyTest().target(
		"/v1/profile/name/1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890")
		.request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.put(Entity.text(""));

	assertThat(response.getStatus(), equalTo(400));
	verifyNoMoreInteractions(accountsManager);
    }

/////

    @Test
    public void testSetProfileWantAvatarUpload() throws InvalidInputException {
	ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(AuthHelper.VALID_UUID);

	ProfileAvatarUploadAttributes uploadAttributes = resources.getJerseyTest().target("/v1/profile/").request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.put(Entity.entity(new CreateProfileRequest(commitment, "someversion",
			"123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678",
			true), MediaType.APPLICATION_JSON_TYPE), ProfileAvatarUploadAttributes.class);

	ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

	verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID), eq("someversion"));
	verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID), profileArgumentCaptor.capture());

	verifyNoMoreInteractions(minioClient);

	assertThat(profileArgumentCaptor.getValue().getCommitment(), equalTo(commitment.serialize()));
	assertThat(profileArgumentCaptor.getValue().getAvatar(), equalTo(uploadAttributes.getKey()));
	assertThat(profileArgumentCaptor.getValue().getVersion(), equalTo("someversion"));
	assertThat(profileArgumentCaptor.getValue().getName(), equalTo(
		"123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"));
    }

    @Test
    public void testSetProfileWantAvatarUploadWithBadProfileSize() throws InvalidInputException {
	ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(AuthHelper.VALID_UUID);

	Response response = resources.getJerseyTest().target("/v1/profile/").request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.put(Entity.entity(new CreateProfileRequest(commitment, "someversion",
			"1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890",
			true), MediaType.APPLICATION_JSON_TYPE));

	assertThat(response.getStatus(), equalTo(422));
    }

    @Test
    public void testSetProfileWithoutAvatarUpload() throws InvalidInputException {
	ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(AuthHelper.VALID_UUID);

	clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

	Response response = resources.getJerseyTest().target("/v1/profile/").request()
		.header("Authorization",
			AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER_TWO, AuthHelper.VALID_PASSWORD_TWO))
		.put(Entity.entity(new CreateProfileRequest(commitment, "anotherversion",
			"123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678",
			false), MediaType.APPLICATION_JSON_TYPE));

	assertThat(response.getStatus(), equalTo(200));
	assertThat(response.hasEntity(), equalTo(false));

	ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

	verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("anotherversion"));
	verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());

	verify(AuthHelper.VALID_ACCOUNT_TWO).setProfileName("123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678");
	verify(AuthHelper.VALID_ACCOUNT_TWO).setAvatar(null);

	verifyNoMoreInteractions(minioClient);

	assertThat(profileArgumentCaptor.getValue().getCommitment(), equalTo(commitment.serialize()));
	assertThat(profileArgumentCaptor.getValue().getAvatar(), nullValue());
	assertThat(profileArgumentCaptor.getValue().getVersion(), equalTo("anotherversion"));
	assertThat(profileArgumentCaptor.getValue().getName(), equalTo(
		"123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"));
    }

    @Test
    public void testSetProvfileWithAvatarUploadAndPreviousAvatar()
	    throws InvalidInputException, MinioException, InvalidKeyException, IOException, NoSuchAlgorithmException {
	ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(AuthHelper.VALID_UUID_TWO);

	ProfileAvatarUploadAttributes uploadAttributes = resources.getJerseyTest().target("/v1/profile/").request()
		.header("Authorization",
			AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER_TWO, AuthHelper.VALID_PASSWORD_TWO))
		.put(Entity.entity(new CreateProfileRequest(commitment, "validversion",
			"123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678",
			true), MediaType.APPLICATION_JSON_TYPE), ProfileAvatarUploadAttributes.class);

	ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

	verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));
	verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());
	verify(minioClient, times(1)).removeObject(
		RemoveObjectArgs.builder().bucket(eq("profilesBucket")).object(eq("validavatar")).build());

	assertThat(profileArgumentCaptor.getValue().getCommitment(), equalTo(commitment.serialize()));
	assertThat(profileArgumentCaptor.getValue().getVersion(), equalTo("validversion"));
	assertThat(profileArgumentCaptor.getValue().getName(), equalTo(
		"123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"));
    }

    @Test
    public void testGetProfileByVersion() throws RateLimitExceededException {
	Profile profile = resources.getJerseyTest().target("/v1/profile/" + AuthHelper.VALID_UUID_TWO + "/validversion")
		.request()
		.header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
		.get(Profile.class);

	assertThat(profile.getIdentityKey(), equalTo("bar"));
	assertThat(profile.getName(), equalTo("validname"));
	assertThat(profile.getAvatar(), equalTo("validavatar"));
	assertThat(profile.getCapabilities().isGv2(), equalTo(false));
	assertThat(profile.getCapabilities().isGv1Migration(), equalTo(false));
	assertThat(profile.getUsername(), equalTo("n00bkiller"));
	assertThat(profile.getUuid(), nullValue());

	verify(accountsManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO));
	verify(usernamesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO));
	verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));

	verify(rateLimiter, times(1)).validate(eq(AuthHelper.VALID_NUMBER));
    }

}