/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.websocket;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class ProvisioningAddress extends WebsocketAddress {

  public ProvisioningAddress(String address, int id) {
    super(address, id);
  }

  public ProvisioningAddress(String serialized) throws InvalidWebsocketAddressException {
    super(serialized);
  }

  public String getAddress() {
    return getNumber();
  }

  public static ProvisioningAddress generate() {
    byte[] random = new byte[16];
    new SecureRandom().nextBytes(random);

    return new ProvisioningAddress(Base64.getUrlEncoder().withoutPadding().encodeToString(random), 0);
  }
}
