/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.auth;

import org.glassfish.jersey.server.ContainerRequest;
import su.sres.shadowserver.storage.Account;
import javax.ws.rs.core.SecurityContext;
import java.util.Optional;

class ContainerRequestUtil {

  static Optional<Account> getAuthenticatedAccount(final ContainerRequest request) {
    return Optional.ofNullable(request.getSecurityContext())
        .map(SecurityContext::getUserPrincipal)
        .map(principal -> principal instanceof AccountAndAuthenticatedDeviceHolder
            ? ((AccountAndAuthenticatedDeviceHolder) principal).getAccount() : null);
  }
}
