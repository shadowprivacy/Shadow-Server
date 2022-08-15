/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.crypto;

public interface ECPublicKey extends Comparable<ECPublicKey> {

  public static final int KEY_SIZE = 33;

  public byte[] serialize();

  public int getType();
} 