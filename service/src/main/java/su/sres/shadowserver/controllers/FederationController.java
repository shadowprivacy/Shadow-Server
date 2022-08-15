/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
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
