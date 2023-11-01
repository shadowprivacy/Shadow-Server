/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

public class ChunkProcessingFailedException extends Exception {

  public ChunkProcessingFailedException(String message) {
    super(message);
  }

  public ChunkProcessingFailedException(Exception cause) {
    super(cause);
  }
}
