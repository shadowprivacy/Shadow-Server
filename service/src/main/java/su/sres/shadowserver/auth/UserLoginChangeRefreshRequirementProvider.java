/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.auth;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.glassfish.jersey.server.ContainerRequest;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.Pair;

public class UserLoginChangeRefreshRequirementProvider implements WebsocketRefreshRequirementProvider {

  private static final String INITIAL_LOGIN_KEY =
      UserLoginChangeRefreshRequirementProvider.class.getName() + ".initialNumber";

  @Override
  public void handleRequestFiltered(final ContainerRequest request) {
    ContainerRequestUtil.getAuthenticatedAccount(request)
        .ifPresent(account -> request.setProperty(INITIAL_LOGIN_KEY, account.getUserLogin()));
  }

  @Override
  public List<Pair<UUID, Long>> handleRequestFinished(final ContainerRequest request) {
    final String initialLogin = (String) request.getProperty(INITIAL_LOGIN_KEY);

    if (initialLogin != null) {
      final Optional<Account> maybeAuthenticatedAccount = ContainerRequestUtil.getAuthenticatedAccount(request);

      return maybeAuthenticatedAccount
          .filter(account -> !initialLogin.equals(account.getUserLogin()))
          .map(account -> account.getDevices().stream()
              .map(device -> new Pair<>(account.getUuid(), device.getId()))
              .collect(Collectors.toList()))
          .orElse(Collections.emptyList());
    } else {
      return Collections.emptyList();
    }
  }
}
