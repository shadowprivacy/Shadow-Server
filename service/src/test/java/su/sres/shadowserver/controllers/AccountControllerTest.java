/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.security.SecureRandom;
import java.time.Duration;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;

import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.auth.TurnTokenGenerator;
import su.sres.shadowserver.configuration.LocalParametersConfiguration;
import su.sres.shadowserver.configuration.ServiceConfiguration;
import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.entities.AccountCreationResult;
import su.sres.shadowserver.entities.ApnRegistrationId;
import su.sres.shadowserver.entities.GcmRegistrationId;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.mappers.RateLimitExceededExceptionMapper;
// import su.sres.shadowserver.push.APNSender;
import su.sres.shadowserver.push.ApnMessage;
import su.sres.shadowserver.push.GCMSender;
import su.sres.shadowserver.push.GcmMessage;
import su.sres.shadowserver.recaptcha.RecaptchaClient;

import su.sres.shadowserver.storage.AbusiveHostRule;
import su.sres.shadowserver.storage.AbusiveHostRules;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.PendingAccountsManager;
import su.sres.shadowserver.storage.UsernamesManager;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.Hex;
import su.sres.shadowserver.util.SystemMapper;

@ExtendWith(DropwizardExtensionsSupport.class)
class AccountControllerTest {

  private static final String SENDER = "richardroe";
  private static final String SENDER_OLD = "miketyson";  
  private static final String SENDER_PREAUTH = "tysonfury";  
  private static final String SENDER_HAS_STORAGE = "canelo";
  private static final String SENDER_TRANSFER = "sam";

  private static final String ABUSIVE_HOST = "192.168.1.1";
  private static final String NICE_HOST = "127.0.0.1";
  private static final String RATE_LIMITED_IP_HOST = "10.0.0.1";
  private static final String RATE_LIMITED_PREFIX_HOST = "10.0.0.2";
  private static final String RATE_LIMITED_HOST2 = "10.0.0.3";

  private static final String VALID_CAPTCHA_TOKEN = "valid_token";
  private static final String INVALID_CAPTCHA_TOKEN = "invalid_token";
  
  private static final int VERIFICATION_CODE_LIFETIME = 48;

  private static PendingAccountsManager pendingAccountsManager = mock(PendingAccountsManager.class);
  private static AccountsManager accountsManager = mock(AccountsManager.class);
  private static AbusiveHostRules abusiveHostRules = mock(AbusiveHostRules.class);
  private static RateLimiters rateLimiters = mock(RateLimiters.class);
  private static RateLimiter rateLimiter = mock(RateLimiter.class);
  private static RateLimiter pinLimiter = mock(RateLimiter.class);
  private static RateLimiter smsVoiceIpLimiter = mock(RateLimiter.class);
  private static RateLimiter autoBlockLimiter = mock(RateLimiter.class);
  private static RateLimiter usernameSetLimiter = mock(RateLimiter.class);

  private static MessagesManager storedMessages = mock(MessagesManager.class);
  private static TurnTokenGenerator turnTokenGenerator = mock(TurnTokenGenerator.class);
  private static Account senderPinAccount = mock(Account.class);
  private static Account senderRegLockAccount = mock(Account.class);
  private static Account senderHasStorage = mock(Account.class);
  private static Account senderTransfer = mock(Account.class);
  private static RecaptchaClient recaptchaClient = mock(RecaptchaClient.class);
  private static GCMSender gcmSender = mock(GCMSender.class);
  // private static APNSender apnSender = mock(APNSender.class);
  private static UsernamesManager usernamesManager = mock(UsernamesManager.class);

  private static DirectoryManager directoryManager = mock(DirectoryManager.class);

  private byte[] registration_lock_key = new byte[32];

