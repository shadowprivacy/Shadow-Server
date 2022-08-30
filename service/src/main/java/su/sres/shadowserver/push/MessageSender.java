/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.codahale.metrics.MetricRegistry.name;
import static su.sres.shadowserver.entities.MessageProtos.Envelope;

import io.dropwizard.lifecycle.Managed;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import su.sres.shadowserver.controllers.MessageController;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.redis.RedisOperation;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Util;

/**
 * A MessageSender sends Signal messages to destination devices. Messages may be
 * "normal" user-to-user messages, ephemeral ("online") messages like typing
 * indicators, or delivery receipts.
 * <p/>
 * If a client is not actively connected to a Signal server to receive a message
 * as soon as it is sent, the MessageSender will send a push notification to the
 * destination device if possible. Some messages may be designated for "online"
 * delivery only and will not be delivered (and clients will not be notified) if
 * the destination device isn't actively connected to a Signal server.
 *
 * @see ClientPresenceManager
 * @see su.sres.shadowserver.storage.MessageAvailabilityListener
 * @see ReceiptSender
 */
public class MessageSender implements Managed {

    private final ApnFallbackManager apnFallbackManager;
    private final ClientPresenceManager clientPresenceManager;
    private final MessagesManager messagesManager;
    private final GCMSender gcmSender;
    private final APNSender apnSender;
    private final PushLatencyManager pushLatencyManager;
    
    private final Logger logger = LoggerFactory.getLogger(MessageController.class);

    private static final String SEND_COUNTER_NAME = name(MessageSender.class, "sendMessage");
    private static final String CHANNEL_TAG_NAME = "channel";
    private static final String EPHEMERAL_TAG_NAME = "ephemeral";
    private static final String CLIENT_ONLINE_TAG_NAME = "clientOnline";

    public MessageSender(ApnFallbackManager apnFallbackManager,
	    ClientPresenceManager clientPresenceManager,
	    MessagesManager messagesManager,
	    GCMSender gcmSender,
	    APNSender apnSender,
	    PushLatencyManager pushLatencyManager) {

	this.apnFallbackManager = apnFallbackManager;
	this.clientPresenceManager = clientPresenceManager;
	this.messagesManager = messagesManager;
	this.gcmSender = gcmSender;
	this.apnSender = apnSender;
	this.pushLatencyManager = pushLatencyManager;
    }

    public void sendMessage(final Account account, final Device device, final Envelope message, boolean online)
	    throws NotPushRegisteredException {
	if (device.getGcmId() == null && device.getApnId() == null && !device.getFetchesMessages()) {
	    throw new NotPushRegisteredException("No delivery possible!");
	}

	final String channel;

	if (device.getGcmId() != null) {
	    channel = "gcm";
	    // remove after testing
	    logger.warn("Channel is GCM");;
	} else if (device.getApnId() != null) {
	    channel = "apn";
	} else if (device.getFetchesMessages()) {
	    channel = "websocket";
	 // remove after testing
        logger.warn("Channel is Websocket");
	} else {
	    throw new AssertionError();
	}

	final boolean clientPresent;

	if (online) {
	    clientPresent = clientPresenceManager.isPresent(account.getUuid(), device.getId());
	    
	 // remove after testing
        logger.warn("Online is true and clientPresent is: " + clientPresent);

	    if (clientPresent) {
		messagesManager.insertEphemeral(account.getUuid(), device.getId(), message);
	    }
	} else {
	    messagesManager.insert(account.getUuid(), device.getId(), message);

	    // We check for client presence after inserting the message to take a
	    // conservative view of notifications. If the
	    // client wasn't present at the time of insertion but is now, they'll retrieve
	    // the message. If they were present
	    // but disconnected before the message was delivered, we should send a
	    // notification.
	    clientPresent = clientPresenceManager.isPresent(account.getUuid(), device.getId());
	    
	    // remove after testing
	    logger.warn("Online is false and clientPresent is: " + clientPresent);;

	    if (!clientPresent) {
		sendNewMessageNotification(account, device);
	    }
	}

	final List<Tag> tags = List.of(
		Tag.of(CHANNEL_TAG_NAME, channel),
		Tag.of(EPHEMERAL_TAG_NAME, String.valueOf(online)),
		Tag.of(CLIENT_ONLINE_TAG_NAME, String.valueOf(clientPresent)));

	Metrics.counter(SEND_COUNTER_NAME, tags).increment();
    }

    public void sendNewMessageNotification(final Account account, final Device device) {
	if (!Util.isEmpty(device.getGcmId())) {
	    sendGcmNotification(account, device);
	} else if (!Util.isEmpty(device.getApnId()) || !Util.isEmpty(device.getVoipApnId())) {
	    sendApnNotification(account, device);
	}
    }

    private void sendGcmNotification(Account account, Device device) {
	GcmMessage gcmMessage = new GcmMessage(device.getGcmId(), account.getUserLogin(),
		(int) device.getId(), GcmMessage.Type.NOTIFICATION, Optional.empty());

	gcmSender.sendMessage(gcmMessage);

	RedisOperation.unchecked(() -> pushLatencyManager.recordPushSent(account.getUuid(), device.getId()));
    }

    private void sendApnNotification(Account account, Device device) {
	ApnMessage apnMessage;

	if (!Util.isEmpty(device.getVoipApnId())) {
	    apnMessage = new ApnMessage(device.getVoipApnId(), account.getUserLogin(), device.getId(), true, Optional.empty());
	    RedisOperation.unchecked(() -> apnFallbackManager.schedule(account, device));
	} else {
	    apnMessage = new ApnMessage(device.getApnId(), account.getUserLogin(), device.getId(), false, Optional.empty());
	}

	apnSender.sendMessage(apnMessage);

	RedisOperation.unchecked(() -> pushLatencyManager.recordPushSent(account.getUuid(), device.getId()));
    }

    @Override
    public void start() {
	// apnSender.start();
    }

    @Override
    public void stop() {

	// apnSender.stop();
    }
}
