/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.federation;


public class NoSuchPeerException extends Exception {
  public NoSuchPeerException(String name) {
    super(name);
  }
}
