/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.dropwizard.auth.Auth;
import io.dropwizard.util.DataSize;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.auth.Anonymous;
import su.sres.shadowserver.auth.CombinedUnidentifiedSenderAccessKeys;
import su.sres.shadowserver.auth.OptionalAccess;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicMessageRateConfiguration;
import su.sres.shadowserver.entities.AccountMismatchedDevices;
import su.sres.shadowserver.entities.AccountStaleDevices;
import su.sres.shadowserver.entities.IncomingMessage;
import su.sres.shadowserver.entities.IncomingMessageList;
import su.sres.shadowserver.entities.MismatchedDevices;
import su.sres.shadowserver.entities.MultiRecipientMessage;
import su.sres.shadowserver.entities.MultiRecipientMessage.Recipient;
import su.sres.shadowserver.entities.OutgoingMessageEntity;
import su.sres.shadowserver.entities.OutgoingMessageEntityList;
import su.sres.shadowserver.entities.SendMessageResponse;
import su.sres.shadowserver.entities.SendMultiRecipientMessageResponse;
import su.sres.shadowserver.entities.StaleDevices;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
import su.sres.shadowserver.entities.MessageProtos.Envelope.Type;
import su.sres.shadowserver.limits.RateLimitChallengeException;
import su.sres.shadowserver.limits.RateLimitChallengeManager;
// excluded federation, reserved for future use
// import su.sres.shadowserver.federation.FederatedClient;
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.NoSuchPeerException;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.limits.UnsealedSenderRateLimiter;
import su.sres.shadowserver.metrics.UserAgentTagUtil;
import su.sres.shadowserver.providers.MultiRecipientMessageProvider;
import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.NotPushRegisteredException;
import su.sres.shadowserver.push.MessageSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
// import su.sres.shadowserver.push.TransientPushFailureException;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.ReportMessageManager;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.Util;
import su.sres.shadowserver.util.ua.UnrecognizedUserAgentException;
import su.sres.shadowserver.util.ua.UserAgentUtil;
import su.sres.shadowserver.websocket.WebSocketConnection;

import static com.codahale.metrics.MetricRegistry.name;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/messages")
public class MessageController {

  private final Logger logger = LoggerFactory.getLogger(MessageController.class);
  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter unidentifiedMeter = metricRegistry.meter(name(getClass(), "delivery", "unidentified"));
  private final Meter identifiedMeter = metricRegistry.meter(name(getClass(), "delivery", "identified"));
  private final Meter rejectOver256kibMessageMeter = metricRegistry.meter(name(getClass(), "rejectOver256kibMessage"));
  private final Meter rejectUnsealedSenderLimit = metricRegistry.meter(name(getClass(), "rejectUnsealedSenderLimit"));
  private final Timer sendMessageInternalTimer = metricRegistry.timer(name(getClass(), "sendMessageInternal"));
  private final Timer sendCommonMessageInternalTimer   = metricRegistry.timer(name(getClass(), "sendCommonMessageInternal"));
  private final Histogram outgoingMessageListSizeHistogram = metricRegistry.histogram(name(getClass(), "outgoingMessageListSize"));

  private final RateLimiters rateLimiters;
  private final MessageSender messageSender;
  private final ReceiptSender receiptSender;
  // excluded federation, reserved for future use
  // private final FederatedClientManager federatedClientManager;
  private final AccountsManager accountsManager;
  private final MessagesManager messagesManager;
  private final UnsealedSenderRateLimiter unsealedSenderRateLimiter;
  private final RateLimitChallengeManager rateLimitChallengeManager;
  private final ReportMessageManager reportMessageManager;
  private final ApnFallbackManager apnFallbackManager;
  private final DynamicConfiguration dynamicConfiguration;  
  private final ScheduledExecutorService    receiptExecutorService;

  private final Random random = new Random();

  private static final String LEGACY_MESSAGE_SENT_COUNTER = name(MessageController.class, "legacyMessageSent");
  private static final String SENT_MESSAGE_COUNTER_NAME = name(MessageController.class, "sentMessages"); 
  private static final String REJECT_UNSEALED_SENDER_COUNTER_NAME                = name(MessageController.class, "rejectUnsealedSenderLimit");
  private static final String UNSEALED_SENDER_WITHOUT_PUSH_TOKEN_COUNTER_NAME    = name(MessageController.class, "unsealedSenderWithoutPushToken");
  private static final String DECLINED_DELIVERY_COUNTER                          = name(MessageController.class, "declinedDelivery");
  private static final String CONTENT_SIZE_DISTRIBUTION_NAME = name(MessageController.class, "messageContentSize");
  private static final String OUTGOING_MESSAGE_LIST_SIZE_BYTES_DISTRIBUTION_NAME = name(MessageController.class, "outgoingMessageListSizeBytes");

