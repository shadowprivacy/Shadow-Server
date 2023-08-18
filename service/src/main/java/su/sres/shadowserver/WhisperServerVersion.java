/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver;

public class WhisperServerVersion {

  private static final String VERSION = "${project.version}";

  public static String getServerVersion() {
    return VERSION;
  }
}