  private static LocalParametersConfiguration localParametersConfiguration = mock(LocalParametersConfiguration.class);
  private static ServiceConfiguration serviceConfiguration = new ServiceConfiguration();

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(
          ImmutableSet.of(Account.class, DisabledPermittedAccount.class)))
      .addProvider(new RateLimitExceededExceptionMapper()).setMapper(SystemMapper.getMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new AccountController(pendingAccountsManager, accountsManager, usernamesManager,
          abusiveHostRules, rateLimiters, storedMessages, turnTokenGenerator, new HashMap<>(),
          recaptchaClient, gcmSender,
          // apnSender,
          localParametersConfiguration, serviceConfiguration))
      .build();

  @BeforeEach
  void setup() throws Exception {
    clearInvocations(AuthHelper.VALID_ACCOUNT, AuthHelper.UNDISCOVERABLE_ACCOUNT);

    new SecureRandom().nextBytes(registration_lock_key);
    AuthenticationCredentials registrationLockCredentials = new AuthenticationCredentials(
        Hex.toStringCondensed(registration_lock_key));

    when(rateLimiters.getSmsDestinationLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getVerifyLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getSmsVoiceIpLimiter()).thenReturn(smsVoiceIpLimiter);
    when(rateLimiters.getAutoBlockLimiter()).thenReturn(autoBlockLimiter);
    when(rateLimiters.getUsernameSetLimiter()).thenReturn(usernameSetLimiter);

    when(senderPinAccount.getLastSeen()).thenReturn(System.currentTimeMillis());

    when(senderHasStorage.getUuid()).thenReturn(UUID.randomUUID());
    when(senderHasStorage.isStorageSupported()).thenReturn(true);    
    
    when(senderRegLockAccount.getLastSeen()).thenReturn(System.currentTimeMillis());

    when(pendingAccountsManager.getCodeForUserLogin(SENDER)).thenReturn(Optional
        .of(new StoredVerificationCode("1234", System.currentTimeMillis(), "1234-push")));
    when(pendingAccountsManager.getCodeForUserLogin(SENDER_OLD))
        .thenReturn(Optional.of(new StoredVerificationCode("1234",
            System.currentTimeMillis() - TimeUnit.HOURS.toMillis(50), null)));
    
    when(pendingAccountsManager.getCodeForUserLogin(SENDER_PREAUTH))
        .thenReturn(Optional.of(new StoredVerificationCode("555555", System.currentTimeMillis(),
            "validchallenge")));
    when(pendingAccountsManager.getCodeForUserLogin(SENDER_HAS_STORAGE)).thenReturn(Optional.of(
        new StoredVerificationCode("666666", System.currentTimeMillis(), null)));
    when(pendingAccountsManager.getCodeForUserLogin(SENDER_TRANSFER)).thenReturn(Optional
        .of(new StoredVerificationCode("1234", System.currentTimeMillis(), null)));
    
    when(accountsManager.get(eq(SENDER))).thenReturn(Optional.empty());
    when(accountsManager.get(eq(SENDER_OLD))).thenReturn(Optional.empty());
    when(accountsManager.get(eq(SENDER_PREAUTH))).thenReturn(Optional.empty());
    when(accountsManager.get(eq(SENDER_HAS_STORAGE))).thenReturn(Optional.of(senderHasStorage));
    when(accountsManager.get(eq(SENDER_TRANSFER))).thenReturn(Optional.of(senderTransfer));

    when(accountsManager.getDirectoryManager()).thenReturn(directoryManager);

    when(usernamesManager.put(eq(AuthHelper.VALID_UUID), eq("n00bkiller"))).thenReturn(true);
    when(usernamesManager.put(eq(AuthHelper.VALID_UUID), eq("takenusername"))).thenReturn(false);

    when(abusiveHostRules.getAbusiveHostRulesFor(eq(ABUSIVE_HOST))).thenReturn(Collections.singletonList(new AbusiveHostRule(ABUSIVE_HOST, true, Collections.emptyList())));
    when(abusiveHostRules.getAbusiveHostRulesFor(eq(NICE_HOST))).thenReturn(Collections.emptyList());

    when(recaptchaClient.verify(eq(INVALID_CAPTCHA_TOKEN), anyString())).thenReturn(false);
    when(recaptchaClient.verify(eq(VALID_CAPTCHA_TOKEN), anyString())).thenReturn(true);
    
    when(localParametersConfiguration.getVerificationCodeLifetime()).thenReturn(VERIFICATION_CODE_LIFETIME);
    

    doThrow(new RateLimitExceededException(RATE_LIMITED_PREFIX_HOST, Duration.ZERO)).when(autoBlockLimiter).validate(eq(RATE_LIMITED_PREFIX_HOST));
    doThrow(new RateLimitExceededException(RATE_LIMITED_IP_HOST, Duration.ZERO)).when(autoBlockLimiter).validate(eq(RATE_LIMITED_IP_HOST));

    doThrow(new RateLimitExceededException(RATE_LIMITED_IP_HOST, Duration.ZERO)).when(smsVoiceIpLimiter).validate(RATE_LIMITED_IP_HOST);
    doThrow(new RateLimitExceededException(RATE_LIMITED_HOST2, Duration.ZERO)).when(smsVoiceIpLimiter).validate(RATE_LIMITED_HOST2);
  }

  @AfterEach
  void teardown() {
    reset(
        pendingAccountsManager,
        accountsManager,
        abusiveHostRules,
        rateLimiters,
        rateLimiter,
        pinLimiter,
        smsVoiceIpLimiter,
        autoBlockLimiter,
        usernameSetLimiter,
        storedMessages,
        turnTokenGenerator,
        senderPinAccount,
        senderRegLockAccount,
        senderHasStorage,
        senderTransfer,
        recaptchaClient,
        gcmSender,
        // apnSender,
        usernamesManager);
    
    clearInvocations(AuthHelper.DISABLED_DEVICE);
  }

  @Test
  void testGetFcmPreauth() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/accounts/fcm/preauth/mytoken/richardroe").request()
        .get();

    assertThat(response.getStatus()).isEqualTo(200);

    ArgumentCaptor<GcmMessage> captor = ArgumentCaptor.forClass(GcmMessage.class);

    verify(gcmSender, times(1)).sendMessage(captor.capture());
    assertThat(captor.getValue().getGcmId()).isEqualTo("mytoken");
    assertThat(captor.getValue().getData().isPresent()).isTrue();
    assertThat(captor.getValue().getData().get().length()).isEqualTo(32);