  private static final String EPHEMERAL_TAG_NAME = "ephemeral";
  private static final String SENDER_TYPE_TAG_NAME = "senderType";

  private static final long MAX_MESSAGE_SIZE = DataSize.kibibytes(256).toBytes();
  
  public MessageController(RateLimiters rateLimiters,
      MessageSender messageSender,
      ReceiptSender receiptSender,
      AccountsManager accountsManager,
      MessagesManager messagesManager,
      UnsealedSenderRateLimiter unsealedSenderRateLimiter,
      // excluded federation, reserved for future use
      // FederatedClientManager federatedClientManager,
      ApnFallbackManager apnFallbackManager,
      DynamicConfiguration dynamicConfiguration,
      RateLimitChallengeManager rateLimitChallengeManager,
      ReportMessageManager reportMessageManager,
      FaultTolerantRedisCluster metricsCluster,
      ScheduledExecutorService receiptExecutorService) {
    this.rateLimiters = rateLimiters;
    this.messageSender = messageSender;
    this.receiptSender = receiptSender;
    this.accountsManager = accountsManager;
    this.messagesManager = messagesManager;  
    this.unsealedSenderRateLimiter = unsealedSenderRateLimiter;
    // excluded federation, reserved for future use
    // this.federatedClientManager = federatedClientManager;
    this.apnFallbackManager = apnFallbackManager;
    this.dynamicConfiguration = dynamicConfiguration;   
    this.rateLimitChallengeManager = rateLimitChallengeManager;
    this.reportMessageManager = reportMessageManager;
    this.receiptExecutorService = receiptExecutorService;
  }
  
  @Timed
  @POST
  @Path("/report/{sourceUserLogin}/{messageGuid}")
  public Response reportMessage(@Auth Account account, @PathParam("sourceUserLogin") String sourceUserLogin, @PathParam("messageGuid") UUID messageGuid) {

    reportMessageManager.report(sourceUserLogin, messageGuid);

    return Response.status(Status.ACCEPTED)
        .build();
  }

  @Timed
  @Path("/{destination}")
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendMessage(@Auth Optional<Account> source,
      @HeaderParam(OptionalAccess.UNIDENTIFIED) Optional<Anonymous> accessKey,
      @HeaderParam("User-Agent") String userAgent,
      @HeaderParam("X-Forwarded-For")           String forwardedFor,
      @PathParam("destination") AmbiguousIdentifier destinationName,
      @Valid IncomingMessageList messages)
          throws RateLimitExceededException, RateLimitChallengeException {    
        
    if (source.isEmpty() && accessKey.isEmpty()) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }    
    
    if (source.isPresent() && !source.get().isFor(destinationName)) {
                  
      assert source.get().getMasterDevice().isPresent();

      final Device masterDevice = source.get().getMasterDevice().get();
      
      if (StringUtils.isAllBlank(masterDevice.getApnId(), masterDevice.getVoipApnId(), masterDevice.getGcmId()) || masterDevice.getUninstalledFeedbackTimestamp() > 0) {
        Metrics.counter(UNSEALED_SENDER_WITHOUT_PUSH_TOKEN_COUNTER_NAME).increment();
      }           
      
    }    

    final String senderType;

    if (source.isPresent() && !source.get().isFor(destinationName)) {
      identifiedMeter.mark();
      senderType = "identified";   
    } else if (source.isEmpty()) {
      unidentifiedMeter.mark();
      senderType = "unidentified";      
    } else {
      senderType = "self";
    }

    for (final IncomingMessage message : messages.getMessages()) {
      int contentLength = 0;

      if (!Util.isEmpty(message.getContent())) {
        contentLength += message.getContent().length();
      }

      if (!Util.isEmpty(message.getBody())) {
        contentLength += message.getBody().length();
      }

      Metrics.summary(CONTENT_SIZE_DISTRIBUTION_NAME, Tags.of(UserAgentTagUtil.getPlatformTag(userAgent))).record(contentLength);

      if (contentLength > MAX_MESSAGE_SIZE) {
        rejectOver256kibMessageMeter.mark();
        return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build();
      }
    }    

