/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.websocket;

public class InvalidWebsocketAddressException extends Exception {
  public InvalidWebsocketAddressException(String serialized) {
    super(serialized);
  }

  public InvalidWebsocketAddressException(Exception e) {
    super(e);
  }
}
