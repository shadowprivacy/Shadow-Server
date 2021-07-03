/*
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.annotation.Timed;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.dropwizard.auth.Auth;

import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.auth.Anonymous;
import su.sres.shadowserver.auth.OptionalAccess;
import su.sres.shadowserver.entities.IncomingMessage;
import su.sres.shadowserver.entities.IncomingMessageList;
import su.sres.shadowserver.entities.MismatchedDevices;
import su.sres.shadowserver.entities.OutgoingMessageEntity;
import su.sres.shadowserver.entities.OutgoingMessageEntityList;
import su.sres.shadowserver.entities.SendMessageResponse;
import su.sres.shadowserver.entities.StaleDevices;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
// excluded federation, reserved for future use
// import su.sres.shadowserver.federation.FederatedClient;
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.NoSuchPeerException;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.NotPushRegisteredException;
import su.sres.shadowserver.push.PushSender;
import su.sres.shadowserver.push.ReceiptSender;
// import su.sres.shadowserver.push.TransientPushFailureException;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.util.Base64;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Util;
import su.sres.shadowserver.websocket.WebSocketConnection;

import static com.codahale.metrics.MetricRegistry.name;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/messages")
public class MessageController {

	private final Logger         logger            = LoggerFactory.getLogger(MessageController.class);
	  private final MetricRegistry metricRegistry    = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
	  private final Meter          unidentifiedMeter = metricRegistry.meter(name(getClass(), "delivery", "unidentified"));
	  private final Meter          identifiedMeter   = metricRegistry.meter(name(getClass(), "delivery", "identified"  ));

  private final RateLimiters           rateLimiters;
  private final PushSender             pushSender;
  private final ReceiptSender          receiptSender;
//excluded federation, reserved for future use
  //  private final FederatedClientManager federatedClientManager;
  private final AccountsManager        accountsManager;
  private final MessagesManager        messagesManager;
  private final ApnFallbackManager     apnFallbackManager;

  public MessageController(RateLimiters rateLimiters,
                           PushSender pushSender,
                           ReceiptSender receiptSender,
                           AccountsManager accountsManager,
                           MessagesManager messagesManager,
                         //excluded federation, reserved for future use
                           // FederatedClientManager federatedClientManager,
                           ApnFallbackManager apnFallbackManager)
  {
    this.rateLimiters           = rateLimiters;
    this.pushSender             = pushSender;
    this.receiptSender          = receiptSender;
    this.accountsManager        = accountsManager;
    this.messagesManager        = messagesManager;
  //excluded federation, reserved for future use
    //  this.federatedClientManager = federatedClientManager;
    this.apnFallbackManager     = apnFallbackManager;
  }

  @Timed
  @Path("/{destination}")
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public SendMessageResponse sendMessage(@Auth                                     Optional<Account>   source,
          @HeaderParam(OptionalAccess.UNIDENTIFIED) Optional<Anonymous> accessKey,
          @PathParam("destination")                 AmbiguousIdentifier destinationName,
          @Valid                                    IncomingMessageList messages)
throws RateLimitExceededException
  {  
	  
	  if (!source.isPresent() && !accessKey.isPresent()) {		  
		  
	      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
	    }	  
	 

	  if (source.isPresent() && !source.get().isFor(destinationName)) {
	      rateLimiters.getMessagesLimiter().validate(source.get().getUserLogin() + "__" + destinationName);
    }
	    
	  if (source.isPresent() && !source.get().isFor(destinationName)) {
	        identifiedMeter.mark();
	  } else if (!source.isPresent()) {
	        unidentifiedMeter.mark();
	      }

    try {
    	boolean isSyncMessage = source.isPresent() && source.get().isFor(destinationName);

        Optional<Account> destination;

        if (!isSyncMessage) destination = accountsManager.get(destinationName);
        else                destination = source;

        OptionalAccess.verify(source, accessKey, destination);
        assert(destination.isPresent());

        validateCompleteDeviceList(destination.get(), messages.getMessages(), isSyncMessage);
        validateRegistrationIds(destination.get(), messages.getMessages());

      /* excluded federation (?), reserved for future purposes
       * 
       
        if (Util.isEmpty(messages.getRelay())) sendLocalMessage(source, destinationName, messages, isSyncMessage);
      else                                   sendRelayMessage(source, destinationName, messages, isSyncMessage);

      return new SendMessageResponse(!isSyncMessage && source.getActiveDeviceCount() > 1);
      */
        
        for (IncomingMessage incomingMessage : messages.getMessages()) {
            Optional<Device> destinationDevice = destination.get().getDevice(incomingMessage.getDestinationDeviceId());
            
            if (destinationDevice.isPresent()) {
            	sendMessage(source, destination.get(), destinationDevice.get(), messages.getTimestamp(), messages.isOnline(), incomingMessage);
              }
            }

        return new SendMessageResponse(!isSyncMessage && source.isPresent() && source.get().getEnabledDeviceCount() > 1); 
        
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
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public OutgoingMessageEntityList getPendingMessages(@Auth Account account) {
    assert account.getAuthenticatedDevice().isPresent();

    if (!Util.isEmpty(account.getAuthenticatedDevice().get().getApnId())) {
      RedisOperation.unchecked(() -> apnFallbackManager.cancel(account, account.getAuthenticatedDevice().get()));
    }

    return messagesManager.getMessagesForDevice(account.getUserLogin(),
                                                account.getAuthenticatedDevice().get().getId());
  }

  @Timed
  @DELETE
  @Path("/{source}/{timestamp}")
  public void removePendingMessage(@Auth Account account,
                                   @PathParam("source") String source,
                                   @PathParam("timestamp") long timestamp)
      
  {
    try {
      WebSocketConnection.messageTime.update(System.currentTimeMillis() - timestamp);

      Optional<OutgoingMessageEntity> message = messagesManager.delete(account.getUserLogin(),
                                                                       account.getAuthenticatedDevice().get().getId(),
                                                                       source, timestamp);

      if (message.isPresent() && message.get().getType() != Envelope.Type.RECEIPT_VALUE) {
        receiptSender.sendReceipt(account,
                                  message.get().getSource(),
                                  // excluded federation, reserved for future purposes
                                  // message.get().getTimestamp(),
                                  // Optional.fromNullable(message.get().getRelay()));
                                  message.get().getTimestamp());
                                  
      }    
   // excluded federation, reserved for future purposes
      //    } catch (NoSuchUserException | TransientPushFailureException e) {
    } catch (NoSuchUserException e) {
      logger.warn("Sending delivery receipt", e);
    }
  }
  
  @Timed
  @DELETE
  @Path("/uuid/{uuid}")
  public void removePendingMessage(@Auth Account account, @PathParam("uuid") UUID uuid) {
    try {
      Optional<OutgoingMessageEntity> message = messagesManager.delete(account.getUserLogin(),
                                                                       account.getAuthenticatedDevice().get().getId(),
                                                                       uuid);


      message.ifPresent(outgoingMessageEntity -> WebSocketConnection.messageTime.update(System.currentTimeMillis() - outgoingMessageEntity.getTimestamp()));

      if (message.isPresent() && !Util.isEmpty(message.get().getSource()) && message.get().getType() != Envelope.Type.RECEIPT_VALUE) {
          receiptSender.sendReceipt(account, message.get().getSource(), message.get().getTimestamp());
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
      throws NoSuchUserException
  {
    try {
      Optional<byte[]> messageBody    = getMessageBody(incomingMessage);
      Optional<byte[]> messageContent = getMessageContent(incomingMessage);
      Envelope.Builder messageBuilder = Envelope.newBuilder();

      messageBuilder.setType(Envelope.Type.valueOf(incomingMessage.getType()))                    
                    .setTimestamp(timestamp == 0 ? System.currentTimeMillis() : timestamp)
                    .setServerTimestamp(System.currentTimeMillis());

                    if (source.isPresent()) {
                      messageBuilder.setSource(source.get().getUserLogin())
                                    .setSourceUuid(source.get().getUuid().toString())              
                                    .setSourceDevice((int)source.get().getAuthenticatedDevice().get().getId());
                    }
      if (messageBody.isPresent()) {
        messageBuilder.setLegacyMessage(ByteString.copyFrom(messageBody.get()));
      }

      if (messageContent.isPresent()) {
        messageBuilder.setContent(ByteString.copyFrom(messageContent.get()));
      }
/* excluded federation, reserved for future use
 *  
      if (source.getRelay().isPresent()) {
        messageBuilder.setRelay(source.getRelay().get());
      }
*/
      

      pushSender.sendMessage(destinationAccount, destinationDevice, messageBuilder.build(), online);
    } catch (NotPushRegisteredException e) {
      if (destinationDevice.isMaster()) throw new NoSuchUserException(e);
      else                              logger.debug("Not registered", e);
    }
  }

/*   excluded federation, reserved for future use
 *  
 
  private void sendRelayMessage(Account source,
                                String destinationName,
                                IncomingMessageList messages,
                                boolean isSyncMessage)
      throws IOException, NoSuchUserException, InvalidDestinationException
  {
    if (isSyncMessage) throw new InvalidDestinationException("Transcript messages can't be relayed!");

    try {
      FederatedClient client = federatedClientManager.getClient(messages.getRelay());
      client.sendMessages(source.getNumber(), source.getAuthenticatedDevice().get().getId(),
                          destinationName, messages);
    } catch (NoSuchPeerException e) {
      throw new NoSuchUserException(e);
    }
  }

  private Account getDestinationAccount(String destination)
      throws NoSuchUserException
  {
    Optional<Account> account = accountsManager.get(destination);

    if (!account.isPresent() || !account.get().isActive()) {
      throw new NoSuchUserException(destination);
    }

    return account.get();
  }
  */

  private void validateRegistrationIds(Account account, List<IncomingMessage> messages)
      throws StaleDevicesException
  {
    List<Long> staleDevices = new LinkedList<>();

    for (IncomingMessage message : messages) {
      Optional<Device> device = account.getDevice(message.getDestinationDeviceId());

      if (device.isPresent() &&
          message.getDestinationRegistrationId() > 0 &&
          message.getDestinationRegistrationId() != device.get().getRegistrationId())
      {
        staleDevices.add(device.get().getId());
      }
    }

    if (!staleDevices.isEmpty()) {
      throw new StaleDevicesException(staleDevices);
    }
  }

  private void validateCompleteDeviceList(Account account,
                                          List<IncomingMessage> messages,
                                          boolean isSyncMessage)
      throws MismatchedDevicesException
  {
    Set<Long> messageDeviceIds = new HashSet<>();
    Set<Long> accountDeviceIds = new HashSet<>();

    List<Long> missingDeviceIds = new LinkedList<>();
    List<Long> extraDeviceIds   = new LinkedList<>();

    for (IncomingMessage message : messages) {
      messageDeviceIds.add(message.getDestinationDeviceId());
    }

    for (Device device : account.getDevices()) {
        if (device.isEnabled() &&
          !(isSyncMessage && device.getId() == account.getAuthenticatedDevice().get().getId()))
      {
        accountDeviceIds.add(device.getId());

        if (!messageDeviceIds.contains(device.getId())) {
          missingDeviceIds.add(device.getId());
        }
      }
    }

    for (IncomingMessage message : messages) {
      if (!accountDeviceIds.contains(message.getDestinationDeviceId())) {
        extraDeviceIds.add(message.getDestinationDeviceId());
      }
    }

    if (!missingDeviceIds.isEmpty() || !extraDeviceIds.isEmpty()) {
      throw new MismatchedDevicesException(missingDeviceIds, extraDeviceIds);
    }
  }

  private Optional<byte[]> getMessageBody(IncomingMessage message) {
	  if (Util.isEmpty(message.getBody())) return Optional.empty();

    try {
      return Optional.of(Base64.decode(message.getBody()));
    } catch (IOException ioe) {
      logger.debug("Bad B64", ioe);
      return Optional.empty();
    }
  }

  private Optional<byte[]> getMessageContent(IncomingMessage message) {
	  if (Util.isEmpty(message.getContent())) return Optional.empty();

    try {
      return Optional.of(Base64.decode(message.getContent()));
    } catch (IOException ioe) {
      logger.debug("Bad B64", ioe);
      return Optional.empty();
    }
  }
}
