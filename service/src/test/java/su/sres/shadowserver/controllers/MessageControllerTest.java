/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.vdurmont.semver4j.Semver;

import org.assertj.core.api.Assertions;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.stubbing.Answer;

import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.OptionalAccess;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicMessageRateConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicRateLimitChallengeConfiguration;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import su.sres.shadowserver.entities.IncomingMessageList;
import su.sres.shadowserver.entities.MismatchedDevices;
import su.sres.shadowserver.entities.OutgoingMessageEntity;
import su.sres.shadowserver.entities.OutgoingMessageEntityList;
import su.sres.shadowserver.entities.RateLimitChallenge;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.entities.StaleDevices;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
import su.sres.shadowserver.limits.CardinalityRateLimiter;
import su.sres.shadowserver.limits.RateLimitChallengeManager;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.limits.UnsealedSenderRateLimiter;
import su.sres.shadowserver.mappers.RateLimitChallengeExceptionMapper;
import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.MessageSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.ReportMessageManager;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.ua.ClientPlatform;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static su.sres.shadowserver.util.JsonHelpers.asJson;
import static su.sres.shadowserver.util.JsonHelpers.jsonFixture;

@ExtendWith(DropwizardExtensionsSupport.class)
class MessageControllerTest {

  private static final String SINGLE_DEVICE_RECIPIENT = "+14151111111";
  private static final UUID SINGLE_DEVICE_UUID = UUID.randomUUID();

  private static final String MULTI_DEVICE_RECIPIENT = "+14152222222";
  private static final UUID MULTI_DEVICE_UUID = UUID.randomUUID();

  private static final String INTERNATIONAL_RECIPIENT = "+61123456789";
  private static final UUID INTERNATIONAL_UUID = UUID.randomUUID();

  private Account internationalAccount;

  @SuppressWarnings("unchecked")
  private static final RedisAdvancedClusterCommands<String, String> redisCommands = mock(RedisAdvancedClusterCommands.class);

