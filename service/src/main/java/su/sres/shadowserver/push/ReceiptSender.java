package su.sres.shadowserver.push;


import su.sres.shadowserver.controllers.NoSuchUserException;
import su.sres.shadowserver.entities.MessageProtos.Envelope;
// federation excluded, reserved for future use
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.NoSuchPeerException;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;

import java.util.Optional;
import java.util.Set;

public class ReceiptSender {

  private final PushSender             pushSender;
//federation excluded, reserved for future use
  // private final FederatedClientManager federatedClientManager;
  private final AccountsManager        accountManager;

 /* federation excluded, reserved for future use
  * 
  
  public ReceiptSender(AccountsManager        accountManager,
                       PushSender             pushSender,
                       FederatedClientManager federatedClientManager)
  {
    this.federatedClientManager = federatedClientManager;
    this.accountManager         = accountManager;
    this.pushSender             = pushSender;
  }
  
  */
  
  public ReceiptSender(AccountsManager accountManager,
          PushSender      pushSender)
{
	  this.accountManager = accountManager;
	    this.pushSender     = pushSender;
}

  /* federation excluded, reserved for future use
   *  
  
  public void sendReceipt(Account source, String destination,
                          long messageId, Optional<String> relay)
      throws IOException, NoSuchUserException,
             NotPushRegisteredException, TransientPushFailureException
  {
    if (source.getNumber().equals(destination)) {
      return;
    }

    if (relay.isPresent() && !relay.get().isEmpty()) {
      sendRelayedReceipt(source, destination, messageId, relay.get());
    } else {
      sendDirectReceipt(source, destination, messageId);
    }
  }

  private void sendRelayedReceipt(Account source, String destination, long messageId, String relay)
      throws NoSuchUserException, IOException
  {
    try {
      federatedClientManager.getClient(relay)
                            .sendDeliveryReceipt(source.getNumber(),
                                                 source.getAuthenticatedDevice().get().getId(),
                                                 destination, messageId);
    } catch (NoSuchPeerException e) {
      throw new NoSuchUserException(e);
    }
  }
  
  */
  
  public void sendReceipt(Account source, String destination, long messageId)
	      throws NoSuchUserException, NotPushRegisteredException
	  {
	    if (source.getUserLogin().equals(destination)) {
	      return;
	    }
 
    Account          destinationAccount = getDestinationAccount(destination);
    Set<Device>      destinationDevices = destinationAccount.getDevices();
    Envelope.Builder message            = Envelope.newBuilder()
                                                  .setSource(source.getUserLogin())
                                                  .setSourceUuid(source.getUuid().toString())
                                                  .setSourceDevice((int) source.getAuthenticatedDevice().get().getId())
                                                  .setTimestamp(messageId)
                                                  .setType(Envelope.Type.RECEIPT);

    if (source.getRelay().isPresent()) {
      message.setRelay(source.getRelay().get());
    }

    for (Device destinationDevice : destinationDevices) {
    	pushSender.sendMessage(destinationAccount, destinationDevice, message.build(), false);
    }
  }

  private Account getDestinationAccount(String destination)
      throws NoSuchUserException
  {
    Optional<Account> account = accountManager.get(destination);

    if (!account.isPresent()) {
      throw new NoSuchUserException(destination);
    }

    return account.get();
  }

}
