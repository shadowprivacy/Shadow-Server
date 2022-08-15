/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import su.sres.shadowserver.util.Base64;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.IOException;

public class Anonymous {

  private final byte[] unidentifiedSenderAccessKey;

  public Anonymous(String header) {
    try {
      this.unidentifiedSenderAccessKey = Base64.decode(header);
    } catch (IOException e) {
      throw new WebApplicationException(e, Response.Status.UNAUTHORIZED);
    }
  }

  public byte[] getAccessKey() {
    return unidentifiedSenderAccessKey;
  }
}