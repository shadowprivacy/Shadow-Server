/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

public class NotPushRegisteredException extends Exception {
  public NotPushRegisteredException() {
    super();
  }
  
  public NotPushRegisteredException(String s) {
    super(s);
  }

  public NotPushRegisteredException(Exception e) {
    super(e);
  }
}
