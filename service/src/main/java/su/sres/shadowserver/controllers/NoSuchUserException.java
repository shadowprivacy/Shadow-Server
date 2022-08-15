/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import java.util.LinkedList;
import java.util.List;

public class NoSuchUserException extends Exception {

  private List<String> missing;

  public NoSuchUserException(String user) {
    super(user);
    missing = new LinkedList<>();
    missing.add(user);
  }

  public NoSuchUserException(List<String> missing) {
    this.missing = missing;
  }

  public NoSuchUserException(Exception e) {
    super(e);
  }

  public List<String> getMissing() {
    return missing;
  }
}
