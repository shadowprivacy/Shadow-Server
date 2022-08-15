/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import su.sres.shadowserver.entities.DeliveryCertificate;
import su.sres.shadowserver.util.ByteArrayAdapter;

public class VersionedProfile {

  @JsonProperty
  private String version;

  @JsonProperty
  private String name;

  @JsonProperty
  private String avatar;

  @JsonProperty
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
  private byte[] commitment;

  public VersionedProfile() {}

  public VersionedProfile(String version, String name, String avatar, byte[] commitment) {
    this.version    = version;
    this.name       = name;
    this.avatar     = avatar;
    this.commitment = commitment;
  }

  public String getVersion() {
    return version;
  }

  public String getName() {
    return name;
  }

  public String getAvatar() {
    return avatar;
  }

  public byte[] getCommitment() {
    return commitment;
  }
}
