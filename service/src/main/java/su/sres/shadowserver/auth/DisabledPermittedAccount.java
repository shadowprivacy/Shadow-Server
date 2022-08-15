/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import su.sres.shadowserver.storage.Account;

import javax.security.auth.Subject;
import java.security.Principal;

public class DisabledPermittedAccount implements Principal  {

  private final Account account;

  public DisabledPermittedAccount(Account account) {
    this.account = account;
  }

  public Account getAccount() {
    return account;
  }

  // Principal implementation

  @Override
  public String getName() {
    return null;
  }

  @Override
  public boolean implies(Subject subject) {
    return false;
  }
}