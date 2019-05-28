package su.sres.shadowserver.controllers;

import su.sres.shadowserver.storage.AccountsManager;

public class FederationController {

	// changed AttachmentController to AttachmentControllerV1 
	
  protected final AccountsManager      accounts;
  protected final AttachmentControllerV1 attachmentController;
  protected final MessageController    messageController;

  public FederationController(AccountsManager accounts,
                              AttachmentControllerV1 attachmentController,
                              MessageController messageController)
  {
    this.accounts             = accounts;
    this.attachmentController = attachmentController;
    this.messageController    = messageController;
  }
}