    try {
      boolean isSyncMessage = source.isPresent() && source.get().isFor(destinationName);

      Optional<Account> destination;

      if (!isSyncMessage)
        destination = accountsManager.get(destinationName);
      else
        destination = source;

      OptionalAccess.verify(source, accessKey, destination);
      assert (destination.isPresent());

      if (source.isPresent() && !source.get().isFor(destinationName)) {
        rateLimiters.getMessagesLimiter().validate(source.get().getUserLogin() + "__" + destination.get().getUuid());
        
        try {          
          unsealedSenderRateLimiter.validate(source.get(), destination.get());
        } catch (final RateLimitExceededException e) {

          final boolean enforceLimit = rateLimitChallengeManager.shouldIssueRateLimitChallenge(userAgent);

          Metrics.counter(REJECT_UNSEALED_SENDER_COUNTER_NAME,              
              "enforced", String.valueOf(enforceLimit))
              .increment();

          if (enforceLimit) {
            logger.debug("Rejected unsealed sender limit from: {}", source.get().getUserLogin());

            throw new RateLimitChallengeException(source.get(), e.getRetryDuration());
          } else {
            throw e;
          }
        }
        
        final Device masterDevice = source.get().getMasterDevice().get();
      }

      validateCompleteDeviceList(destination.get(), messages.getMessages(), isSyncMessage);
      validateRegistrationIds(destination.get(), messages.getMessages());              
      
      final List<Tag> tags = List.of(UserAgentTagUtil.getPlatformTag(userAgent),
          Tag.of(EPHEMERAL_TAG_NAME, String.valueOf(messages.isOnline())),
          Tag.of(SENDER_TYPE_TAG_NAME, senderType));

      /*
       * excluded federation (?), reserved for future purposes
       * 
       * 
       * if (Util.isEmpty(messages.getRelay())) sendLocalMessage(source,
       * destinationName, messages, isSyncMessage); else sendRelayMessage(source,
       * destinationName, messages, isSyncMessage);
       * 
       * return new SendMessageResponse(!isSyncMessage &&
       * source.getActiveDeviceCount() > 1);
       */

      for (IncomingMessage incomingMessage : messages.getMessages()) {
        Optional<Device> destinationDevice = destination.get().getDevice(incomingMessage.getDestinationDeviceId());

        if (destinationDevice.isPresent()) {

          Metrics.counter(SENT_MESSAGE_COUNTER_NAME, tags).increment();
          
          sendMessage(source, destination.get(), destinationDevice.get(), messages.getTimestamp(), messages.isOnline(), incomingMessage);
        }

      }

      return Response.ok(new SendMessageResponse(!isSyncMessage && source.isPresent() && source.get().getEnabledDeviceCount() > 1)).build();
    } catch (NoSuchUserException e) {
      throw new WebApplicationException(Response.status(404).build());
    } catch (MismatchedDevicesException e) {
      throw new WebApplicationException(Response.status(409)
          .type(MediaType.APPLICATION_JSON_TYPE)
          .entity(new MismatchedDevices(e.getMissingDevices(),
              e.getExtraDevices()))
          .build());
    } catch (StaleDevicesException e) {
      throw new WebApplicationException(Response.status(410)
          .type(MediaType.APPLICATION_JSON)
          .entity(new StaleDevices(e.getStaleDevices()))
          .build());
    }

  }
  
  @Timed
  @Path("/multi_recipient")
  @PUT
  @Consumes(MultiRecipientMessageProvider.MEDIA_TYPE)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendMultiRecipientMessage(
      @HeaderParam(OptionalAccess.UNIDENTIFIED) CombinedUnidentifiedSenderAccessKeys accessKeys,
      @HeaderParam("User-Agent") String userAgent,
      @HeaderParam("X-Forwarded-For") String forwardedFor,
      @QueryParam("online") boolean online,
      @QueryParam("ts") long timestamp,
      @Valid MultiRecipientMessage multiRecipientMessage) {

    unidentifiedMeter.mark(multiRecipientMessage.getRecipients().length);

    Map<UUID, Account> uuidToAccountMap = Arrays.stream(multiRecipientMessage.getRecipients())
        .map(Recipient::getUuid)
        .distinct()
        .collect(Collectors.toMap(Function.identity(), uuid -> {
          Optional<Account> account = accountsManager.get(uuid);
          if (account.isEmpty()) {
            throw new WebApplicationException(Status.NOT_FOUND);
          }
          return account.get();
        }));
    checkAccessKeys(accessKeys, uuidToAccountMap);

    final Map<Account, HashSet<Pair<Long, Integer>>> accountToDeviceIdAndRegistrationIdMap =
        Arrays
            .stream(multiRecipientMessage.getRecipients())
            .collect(Collectors.toMap(
                recipient -> uuidToAccountMap.get(recipient.getUuid()),
                recipient -> new HashSet<>(
                    Collections.singletonList(new Pair<>(recipient.getDeviceId(), recipient.getRegistrationId()))),
                (a, b) -> {
                  a.addAll(b);
                  return a;
                }
            ));

    Collection<AccountMismatchedDevices> accountMismatchedDevices = new ArrayList<>();
    Collection<AccountStaleDevices> accountStaleDevices = new ArrayList<>();
    uuidToAccountMap.values().forEach(account -> {
      final Set<Pair<Long, Integer>> deviceIdAndRegistrationIdSet = accountToDeviceIdAndRegistrationIdMap.get(account);
      final Set<Long> deviceIds = deviceIdAndRegistrationIdSet.stream().map(Pair::first).collect(Collectors.toSet());
      try {
        validateCompleteDeviceList(account, deviceIds, false);
        validateRegistrationIds(account, deviceIdAndRegistrationIdSet.stream());
      } catch (MismatchedDevicesException e) {
        accountMismatchedDevices.add(new AccountMismatchedDevices(account.getUuid(),
            new MismatchedDevices(e.getMissingDevices(), e.getExtraDevices())));
      } catch (StaleDevicesException e) {
        accountStaleDevices.add(new AccountStaleDevices(account.getUuid(), new StaleDevices(e.getStaleDevices())));
      }
    });
    if (!accountMismatchedDevices.isEmpty()) {
      return Response
          .status(409)
          .type(MediaType.APPLICATION_JSON_TYPE)
          .entity(accountMismatchedDevices)
          .build();
    }
    
    if (!accountStaleDevices.isEmpty()) {
      return Response
          .status(410)
          .type(MediaType.APPLICATION_JSON)
          .entity(accountStaleDevices)
          .build();
    }

    List<Tag> tags = List.of(
        UserAgentTagUtil.getPlatformTag(userAgent),
        Tag.of(EPHEMERAL_TAG_NAME, String.valueOf(online)),
        Tag.of(SENDER_TYPE_TAG_NAME, "unidentified"));
    List<UUID> uuids404 = new ArrayList<>();
    for (Recipient recipient : multiRecipientMessage.getRecipients()) {

      Account destinationAccount = uuidToAccountMap.get(recipient.getUuid());
      // we asserted this must be true in validateCompleteDeviceList
      //noinspection OptionalGetWithoutIsPresent
      Device destinationDevice = destinationAccount.getDevice(recipient.getDeviceId()).get();
      Metrics.counter(SENT_MESSAGE_COUNTER_NAME, tags).increment();
      try {
        sendMessage(destinationAccount, destinationDevice, timestamp, online, recipient,
            multiRecipientMessage.getCommonPayload());
      } catch (NoSuchUserException e) {
        uuids404.add(destinationAccount.getUuid());
      }
    }
    return Response.ok(new SendMultiRecipientMessageResponse(uuids404)).build();
  }

  private void checkAccessKeys(CombinedUnidentifiedSenderAccessKeys accessKeys, Map<UUID, Account> uuidToAccountMap) {
    AtomicBoolean throwUnauthorized = new AtomicBoolean(false);
    byte[] empty = new byte[16];
    byte[] combinedUnknownAccessKeys = uuidToAccountMap.values().stream()
        .map(Account::getUnidentifiedAccessKey)
        .map(accessKey -> {
          if (accessKey.isEmpty()) {
            throwUnauthorized.set(true);
            return empty;
          }
          return accessKey.get();
        })
        .reduce(new byte[16], (bytes, bytes2) -> {
          if (bytes.length != bytes2.length) {
            throwUnauthorized.set(true);
            return bytes;
          }
          for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= bytes2[i];
          }
          return bytes;
        });
    if (throwUnauthorized.get()
        || !MessageDigest.isEqual(combinedUnknownAccessKeys, accessKeys.getAccessKeys())) {
      throw new WebApplicationException(Status.UNAUTHORIZED);
    }
  }
  
  private Response declineDelivery(final IncomingMessageList messages, final Account source, final Account destination) {
    Metrics.counter(DECLINED_DELIVERY_COUNTER).increment();

    final DynamicMessageRateConfiguration messageRateConfiguration = dynamicConfiguration.getMessageRateConfiguration();

    {
      final long timestamp = System.currentTimeMillis();

      for (final IncomingMessage message : messages.getMessages()) {
        final long jitterNanos = random.nextInt((int) messageRateConfiguration.getReceiptDelayJitter().toNanos());
        final Duration receiptDelay = messageRateConfiguration.getReceiptDelay().plusNanos(jitterNanos);

        if (random.nextDouble() <= messageRateConfiguration.getReceiptProbability()) {
          receiptExecutorService.schedule(() -> {
            try {
              receiptSender.sendReceipt(destination, source.getUserLogin(), timestamp);
            } catch (final NoSuchUserException ignored) {
            }
          }, receiptDelay.toMillis(), TimeUnit.MILLISECONDS);
        }
      }
    }

    {
      Duration responseDelay = Duration.ZERO;

      for (int i = 0; i < messages.getMessages().size(); i++) {
        final long jitterNanos = random.nextInt((int) messageRateConfiguration.getResponseDelayJitter().toNanos());

        responseDelay = responseDelay.plus(
            messageRateConfiguration.getResponseDelay()).plusNanos(jitterNanos);
      }

      Util.sleep(responseDelay.toMillis());
    }

    return Response.ok(new SendMessageResponse(source.getEnabledDeviceCount() > 1)).build();
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public OutgoingMessageEntityList getPendingMessages(@Auth Account account, @HeaderParam("User-Agent") String userAgent) {
    assert account.getAuthenticatedDevice().isPresent();

    if (!Util.isEmpty(account.getAuthenticatedDevice().get().getApnId())) {
      RedisOperation.unchecked(() -> apnFallbackManager.cancel(account, account.getAuthenticatedDevice().get()));
    }

    final OutgoingMessageEntityList outgoingMessages = messagesManager.getMessagesForDevice(
        account.getUuid(),
        account.getAuthenticatedDevice().get().getId(),
        userAgent,
        false);

    outgoingMessageListSizeHistogram.update(outgoingMessages.getMessages().size());

    {
      String platform;

      try {
        platform = UserAgentUtil.parseUserAgentString(userAgent).getPlatform().name().toLowerCase();
      } catch (final UnrecognizedUserAgentException ignored) {
        platform = "unrecognized";
      }

      Metrics.summary(OUTGOING_MESSAGE_LIST_SIZE_BYTES_DISTRIBUTION_NAME, "platform", platform).record(estimateMessageListSizeBytes(outgoingMessages));
    }

    return outgoingMessages;
  }

  private static long estimateMessageListSizeBytes(final OutgoingMessageEntityList messageList) {
    long size = 0;

    for (final OutgoingMessageEntity message : messageList.getMessages()) {
      size += message.getContent() == null ? 0 : message.getContent().length;
      size += message.getMessage() == null ? 0 : message.getMessage().length;
      size += Util.isEmpty(message.getSource()) ? 0 : message.getSource().length();
      size += Util.isEmpty(message.getRelay()) ? 0 : message.getRelay().length();
    }

    return size;
  }

  @Timed
  @DELETE
  @Path("/{source}/{timestamp}")
  public void removePendingMessage(@Auth Account account,
      @PathParam("source") String source,
      @PathParam("timestamp") long timestamp)

  {
    try {
      WebSocketConnection.recordMessageDeliveryDuration(timestamp, account.getAuthenticatedDevice().get());

      Optional<OutgoingMessageEntity> message = messagesManager.delete(
          account.getUuid(),
          account.getAuthenticatedDevice().get().getId(),
          source, timestamp);

      if (message.isPresent() && message.get().getType() != Envelope.Type.SERVER_DELIVERY_RECEIPT_VALUE) {
        receiptSender.sendReceipt(account,
            message.get().getSource(),
            // excluded federation, reserved for future purposes
            // message.get().getTimestamp(),
            // Optional.fromNullable(message.get().getRelay()));
            message.get().getTimestamp());

      }
      // excluded federation, reserved for future purposes
      // } catch (NoSuchUserException | TransientPushFailureException e) {
    } catch (NoSuchUserException e) {
      logger.warn("Sending delivery receipt", e);
    }
  }

  @Timed
  @DELETE
  @Path("/uuid/{uuid}")
  public void removePendingMessage(@Auth Account account, @PathParam("uuid") UUID uuid) {
    try {
      Optional<OutgoingMessageEntity> message = messagesManager.delete(
          account.getUuid(),
          account.getAuthenticatedDevice().get().getId(),
          uuid);

      if (message.isPresent()) {
        WebSocketConnection.recordMessageDeliveryDuration(message.get().getTimestamp(), account.getAuthenticatedDevice().get());
        if (!Util.isEmpty(message.get().getSource()) && message.get().getType() != Envelope.Type.SERVER_DELIVERY_RECEIPT_VALUE) {
          receiptSender.sendReceipt(account, message.get().getSource(), message.get().getTimestamp());
        }
      }
    } catch (NoSuchUserException e) {
      logger.warn("Sending delivery receipt", e);
    }
  }

  private void sendMessage(Optional<Account> source,
      Account destinationAccount,
      Device destinationDevice,
      long timestamp,
      boolean online,
      IncomingMessage incomingMessage)
      throws NoSuchUserException {
    try (final Timer.Context ignored = sendMessageInternalTimer.time()) {
      Optional<byte[]> messageBody = getMessageBody(incomingMessage);
      Optional<byte[]> messageContent = getMessageContent(incomingMessage);
      Envelope.Builder messageBuilder = Envelope.newBuilder();

      messageBuilder.setType(Envelope.Type.forNumber(incomingMessage.getType()))
          .setTimestamp(timestamp == 0 ? System.currentTimeMillis() : timestamp)
          .setServerTimestamp(System.currentTimeMillis());

      if (source.isPresent()) {
        messageBuilder.setSource(source.get().getUserLogin())
            .setSourceUuid(source.get().getUuid().toString())
            .setSourceDevice((int) source.get().getAuthenticatedDevice().get().getId());
      }
      if (messageBody.isPresent()) {
        Metrics.counter(LEGACY_MESSAGE_SENT_COUNTER).increment();
        messageBuilder.setLegacyMessage(ByteString.copyFrom(messageBody.get()));
      }

      if (messageContent.isPresent()) {
        messageBuilder.setContent(ByteString.copyFrom(messageContent.get()));
      }
      /*
       * excluded federation, reserved for future use
       * 
       * if (source.getRelay().isPresent()) {
       * messageBuilder.setRelay(source.getRelay().get()); }
       */

      messageSender.sendMessage(destinationAccount, destinationDevice, messageBuilder.build(), online);
    } catch (NotPushRegisteredException e) {
      if (destinationDevice.isMaster())
        throw new NoSuchUserException(e);
      else
        logger.debug("Not registered", e);
    }
  }
  
  private void sendMessage(Account destinationAccount, Device destinationDevice, long timestamp, boolean online,
      Recipient recipient, byte[] commonPayload) throws NoSuchUserException {
    try (final Timer.Context ignored = sendCommonMessageInternalTimer.time()) {
      Envelope.Builder messageBuilder = Envelope.newBuilder();
      long serverTimestamp = System.currentTimeMillis();
      byte[] recipientKeyMaterial = recipient.getPerRecipientKeyMaterial();

      byte[] payload = new byte[1 + recipientKeyMaterial.length + commonPayload.length];
      payload[0] = MultiRecipientMessageProvider.VERSION;
      System.arraycopy(recipientKeyMaterial, 0, payload, 1, recipientKeyMaterial.length);
      System.arraycopy(commonPayload, 0, payload, 1 + recipientKeyMaterial.length, commonPayload.length);

      messageBuilder
          .setType(Type.UNIDENTIFIED_SENDER)
          .setTimestamp(timestamp == 0 ? serverTimestamp : timestamp)
          .setServerTimestamp(serverTimestamp)
          .setContent(ByteString.copyFrom(payload));

      messageSender.sendMessage(destinationAccount, destinationDevice, messageBuilder.build(), online);
    } catch (NotPushRegisteredException e) {
      if (destinationDevice.isMaster()) {
        throw new NoSuchUserException(e);
      } else {
        logger.debug("Not registered", e);
      }
    }
  }

  /*
   * excluded federation, reserved for future use
   * 
   * 
   * private void sendRelayMessage(Account source, String destinationName,
   * IncomingMessageList messages, boolean isSyncMessage) throws IOException,
   * NoSuchUserException, InvalidDestinationException { if (isSyncMessage) throw
   * new InvalidDestinationException("Transcript messages can't be relayed!");
   * 
   * try { FederatedClient client =
   * federatedClientManager.getClient(messages.getRelay());
   * client.sendMessages(source.getNumber(),
   * source.getAuthenticatedDevice().get().getId(), destinationName, messages); }
   * catch (NoSuchPeerException e) { throw new NoSuchUserException(e); } }
   * 
   * private Account getDestinationAccount(String destination) throws
   * NoSuchUserException { Optional<Account> account =
   * accountsManager.get(destination);
   * 
   * if (!account.isPresent() || !account.get().isActive()) { throw new
   * NoSuchUserException(destination); }
   * 
   * return account.get(); }
   */

  @VisibleForTesting
  public static void validateRegistrationIds(Account account, List<IncomingMessage> messages)
      throws StaleDevicesException {
    final Stream<Pair<Long, Integer>> deviceIdAndRegistrationIdStream = messages
        .stream()
        .map(message -> new Pair<>(message.getDestinationDeviceId(), message.getDestinationRegistrationId()));
    validateRegistrationIds(account, deviceIdAndRegistrationIdStream);
  }

  @VisibleForTesting
  public static void validateRegistrationIds(Account account, Stream<Pair<Long, Integer>> deviceIdAndRegistrationIdStream)
      throws StaleDevicesException {
    final List<Long> staleDevices = deviceIdAndRegistrationIdStream
        .filter(deviceIdAndRegistrationId -> deviceIdAndRegistrationId.second() > 0)
        .filter(deviceIdAndRegistrationId -> {
          Optional<Device> device = account.getDevice(deviceIdAndRegistrationId.first());
          return device.isPresent() && deviceIdAndRegistrationId.second() != device.get().getRegistrationId();
        })
        .map(Pair::first)
        .collect(Collectors.toList());

    if (!staleDevices.isEmpty()) {
      throw new StaleDevicesException(staleDevices);
    }
  }
  
  @VisibleForTesting
  public static void validateCompleteDeviceList(Account account, List<IncomingMessage> messages, boolean isSyncMessage)
      throws MismatchedDevicesException {
    Set<Long> messageDeviceIds = messages.stream().map(IncomingMessage::getDestinationDeviceId).collect(Collectors.toSet());
    validateCompleteDeviceList(account, messageDeviceIds, isSyncMessage);
  }

  @VisibleForTesting
  public static void validateCompleteDeviceList(Account account, Set<Long> messageDeviceIds, boolean isSyncMessage)
      throws MismatchedDevicesException {
    
    Set<Long> accountDeviceIds = new HashSet<>();

    List<Long> missingDeviceIds = new LinkedList<>();
    List<Long> extraDeviceIds = new LinkedList<>();

    for (Device device : account.getDevices()) {
      if (device.isEnabled() &&
          !(isSyncMessage && device.getId() == account.getAuthenticatedDevice().get().getId())) {
        accountDeviceIds.add(device.getId());

        if (!messageDeviceIds.contains(device.getId())) {
          missingDeviceIds.add(device.getId());
        }
      }
    }

    for (Long deviceId : messageDeviceIds) {
      if (!accountDeviceIds.contains(deviceId)) {
        extraDeviceIds.add(deviceId);
      }
    }

    if (!missingDeviceIds.isEmpty() || !extraDeviceIds.isEmpty()) {
      throw new MismatchedDevicesException(missingDeviceIds, extraDeviceIds);
    }
  }

  private Optional<byte[]> getMessageBody(IncomingMessage message) {
    if (Util.isEmpty(message.getBody()))
      return Optional.empty();

    try {
      return Optional.of(Base64.getDecoder().decode(message.getBody()));
    } catch (IllegalArgumentException e) {
      logger.debug("Bad B64", e);
      return Optional.empty();
    }
  }

  private Optional<byte[]> getMessageContent(IncomingMessage message) {
    if (Util.isEmpty(message.getContent()))
      return Optional.empty();

    try {
      return Optional.of(Base64.getDecoder().decode(message.getContent()));
    } catch (IllegalArgumentException e) {
      logger.debug("Bad B64", e);
      return Optional.empty();
    }
  }
}
