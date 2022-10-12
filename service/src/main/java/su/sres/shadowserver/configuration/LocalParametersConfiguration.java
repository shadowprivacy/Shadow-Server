/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import javax.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LocalParametersConfiguration {

  @JsonProperty
  @NotEmpty
  private int verificationCodeLifetime;

  @JsonProperty
  @NotEmpty
  private String keyStorePath;

  @JsonProperty
  @NotEmpty
  private String keyStorePassword;

  @JsonProperty
  @NotEmpty
  private String licensePath;

  @JsonProperty
  @NotEmpty
  private int accountLifetime;

  @JsonProperty
  @NotEmpty
  private int accountExpirationPolicy;

  public int getVerificationCodeLifetime() {
    return verificationCodeLifetime;
  }

  public String getKeyStorePath() {
    return keyStorePath;
  }

  public String getKeyStorePassword() {
    return keyStorePassword;
  }

  public String getLicensePath() {
    return licensePath;
  }

  public int getAccountLifetime() {
    return accountLifetime;
  }

  public int getAccountExpirationPolicy() {
    return accountExpirationPolicy;
  }

}