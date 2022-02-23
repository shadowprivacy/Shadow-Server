package su.sres.shadowserver.websocket;

import com.google.protobuf.InvalidProtocolBufferException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import su.sres.dispatch.DispatchChannel;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.PubSubProtos.PubSubMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.codahale.metrics.MetricRegistry.name;

import java.util.Optional;

public class DeadLetterHandler implements DispatchChannel {

    private final Logger logger = LoggerFactory.getLogger(DeadLetterHandler.class);

    private final AccountsManager accountsManager;
    private final MessagesManager messagesManager;

    private final Counter deadLetterCounter = Metrics.counter(name(getClass(), "deadLetterCounter"));

    public DeadLetterHandler(AccountsManager accountsManager, MessagesManager messagesManager) {
	this.accountsManager = accountsManager;
	this.messagesManager = messagesManager;
    }

    @Override
    public void onDispatchMessage(String channel, byte[] data) {
	try {
	    logger.info("Handling dead letter to: " + channel);
	    deadLetterCounter.increment();

	    WebsocketAddress address = new WebsocketAddress(channel);
	    PubSubMessage pubSubMessage = PubSubMessage.parseFrom(data);

	    switch (pubSubMessage.getType().getNumber()) {
	    case PubSubMessage.Type.DELIVER_VALUE:
		Envelope message = Envelope.parseFrom(pubSubMessage.getContent());
		Optional<Account> maybeAccount = accountsManager.get(address.getNumber());

		if (maybeAccount.isPresent()) {
		    messagesManager.insert(maybeAccount.get().getUuid(), address.getDeviceId(), message);
		} else {
		    logger.warn("Dead letter for account that no longer exists: {}", address);
		}
		break;
	    }
	} catch (InvalidProtocolBufferException e) {
	    logger.warn("Bad pubsub message", e);
	} catch (InvalidWebsocketAddressException e) {
	    logger.warn("Invalid websocket address", e);
	}
    }

    @Override
    public void onDispatchSubscribed(String channel) {
	logger.warn("DeadLetterHandler subscription notice! " + channel);
    }

    @Override
    public void onDispatchUnsubscribed(String channel) {
	logger.warn("DeadLetterHandler unsubscribe notice! " + channel);
    }
}
