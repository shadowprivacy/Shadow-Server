/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

public class AccountDatabaseCrawlerRestartException extends Exception {
  public AccountDatabaseCrawlerRestartException(String s) {
    super(s);
  }

  public AccountDatabaseCrawlerRestartException(Exception e) {
    super(e);
  }
}