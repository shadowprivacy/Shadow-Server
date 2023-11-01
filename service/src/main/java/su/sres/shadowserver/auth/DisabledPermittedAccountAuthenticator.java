/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import su.sres.shadowserver.storage.AccountsManager;

import java.util.Optional;

import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.basic.BasicCredentials;

public class DisabledPermittedAccountAuthenticator extends BaseAccountAuthenticator implements
    Authenticator<BasicCredentials, DisabledPermittedAuthenticatedAccount> {

  public DisabledPermittedAccountAuthenticator(AccountsManager accountsManager) {
    super(accountsManager);
  }

  @Override
  public Optional<DisabledPermittedAuthenticatedAccount> authenticate(BasicCredentials credentials) {
    Optional<AuthenticatedAccount> account = super.authenticate(credentials, false);
    return account.map(DisabledPermittedAuthenticatedAccount::new);
  }
}