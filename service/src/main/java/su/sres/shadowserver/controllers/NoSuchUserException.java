/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class NoSuchUserException extends Exception {

  public NoSuchUserException(final UUID uuid) {
    super(uuid.toString());
  }

  public NoSuchUserException(Exception e) {
    super(e);
  }  
}
