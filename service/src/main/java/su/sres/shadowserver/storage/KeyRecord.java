/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

public class KeyRecord {

  private long    id;
  private String  number;
  private long    deviceId;
  private long    keyId;
  private String  publicKey;

  public KeyRecord(long id, String number, long deviceId, long keyId, String publicKey) {
    this.id         = id;
    this.number     = number;
    this.deviceId   = deviceId;
    this.keyId      = keyId;
    this.publicKey  = publicKey;
  }

  public long getId() {
    return id;
  }

  public String getNumber() {
    return number;
  }

  public long getDeviceId() {
    return deviceId;
  }

  public long getKeyId() {
    return keyId;
  }

  public String getPublicKey() {
    return publicKey;
  }
  
}
