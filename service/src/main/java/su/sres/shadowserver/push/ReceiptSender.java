package su.sres.shadowserver.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.sres.shadowserver.controllers.NoSuchUserException;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
// federation excluded, reserved for future use
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.NoSuchPeerException;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;

import java.util.Optional;

public class ReceiptSender {

    private final MessageSender messageSender;
    private final AccountsManager accountManager;

    private static final Logger logger = LoggerFactory.getLogger(ReceiptSender.class);

    /*
     * federation excluded, reserved for future use
     * 
     * 
     * private final FederatedClientManager federatedClientManager;
     * 
     * public ReceiptSender(AccountsManager accountManager, MessageSender
     * pushSender, FederatedClientManager federatedClientManager) {
     * this.federatedClientManager = federatedClientManager; this.accountManager =
     * accountManager; this.pushSender = pushSender; }
     * 
     */

    public ReceiptSender(AccountsManager accountManager, MessageSender messageSender) {
	this.accountManager = accountManager;
	this.messageSender = messageSender;
    }

    /*
     * federation excluded, reserved for future use
     * 
     * 
     * public void sendReceipt(Account source, String destination, long messageId,
     * Optional<String> relay) throws IOException, NoSuchUserException,
     * NotPushRegisteredException, TransientPushFailureException { if
     * (source.getNumber().equals(destination)) { return; }
     * 
     * if (relay.isPresent() && !relay.get().isEmpty()) { sendRelayedReceipt(source,
     * destination, messageId, relay.get()); } else { sendDirectReceipt(source,
     * destination, messageId); } }
     * 
     * private void sendRelayedReceipt(Account source, String destination, long
     * messageId, String relay) throws NoSuchUserException, IOException { try {
     * federatedClientManager.getClient(relay)
     * .sendDeliveryReceipt(source.getNumber(),
     * source.getAuthenticatedDevice().get().getId(), destination, messageId); }
     * catch (NoSuchPeerException e) { throw new NoSuchUserException(e); } }
     * 
     */

    public void sendReceipt(Account source, String destination, long messageId) throws NoSuchUserException {
	if (source.getUserLogin().equals(destination)) {
	    return;
	}

	Account destinationAccount = getDestinationAccount(destination);
	Envelope.Builder message = Envelope.newBuilder().setSource(source.getUserLogin())
		.setSourceUuid(source.getUuid().toString())
		.setSourceDevice((int) source.getAuthenticatedDevice().get().getId()).setTimestamp(messageId)
		.setType(Envelope.Type.RECEIPT);

	if (source.getRelay().isPresent()) {
	    message.setRelay(source.getRelay().get());
	}

	for (final Device destinationDevice : destinationAccount.getDevices()) {
	    try {
		messageSender.sendMessage(destinationAccount, destinationDevice, message.build(), false);
	    } catch (NotPushRegisteredException e) {
		logger.info("User no longer push registered for delivery receipt: " + e.getMessage());
	    }
	}
    }

    private Account getDestinationAccount(String destination) throws NoSuchUserException {
	Optional<Account> account = accountManager.get(destination);

	if (!account.isPresent()) {
	    throw new NoSuchUserException(destination);
	}

	return account.get();
    }

}
