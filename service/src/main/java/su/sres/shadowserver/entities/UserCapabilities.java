/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import su.sres.shadowserver.storage.Account;

public class UserCapabilities {

  public static UserCapabilities createForAccount(Account account) {
    return new UserCapabilities(
        account.isGroupsV2Supported(),
        account.isGv1MigrationSupported(),
        account.isSenderKeySupported(),
        account.isAnnouncementGroupSupported(),
        account.isChangeUserLoginSupported());
  }

  @JsonProperty
  private boolean gv2;

  @JsonProperty("gv1-migration")
  private boolean gv1Migration;

  @JsonProperty
  private boolean senderKey;

  @JsonProperty
  private boolean announcementGroup;
  
  @JsonProperty
  private boolean changeUserLogin;

  public UserCapabilities() {
  }

  public UserCapabilities(final boolean gv2,
      boolean gv1Migration,
      final boolean senderKey,
      final boolean announcementGroup,
      final boolean changeUserLogin) {
    this.gv2 = gv2;
    this.gv1Migration = gv1Migration;
    this.senderKey = senderKey;
    this.announcementGroup = announcementGroup;
    this.changeUserLogin = changeUserLogin;
  }

  public boolean isGv2() {
    return gv2;
  }

  public boolean isGv1Migration() {
    return gv1Migration;
  }

  public boolean isSenderKey() {
    return senderKey;
  }

  public boolean isAnnouncementGroup() {
    return announcementGroup;
  }
  
  public boolean isChangeUserLogin() {
    return changeUserLogin;
  }
}