  private static final MessageSender messageSender = mock(MessageSender.class);
  private static final ReceiptSender receiptSender = mock(ReceiptSender.class);
  private static final AccountsManager accountsManager = mock(AccountsManager.class);
  private static final MessagesManager messagesManager = mock(MessagesManager.class);
  private static final RateLimiters rateLimiters = mock(RateLimiters.class);
  private static final RateLimiter rateLimiter = mock(RateLimiter.class);
  private static final CardinalityRateLimiter unsealedSenderLimiter = mock(CardinalityRateLimiter.class);
  private static final UnsealedSenderRateLimiter unsealedSenderRateLimiter = mock(UnsealedSenderRateLimiter.class);
  private static final ApnFallbackManager apnFallbackManager = mock(ApnFallbackManager.class);
  private static final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);
  private static final RateLimitChallengeManager rateLimitChallengeManager = mock(RateLimitChallengeManager.class);
  private static final ReportMessageManager reportMessageManager = mock(ReportMessageManager.class);
  private static final FaultTolerantRedisCluster metricsCluster = mock(FaultTolerantRedisCluster.class);
  private static final ScheduledExecutorService receiptExecutor = mock(ScheduledExecutorService.class);

  private final ObjectMapper mapper = new ObjectMapper();

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(
          ImmutableSet.of(Account.class, DisabledPermittedAccount.class)))
      .addProvider(new RateLimitChallengeExceptionMapper(rateLimitChallengeManager))
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new MessageController(rateLimiters, messageSender, receiptSender, accountsManager, messagesManager, unsealedSenderRateLimiter, apnFallbackManager, dynamicConfiguration, rateLimitChallengeManager, reportMessageManager, metricsCluster, receiptExecutor))
      .build();

  @BeforeEach
  void setup() throws Exception {
    Set<Device> singleDeviceList = new HashSet<Device>() {
      {
        add(new Device(1, null, "foo", "bar", "isgcm", null, null, false, 111, new SignedPreKey(333, "baz", "boop"), System.currentTimeMillis(), System.currentTimeMillis(), "Test", 0, new Device.DeviceCapabilities(true, false, false, true, true, false,
            false, false)));
      }
    };

    Set<Device> multiDeviceList = new HashSet<Device>() {
      {
        add(new Device(1, null, "foo", "bar", "isgcm", null, null, false, 222, new SignedPreKey(111, "foo", "bar"), System.currentTimeMillis(), System.currentTimeMillis(), "Test", 0, new Device.DeviceCapabilities(true, false, false, true, false, false,
            false, false)));
        add(new Device(2, null, "foo", "bar", "isgcm", null, null, false, 333, new SignedPreKey(222, "oof", "rab"), System.currentTimeMillis(), System.currentTimeMillis(), "Test", 0, new Device.DeviceCapabilities(true, false, false, true, false, false,
            false, false)));
        add(new Device(3, null, "foo", "bar", "isgcm", null, null, false, 444, null, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31), System.currentTimeMillis(), "Test", 0, new Device.DeviceCapabilities(false, false, false, false, false, false,
            false, false)));
      }
    };

    Account singleDeviceAccount = new Account(SINGLE_DEVICE_RECIPIENT, SINGLE_DEVICE_UUID, singleDeviceList,
        "1234".getBytes());
    Account multiDeviceAccount = new Account(MULTI_DEVICE_RECIPIENT, MULTI_DEVICE_UUID, multiDeviceList,
        "1234".getBytes());

    internationalAccount = new Account(INTERNATIONAL_RECIPIENT, INTERNATIONAL_UUID, singleDeviceList, "1234".getBytes());

    when(accountsManager.get(eq(SINGLE_DEVICE_RECIPIENT))).thenReturn(Optional.of(singleDeviceAccount));
    when(accountsManager.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
        && identifier.hasUserLogin() && identifier.getUserLogin().equals(SINGLE_DEVICE_RECIPIENT))))
            .thenReturn(Optional.of(singleDeviceAccount));
    when(accountsManager.get(eq(MULTI_DEVICE_RECIPIENT))).thenReturn(Optional.of(multiDeviceAccount));
    when(accountsManager.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null
        && identifier.hasUserLogin() && identifier.getUserLogin().equals(MULTI_DEVICE_RECIPIENT))))
            .thenReturn(Optional.of(multiDeviceAccount));
    when(accountsManager.get(INTERNATIONAL_RECIPIENT)).thenReturn(Optional.of(internationalAccount));
    when(accountsManager.get(argThat((ArgumentMatcher<AmbiguousIdentifier>) identifier -> identifier != null && identifier.hasUserLogin() && identifier.getUserLogin().equals(INTERNATIONAL_RECIPIENT)))).thenReturn(Optional.of(internationalAccount));

    when(rateLimiters.getMessagesLimiter()).thenReturn(rateLimiter);

    when(receiptExecutor.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(
        (Answer<ScheduledFuture<?>>) invocation -> {
          invocation.getArgument(0, Runnable.class).run();
          return mock(ScheduledFuture.class);
        });
  }

  @AfterEach
  void teardown() {
    reset(
        redisCommands,
        messageSender,
        receiptSender,
        accountsManager,
        messagesManager,
        rateLimiters,
        rateLimiter,
        unsealedSenderRateLimiter,
        apnFallbackManager,
        dynamicConfiguration,
        rateLimitChallengeManager,
        reportMessageManager,
        metricsCluster,
        receiptExecutor);
  }

  @Test
  void testSendFromDisabledAccount() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", SINGLE_DEVICE_RECIPIENT))
        .request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Unauthorized response", response.getStatus(), is(equalTo(401)));
  }

  @Test
  void testSingleDeviceCurrent() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", SINGLE_DEVICE_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response", response.getStatus(), is(equalTo(200)));

    ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
    verify(messageSender, times(1)).sendMessage(any(Account.class), any(Device.class), captor.capture(), eq(false));

    assertTrue(captor.getValue().hasSource());
    assertTrue(captor.getValue().hasSourceDevice());
  }

  @ParameterizedTest
  @CsvSource({ "true, 5.1.0, 429", "true, 5.6.4, 428", "false, 5.6.4, 200" })
  void testUnsealedSenderCardinalityRateLimited(final boolean rateLimited, final String clientVersion, final int expectedStatusCode) throws Exception {
    final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);
    final DynamicMessageRateConfiguration messageRateConfiguration = mock(DynamicMessageRateConfiguration.class);

    when(dynamicConfiguration.getMessageRateConfiguration()).thenReturn(messageRateConfiguration);
    when(messageRateConfiguration.isEnforceUnsealedSenderRateLimit()).thenReturn(true);
    when(messageRateConfiguration.getResponseDelay()).thenReturn(Duration.ofMillis(1));
    when(messageRateConfiguration.getResponseDelayJitter()).thenReturn(Duration.ofMillis(1));
    when(messageRateConfiguration.getReceiptDelay()).thenReturn(Duration.ofMillis(1));
    when(messageRateConfiguration.getReceiptDelayJitter()).thenReturn(Duration.ofMillis(1));
    when(messageRateConfiguration.getReceiptProbability()).thenReturn(1.0);

    DynamicRateLimitChallengeConfiguration dynamicRateLimitChallengeConfiguration = mock(
        DynamicRateLimitChallengeConfiguration.class);
    when(dynamicConfiguration.getRateLimitChallengeConfiguration())
        .thenReturn(dynamicRateLimitChallengeConfiguration);

    when(dynamicRateLimitChallengeConfiguration.getMinimumSupportedVersion(any())).thenReturn(Optional.empty());
    when(dynamicRateLimitChallengeConfiguration.getMinimumSupportedVersion(ClientPlatform.ANDROID))
        .thenReturn(Optional.of(new Semver("5.5.0")));

    when(redisCommands.evalsha(any(), any(), any(), any())).thenReturn(List.of(1L, 1L));
    
    if (rateLimited) {
      doThrow(new RateLimitExceededException(Duration.ofHours(1))).when(unsealedSenderRateLimiter).validate(eq(AuthHelper.VALID_ACCOUNT), eq(internationalAccount));

      when(rateLimitChallengeManager.shouldIssueRateLimitChallenge(String.format("Shadow-Android/%s Android/30", clientVersion)))
          .thenReturn(true);      
    }

    Response response = resources.getJerseyTest()
        .target(String.format("/v1/messages/%s", INTERNATIONAL_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .header("User-Agent", "Shadow-Android/5.6.4 Android/30")
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"), IncomingMessageList.class),
            MediaType.APPLICATION_JSON_TYPE));

    if (rateLimited) {
      assertThat("Error Response", response.getStatus(), is(equalTo(expectedStatusCode)));
    } else {
      assertThat("Good Response", response.getStatus(), is(equalTo(expectedStatusCode)));
    }

    verify(messageSender, rateLimited ? never() : times(1)).sendMessage(any(), any(), any(), anyBoolean());
  }

  @Test
  void testRateLimitResetRequirement() throws Exception {

    Duration retryAfter = Duration.ofMinutes(1);
    doThrow(new RateLimitExceededException(retryAfter))
        .when(unsealedSenderRateLimiter).validate(any(), any());

    when(rateLimitChallengeManager.shouldIssueRateLimitChallenge("Shadow-Android/5.1.2 Android/30")).thenReturn(true);
    when(rateLimitChallengeManager.getChallengeOptions(AuthHelper.VALID_ACCOUNT))
        .thenReturn(List.of(RateLimitChallengeManager.OPTION_PUSH_CHALLENGE, RateLimitChallengeManager.OPTION_RECAPTCHA));

    Response response = resources.getJerseyTest()
        .target(String.format("/v1/messages/%s", INTERNATIONAL_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .header("User-Agent", "Shadow-Android/5.1.2 Android/30")
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"), IncomingMessageList.class),
            MediaType.APPLICATION_JSON_TYPE));

    assertEquals(428, response.getStatus());

    RateLimitChallenge rateLimitChallenge = response.readEntity(RateLimitChallenge.class);

    assertFalse(rateLimitChallenge.getToken().isBlank());
    assertFalse(rateLimitChallenge.getOptions().isEmpty());
    assertTrue(rateLimitChallenge.getOptions().contains("recaptcha"));
    assertTrue(rateLimitChallenge.getOptions().contains("pushChallenge"));
    assertEquals(retryAfter.toSeconds(), Long.parseLong(response.getHeaderString("Retry-After")));
  }

  @Test
  void testSingleDeviceCurrentUnidentified() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", SINGLE_DEVICE_RECIPIENT))
        .request().header(OptionalAccess.UNIDENTIFIED, Base64.getEncoder().encodeToString("1234".getBytes()))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response", response.getStatus(), is(equalTo(200)));

    ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
    verify(messageSender, times(1)).sendMessage(any(Account.class), any(Device.class), captor.capture(), eq(false));

    assertFalse(captor.getValue().hasSource());
    assertFalse(captor.getValue().hasSourceDevice());
  }

  @Test
  void testSendBadAuth() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", SINGLE_DEVICE_RECIPIENT))
        .request()
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response", response.getStatus(), is(equalTo(401)));
  }

  @Test
  void testMultiDeviceMissing() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response Code", response.getStatus(), is(equalTo(409)));

    assertThat("Good Response Body", asJson(response.readEntity(MismatchedDevices.class)),
        is(equalTo(jsonFixture("fixtures/missing_device_response.json"))));

    verifyNoMoreInteractions(messageSender);
  }

  @Test
  void testMultiDeviceExtra() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_extra_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response Code", response.getStatus(), is(equalTo(409)));

    assertThat("Good Response Body", asJson(response.readEntity(MismatchedDevices.class)),
        is(equalTo(jsonFixture("fixtures/missing_device_response2.json"))));

    verifyNoMoreInteractions(messageSender);
  }

  @Test
  void testMultiDevice() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_multi_device.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response Code", response.getStatus(), is(equalTo(200)));

    verify(messageSender, times(2)).sendMessage(any(Account.class), any(Device.class), any(Envelope.class), eq(false));
  }

  @Test
  void testRegistrationIdMismatch() throws Exception {
    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(mapper.readValue(jsonFixture("fixtures/current_message_registration_id.json"),
            IncomingMessageList.class), MediaType.APPLICATION_JSON_TYPE));

    assertThat("Good Response Code", response.getStatus(), is(equalTo(410)));

    assertThat("Good Response Body", asJson(response.readEntity(StaleDevices.class)),
        is(equalTo(jsonFixture("fixtures/mismatched_registration_id.json"))));

    verifyNoMoreInteractions(messageSender);

  }

  @Test
  void testGetMessages() throws Exception {

    final long timestampOne = 313377;
    final long timestampTwo = 313388;

    final UUID messageGuidOne = UUID.randomUUID();
    final UUID sourceUuid = UUID.randomUUID();

    List<OutgoingMessageEntity> messages = new LinkedList<>() {
      {
        add(new OutgoingMessageEntity(1L, false, messageGuidOne, Envelope.Type.CIPHERTEXT_VALUE, null,
            timestampOne, "+14152222222", sourceUuid, 2, "hi there".getBytes(), null, 0));
        add(new OutgoingMessageEntity(2L, false, null, Envelope.Type.SERVER_DELIVERY_RECEIPT_VALUE, null, timestampTwo,
            "+14152222222", sourceUuid, 2, null, null, 0));
      }
    };

    OutgoingMessageEntityList messagesList = new OutgoingMessageEntityList(messages, false);

    when(messagesManager.getMessagesForDevice(eq(AuthHelper.VALID_UUID), eq(1L), anyString(), anyBoolean())).thenReturn(messagesList);

    OutgoingMessageEntityList response = resources.getJerseyTest().target("/v1/messages/").request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.VALID_UUID.toString(), AuthHelper.VALID_PASSWORD))
        .accept(MediaType.APPLICATION_JSON_TYPE).get(OutgoingMessageEntityList.class);

    assertEquals(response.getMessages().size(), 2);

    assertEquals(response.getMessages().get(0).getId(), 0);
    assertEquals(response.getMessages().get(1).getId(), 0);

    assertEquals(response.getMessages().get(0).getTimestamp(), timestampOne);
    assertEquals(response.getMessages().get(1).getTimestamp(), timestampTwo);

    assertEquals(response.getMessages().get(0).getGuid(), messageGuidOne);
    assertNull(response.getMessages().get(1).getGuid());

    assertEquals(response.getMessages().get(0).getSourceUuid(), sourceUuid);
    assertEquals(response.getMessages().get(1).getSourceUuid(), sourceUuid);
  }

  @Test
  void testGetMessagesBadAuth() throws Exception {
    final long timestampOne = 313377;
    final long timestampTwo = 313388;

    List<OutgoingMessageEntity> messages = new LinkedList<OutgoingMessageEntity>() {
      {
        add(new OutgoingMessageEntity(1L, false, UUID.randomUUID(), Envelope.Type.CIPHERTEXT_VALUE, null,
            timestampOne, "+14152222222", UUID.randomUUID(), 2, "hi there".getBytes(), null, 0));
        add(new OutgoingMessageEntity(2L, false, UUID.randomUUID(), Envelope.Type.SERVER_DELIVERY_RECEIPT_VALUE, null,
            timestampTwo, "+14152222222", UUID.randomUUID(), 2, null, null, 0));
      }
    };

    OutgoingMessageEntityList messagesList = new OutgoingMessageEntityList(messages, false);

    when(messagesManager.getMessagesForDevice(eq(AuthHelper.VALID_UUID), eq(1L), anyString(), anyBoolean())).thenReturn(messagesList);

    Response response = resources.getJerseyTest().target("/v1/messages/").request()
        .header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.VALID_UUID.toString(), AuthHelper.INVALID_PASSWORD))
        .accept(MediaType.APPLICATION_JSON_TYPE).get();

    assertThat("Unauthorized response", response.getStatus(), is(equalTo(401)));
  }

  @Test
  void testDeleteMessages() throws Exception {
    long timestamp = System.currentTimeMillis();

    UUID sourceUuid = UUID.randomUUID();

    when(messagesManager.delete(AuthHelper.VALID_UUID, 1, "+14152222222", 31337))
        .thenReturn(Optional.of(new OutgoingMessageEntity(31337L, true, null, Envelope.Type.CIPHERTEXT_VALUE,
            null, timestamp, "+14152222222", sourceUuid, 1, "hi".getBytes(), null, 0)));

    when(messagesManager.delete(AuthHelper.VALID_UUID, 1, "+14152222222", 31338))
        .thenReturn(Optional.of(new OutgoingMessageEntity(31337L, true, null, Envelope.Type.SERVER_DELIVERY_RECEIPT_VALUE, null,
            System.currentTimeMillis(), "+14152222222", sourceUuid, 1, null, null, 0)));

    when(messagesManager.delete(AuthHelper.VALID_UUID, 1, "+14152222222", 31339))
        .thenReturn(Optional.empty());

    Response response = resources.getJerseyTest().target(String.format("/v1/messages/%s/%d", "+14152222222", 31337))
        .request().header("Authorization",
            AuthHelper.getAuthHeader(AuthHelper.VALID_UUID.toString(), AuthHelper.VALID_PASSWORD))
        .delete();

    assertThat("Good Response Code", response.getStatus(), is(equalTo(204)));
    verify(receiptSender).sendReceipt(any(Account.class), eq("+14152222222"), eq(timestamp));

    response = resources.getJerseyTest().target(String.format("/v1/messages/%s/%d", "+14152222222", 31338))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .delete();

    assertThat("Good Response Code", response.getStatus(), is(equalTo(204)));
    verifyNoMoreInteractions(receiptSender);

    response = resources.getJerseyTest().target(String.format("/v1/messages/%s/%d", "+14152222222", 31339))
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .delete();

    assertThat("Good Response Code", response.getStatus(), is(equalTo(204)));
    verifyNoMoreInteractions(receiptSender);

  }  
  
  @Test
  void testReportMessage() {

    final String senderUserLogin = "+12125550001";
    final UUID messageGuid = UUID.randomUUID();

    final Response response =
        resources.getJerseyTest()
            .target(String.format("/v1/messages/report/%s/%s", senderUserLogin, messageGuid))
            .request()
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .post(null);

    assertThat(response.getStatus(), is(equalTo(202)));

    verify(reportMessageManager).report(senderUserLogin, messageGuid);
  }
  
  static Account mockAccountWithDeviceAndRegId(Object... deviceAndRegistrationIds) {
    Account account = mock(Account.class);
    if (deviceAndRegistrationIds.length % 2 != 0) {
      throw new IllegalArgumentException("invalid number of arguments specified; must be even");
    }
    for (int i = 0; i < deviceAndRegistrationIds.length; i+=2) {
      if (!(deviceAndRegistrationIds[i] instanceof Long)) {
        throw new IllegalArgumentException("device id is not instance of long at index " + i);
      }
      if (!(deviceAndRegistrationIds[i + 1] instanceof Integer)) {
        throw new IllegalArgumentException("registration id is not instance of integer at index " + (i + 1));
      }
      Long deviceId = (Long) deviceAndRegistrationIds[i];
      Integer registrationId = (Integer) deviceAndRegistrationIds[i + 1];
      Device device = mock(Device.class);
      when(device.getRegistrationId()).thenReturn(registrationId);
      when(account.getDevice(deviceId)).thenReturn(Optional.of(device));
    }
    return account;
  }

  static Collection<Pair<Long, Integer>> deviceAndRegistrationIds(Object... deviceAndRegistrationIds) {
    final Collection<Pair<Long, Integer>> result = new HashSet<>(deviceAndRegistrationIds.length);
    if (deviceAndRegistrationIds.length % 2 != 0) {
      throw new IllegalArgumentException("invalid number of arguments specified; must be even");
    }
    for (int i = 0; i < deviceAndRegistrationIds.length; i += 2) {
      if (!(deviceAndRegistrationIds[i] instanceof Long)) {
        throw new IllegalArgumentException("device id is not instance of long at index " + i);
      }
      if (!(deviceAndRegistrationIds[i + 1] instanceof Integer)) {
        throw new IllegalArgumentException("registration id is not instance of integer at index " + (i + 1));
      }
      Long deviceId = (Long) deviceAndRegistrationIds[i];
      Integer registrationId = (Integer) deviceAndRegistrationIds[i + 1];
      result.add(new Pair<>(deviceId, registrationId));
    }
    return result;
  }

  static Stream<Arguments> validateRegistrationIdsSource() {
    return Stream.of(
        arguments(
            mockAccountWithDeviceAndRegId(1L, 0xFFFF, 2L, 0xDEAD, 3L, 0xBEEF),
            deviceAndRegistrationIds(1L, 0xFFFF, 2L, 0xDEAD, 3L, 0xBEEF),
            null),
        arguments(
            mockAccountWithDeviceAndRegId(1L, 42),
            deviceAndRegistrationIds(1L, 1492),
            Set.of(1L)),
        arguments(
            mockAccountWithDeviceAndRegId(1L, 42),
            deviceAndRegistrationIds(1L, 42),
            null),
        arguments(
            mockAccountWithDeviceAndRegId(1L, 42),
            deviceAndRegistrationIds(1L, 0),
            null),
        arguments(
            mockAccountWithDeviceAndRegId(1L, 42, 2L, 255),
            deviceAndRegistrationIds(1L, 0, 2L, 42),
            Set.of(2L)),
        arguments(
            mockAccountWithDeviceAndRegId(1L, 42, 2L, 256),
            deviceAndRegistrationIds(1L, 41, 2L, 257),
            Set.of(1L, 2L))
    );
  }

  @ParameterizedTest
  @MethodSource("validateRegistrationIdsSource")
  void testValidateRegistrationIds(
      Account account,
      Collection<Pair<Long, Integer>> deviceAndRegistrationIds,
      Set<Long> expectedStaleDeviceIds) throws Exception {
    if (expectedStaleDeviceIds != null) {
      Assertions.assertThat(assertThrows(StaleDevicesException.class, () -> {
        MessageController.validateRegistrationIds(account, deviceAndRegistrationIds.stream());
      }).getStaleDevices()).hasSameElementsAs(expectedStaleDeviceIds);
    } else {
      MessageController.validateRegistrationIds(account, deviceAndRegistrationIds.stream());
    }
  }

  static Account mockAccountWithDeviceAndEnabled(Object... deviceIdAndEnabled) {
    Account account = mock(Account.class);
    if (deviceIdAndEnabled.length % 2 != 0) {
      throw new IllegalArgumentException("invalid number of arguments specified; must be even");
    }
    final Set<Device> devices = new HashSet<>(deviceIdAndEnabled.length / 2);
    for (int i = 0; i < deviceIdAndEnabled.length; i+=2) {
      if (!(deviceIdAndEnabled[i] instanceof Long)) {
        throw new IllegalArgumentException("device id is not instance of long at index " + i);
      }
      if (!(deviceIdAndEnabled[i + 1] instanceof Boolean)) {
        throw new IllegalArgumentException("enabled is not instance of boolean at index " + (i + 1));
      }
      Long deviceId = (Long) deviceIdAndEnabled[i];
      Boolean enabled = (Boolean) deviceIdAndEnabled[i + 1];
      Device device = mock(Device.class);
      when(device.isEnabled()).thenReturn(enabled);
      when(device.getId()).thenReturn(deviceId);
      when(account.getDevice(deviceId)).thenReturn(Optional.of(device));
      devices.add(device);
    }
    when(account.getDevices()).thenReturn(devices);
    return account;
  }

  static Stream<Arguments> validateCompleteDeviceListSource() {
    return Stream.of(
        arguments(
            mockAccountWithDeviceAndEnabled(1L, true, 2L, false, 3L, true),
            Set.of(1L, 3L),
            null,
            null),
        arguments(
            mockAccountWithDeviceAndEnabled(1L, true, 2L, false, 3L, true),
            Set.of(1L, 2L, 3L),
            null,
            Set.of(2L)),
        arguments(
            mockAccountWithDeviceAndEnabled(1L, true, 2L, false, 3L, true),
            Set.of(1L),
            Set.of(3L),
            null),
        arguments(
            mockAccountWithDeviceAndEnabled(1L, true, 2L, false, 3L, true),
            Set.of(1L, 2L),
            Set.of(3L),
            Set.of(2L))
    );
  }

  @ParameterizedTest
  @MethodSource("validateCompleteDeviceListSource")
  void testValidateCompleteDeviceList(
      Account account,
      Set<Long> deviceIds,
      Collection<Long> expectedMissingDeviceIds,
      Collection<Long> expectedExtraDeviceIds) throws Exception {
    if (expectedMissingDeviceIds != null || expectedExtraDeviceIds != null) {
      final MismatchedDevicesException mismatchedDevicesException = assertThrows(MismatchedDevicesException.class,
          () -> MessageController.validateCompleteDeviceList(account, deviceIds, false));
      if (expectedMissingDeviceIds != null) {
        Assertions.assertThat(mismatchedDevicesException.getMissingDevices())
            .hasSameElementsAs(expectedMissingDeviceIds);
      }
      if (expectedExtraDeviceIds != null) {
        Assertions.assertThat(mismatchedDevicesException.getExtraDevices()).hasSameElementsAs(expectedExtraDeviceIds);
      }
    } else {
      MessageController.validateCompleteDeviceList(account, deviceIds, false);
    }
  }

}
