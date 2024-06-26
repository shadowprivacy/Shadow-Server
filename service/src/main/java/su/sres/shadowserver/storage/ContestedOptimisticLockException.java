/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

public class ContestedOptimisticLockException extends RuntimeException {

  public ContestedOptimisticLockException() {
    super(null, null, true, false);
  }
}