//    verifyNoMoreInteractions(apnSender);
  }

  @Test
  @Disabled
  // until we support iOS
  void testGetApnPreauth() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/accounts/apn/preauth/mytoken/richardroe").request()
        .get();

    assertThat(response.getStatus()).isEqualTo(200);

    ArgumentCaptor<ApnMessage> captor = ArgumentCaptor.forClass(ApnMessage.class);

//    verify(apnSender, times(1)).sendMessage(captor.capture());
    assertThat(captor.getValue().getApnId()).isEqualTo("mytoken");
    assertThat(captor.getValue().getChallengeData().isPresent()).isTrue();
    assertThat(captor.getValue().getChallengeData().get().length()).isEqualTo(32);
    assertThat(captor.getValue().getMessage())
        .contains("\"challenge\" : \"" + captor.getValue().getChallengeData().get() + "\"");
    assertThat(captor.getValue().isVoip()).isTrue();

    verifyNoMoreInteractions(gcmSender);
  }

  @Test
  @Disabled
  // until we support iOS
  void testGetApnPreauthExplicitVoip() throws Exception {
    Response response = resources.getJerseyTest()
        .target("/v1/accounts/apn/preauth/mytoken/+14152222222")
        .queryParam("voip", "true")
        .request()
        .get();

    assertThat(response.getStatus()).isEqualTo(200);

    ArgumentCaptor<ApnMessage> captor = ArgumentCaptor.forClass(ApnMessage.class);

    // verify(apnSender, times(1)).sendMessage(captor.capture());
    assertThat(captor.getValue().getApnId()).isEqualTo("mytoken");
    assertThat(captor.getValue().getChallengeData().isPresent()).isTrue();
    assertThat(captor.getValue().getChallengeData().get().length()).isEqualTo(32);
    assertThat(captor.getValue().getMessage()).contains("\"challenge\" : \"" + captor.getValue().getChallengeData().get() + "\"");
    assertThat(captor.getValue().isVoip()).isTrue();

    verifyNoMoreInteractions(gcmSender);
  }

  @Test
  @Disabled
  // until we support iOS
  void testGetApnPreauthExplicitNoVoip() throws Exception {
    Response response = resources.getJerseyTest()
        .target("/v1/accounts/apn/preauth/mytoken/+14152222222")
        .queryParam("voip", "false")
        .request()
        .get();

    assertThat(response.getStatus()).isEqualTo(200);

    ArgumentCaptor<ApnMessage> captor = ArgumentCaptor.forClass(ApnMessage.class);

    // verify(apnSender, times(1)).sendMessage(captor.capture());
    assertThat(captor.getValue().getApnId()).isEqualTo("mytoken");
    assertThat(captor.getValue().getChallengeData().isPresent()).isTrue();
    assertThat(captor.getValue().getChallengeData().get().length()).isEqualTo(32);
    assertThat(captor.getValue().getMessage()).contains("\"challenge\" : \"" + captor.getValue().getChallengeData().get() + "\"");
    assertThat(captor.getValue().isVoip()).isFalse();

    verifyNoMoreInteractions(gcmSender);
  }

  @Test
  void testSendCode() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("challenge", "1234-push")
        .request().header("X-Forwarded-For", NICE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(200);

    verify(abusiveHostRules).getAbusiveHostRulesFor(eq(NICE_HOST));
  }

  @Test
  void testSendCodeWithValidPreauth() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER_PREAUTH))
        .queryParam("challenge", "validchallenge").request().header("X-Forwarded-For", NICE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(200);

    verify(abusiveHostRules).getAbusiveHostRulesFor(eq(NICE_HOST));
  }

  @Test
  void testSendCodeWithInvalidPreauth() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER_PREAUTH))
        .queryParam("challenge", "invalidchallenge").request().header("X-Forwarded-For", NICE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(402);

    verifyNoMoreInteractions(abusiveHostRules);
  }

  @Test  
  // these ones are allowed through to the abuse check
  void testSendCodeWithNoPreauth() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER_PREAUTH))
        .request().header("X-Forwarded-For", NICE_HOST).get();

    // 402 to 200
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testSendiOSCode() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("client", "ios").queryParam("challenge", "1234-push").request().header("X-Forwarded-For", NICE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testSendAndroidNgCode() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("client", "android-ng").queryParam("challenge", "1234-push").request().header("X-Forwarded-For", NICE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testSendAbusiveHost() {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("challenge", "1234-push")
        .request().header("X-Forwarded-For", ABUSIVE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(402);

    verify(abusiveHostRules).getAbusiveHostRulesFor(eq(ABUSIVE_HOST));
  }

  @Test
  void testSendAbusiveHostWithValidCaptcha() throws IOException {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("captcha", VALID_CAPTCHA_TOKEN).request().header("X-Forwarded-For", ABUSIVE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(200);

    verifyNoMoreInteractions(abusiveHostRules);
    verify(recaptchaClient).verify(eq(VALID_CAPTCHA_TOKEN), eq(ABUSIVE_HOST));

  }

  @Test  
  void testSendAbusiveHostWithInvalidCaptcha() {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("captcha", INVALID_CAPTCHA_TOKEN).request().header("X-Forwarded-For", ABUSIVE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(402);

    verifyNoMoreInteractions(abusiveHostRules);
    verify(recaptchaClient).verify(eq(INVALID_CAPTCHA_TOKEN), eq(ABUSIVE_HOST));
  }

  @Test
  void testSendRateLimitedHostAutoBlock() {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("challenge", "1234-push")
        .request().header("X-Forwarded-For", RATE_LIMITED_IP_HOST).get();

    assertThat(response.getStatus()).isEqualTo(402);

    verify(abusiveHostRules).getAbusiveHostRulesFor(eq(RATE_LIMITED_IP_HOST));
    verify(abusiveHostRules).setBlockedHost(eq(RATE_LIMITED_IP_HOST), eq("Auto-Block"));
    verifyNoMoreInteractions(abusiveHostRules);

    verifyNoMoreInteractions(recaptchaClient);
  }

  @Test
  void testSendRateLimitedHostNoAutoBlock() {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("challenge", "1234-push")
        .request().header("X-Forwarded-For", RATE_LIMITED_HOST2).get();

    assertThat(response.getStatus()).isEqualTo(402);

    verify(abusiveHostRules).getAbusiveHostRulesFor(eq(RATE_LIMITED_HOST2));
    verifyNoMoreInteractions(abusiveHostRules);

    verifyNoMoreInteractions(recaptchaClient);
  }

  @Test
  void testSendMultipleHost() {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/sms/code/%s", SENDER))
        .queryParam("challenge", "1234-push")
        .request().header("X-Forwarded-For", NICE_HOST + ", " + ABUSIVE_HOST).get();

    assertThat(response.getStatus()).isEqualTo(402);

    verify(abusiveHostRules, times(1)).getAbusiveHostRulesFor(eq(ABUSIVE_HOST));

    verifyNoMoreInteractions(abusiveHostRules);
  }

  @Test
  void testVerifyCode() throws Exception {
    AccountCreationResult result = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "1234"))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(SENDER, "bar"))
        .put(Entity.entity(new AccountAttributes(false, 2222, null, true, null),
            MediaType.APPLICATION_JSON_TYPE), AccountCreationResult.class);

    assertThat(result.getUuid()).isNotNull();
    assertThat(result.isStorageCapable()).isFalse();

    final ArgumentCaptor<Account> accountArgumentCaptor = ArgumentCaptor.forClass(Account.class);

    verify(accountsManager, times(1)).create(accountArgumentCaptor.capture());

    assertThat(accountArgumentCaptor.getValue().isDiscoverableByUserLogin()).isTrue();
  }

  @Test
  void testVerifyCodeUndiscoverable() throws Exception {
    AccountCreationResult result = resources.getJerseyTest()
        .target(String.format("/v1/accounts/code/%s", "1234"))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(SENDER, "bar"))
        .put(Entity.entity(new AccountAttributes(false, 2222, null, false, null),
            MediaType.APPLICATION_JSON_TYPE), AccountCreationResult.class);

    assertThat(result.getUuid()).isNotNull();
    assertThat(result.isStorageCapable()).isFalse();

    final ArgumentCaptor<Account> accountArgumentCaptor = ArgumentCaptor.forClass(Account.class);

    verify(accountsManager, times(1)).create(accountArgumentCaptor.capture());

    assertThat(accountArgumentCaptor.getValue().isDiscoverableByUserLogin()).isFalse();
  }

  @Test
  void testVerifySupportsStorage() throws Exception {
    AccountCreationResult result = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "666666"))
        .request().header("Authorization", AuthHelper.getAuthHeader(SENDER_HAS_STORAGE, "bar"))
        .put(Entity.entity(new AccountAttributes(false, 2222, null, true, null),
            MediaType.APPLICATION_JSON_TYPE), AccountCreationResult.class);

    assertThat(result.getUuid()).isNotNull();
    assertThat(result.isStorageCapable()).isTrue();

    verify(accountsManager, times(1)).create(isA(Account.class));
  }

  @Test
  void testVerifyCodeOld() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "1234")).request()
        .header("Authorization", AuthHelper.getAuthHeader(SENDER_OLD, "bar")).put(Entity.entity(new AccountAttributes(false, 2222, null, true, null), MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(403);

    verifyNoMoreInteractions(accountsManager);
  }

  @Test
  void testVerifyBadCode() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "1111")).request()
        .header("Authorization", AuthHelper.getAuthHeader(SENDER, "bar")).put(Entity.entity(new AccountAttributes(false, 3333, null, true, null), MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(403);

    verifyNoMoreInteractions(accountsManager);
  }  

  @Test
  void testVerifyTransferSupported() {
    when(senderTransfer.isTransferSupported()).thenReturn(true);

    final Response response = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "1234"))
        .queryParam("transfer", true).request()
        .header("Authorization", AuthHelper.getAuthHeader(SENDER_TRANSFER, "bar")).put(Entity.entity(new AccountAttributes(false, 2222, null, true, null), MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(409);
  }

  @Test
  void testVerifyTransferNotSupported() {
    when(senderTransfer.isTransferSupported()).thenReturn(false);

    final Response response = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "1234"))
        .queryParam("transfer", true).request()
        .header("Authorization", AuthHelper.getAuthHeader(SENDER_TRANSFER, "bar")).put(Entity.entity(new AccountAttributes(false, 2222, null, true, null), MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testVerifyTransferSupportedNotRequested() {
    when(senderTransfer.isTransferSupported()).thenReturn(true);

    final Response response = resources.getJerseyTest().target(String.format("/v1/accounts/code/%s", "1234"))
        .request().header("Authorization", AuthHelper.getAuthHeader(SENDER_TRANSFER, "bar")).put(Entity.entity(new AccountAttributes(false, 2222, null, true, null), MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(200);
  }  

  @Test
  void testSetGcmId() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/accounts/gcm/").request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
        .put(Entity.json(new GcmRegistrationId("c00lz0rz")));

    assertThat(response.getStatus()).isEqualTo(204);

    verify(AuthHelper.DISABLED_DEVICE, times(1)).setGcmId(eq("c00lz0rz"));
    verify(accountsManager, times(1)).update(eq(AuthHelper.DISABLED_ACCOUNT));
  }

  @Test
  void testSetGcmIdByUuid() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/accounts/gcm/").request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.DISABLED_UUID.toString(), AuthHelper.DISABLED_PASSWORD))
        .put(Entity.json(new GcmRegistrationId("z000")));

    assertThat(response.getStatus()).isEqualTo(204);

    verify(AuthHelper.DISABLED_DEVICE, times(1)).setGcmId(eq("z000"));
    verify(accountsManager, times(1)).update(eq(AuthHelper.DISABLED_ACCOUNT));
  }

  @Test
  void testSetApnId() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/accounts/apn/").request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
        .put(Entity.json(new ApnRegistrationId("first", "second")));

    assertThat(response.getStatus()).isEqualTo(204);

    verify(AuthHelper.DISABLED_DEVICE, times(1)).setApnId(eq("first"));
    verify(AuthHelper.DISABLED_DEVICE, times(1)).setVoipApnId(eq("second"));
    verify(accountsManager, times(1)).update(eq(AuthHelper.DISABLED_ACCOUNT));
  }
  
  @Test
  void testSetApnIdNoVoip() throws Exception {
    Response response =
        resources.getJerseyTest()
            .target("/v1/accounts/apn/")
            .request()
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
            .put(Entity.json(new ApnRegistrationId("first", null)));

    assertThat(response.getStatus()).isEqualTo(204);

    verify(AuthHelper.DISABLED_DEVICE, times(1)).setApnId(eq("first"));
    verify(AuthHelper.DISABLED_DEVICE, times(1)).setVoipApnId(null);
    verify(accountsManager, times(1)).update(eq(AuthHelper.DISABLED_ACCOUNT));
  }


  @Test
  void testSetApnIdByUuid() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/accounts/apn/").request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.DISABLED_UUID.toString(), AuthHelper.DISABLED_PASSWORD))
        .put(Entity.json(new ApnRegistrationId("third", "fourth")));

    assertThat(response.getStatus()).isEqualTo(204);

    verify(AuthHelper.DISABLED_DEVICE, times(1)).setApnId(eq("third"));
    verify(AuthHelper.DISABLED_DEVICE, times(1)).setVoipApnId(eq("fourth"));
    verify(accountsManager, times(1)).update(eq(AuthHelper.DISABLED_ACCOUNT));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/v1/accounts/whoami/", "/v1/accounts/me/"})
  void testWhoAmI(final String path) {
    AccountCreationResult response = resources.getJerseyTest().target(path).request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .get(AccountCreationResult.class);

    assertThat(response.getUuid()).isEqualTo(AuthHelper.VALID_UUID);
  }

  @Test
  void testSetUsername() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/n00bkiller").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.text(""));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testSetTakenUsername() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/takenusername").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.text(""));

    assertThat(response.getStatus()).isEqualTo(409);
  }

  @Test
  void testSetInvalidUsername() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/pаypal").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.text(""));

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testSetInvalidPrefixUsername() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/0n00bkiller").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.text(""));

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testSetUsernameBadAuth() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/n00bkiller").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.INVALID_PASSWORD))
        .put(Entity.text(""));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testDeleteUsername() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .delete();

    assertThat(response.getStatus()).isEqualTo(204);
    verify(usernamesManager, times(1)).delete(eq(AuthHelper.VALID_UUID));
  }

  @Test
  void testDeleteUsernameBadAuth() {
    Response response = resources.getJerseyTest().target("/v1/accounts/username/").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.INVALID_PASSWORD))
        .delete();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testSetAccountAttributes() {
    Response response = resources.getJerseyTest()
        .target("/v1/accounts/attributes/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.json(new AccountAttributes(false, 2222, null, true, null)));

    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void testDeleteAccount() {
    Response response = resources.getJerseyTest()
        .target("/v1/accounts/me")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .delete();

    assertThat(response.getStatus()).isEqualTo(204);
    verify(accountsManager).delete(Stream.of(AuthHelper.VALID_ACCOUNT).collect(Collectors.toCollection(HashSet::new)), AccountsManager.DeletionReason.USER_REQUEST);
  }

}