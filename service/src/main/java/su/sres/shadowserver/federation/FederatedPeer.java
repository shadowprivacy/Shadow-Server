/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.federation;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotEmpty;

public class FederatedPeer {

  @NotEmpty
  @JsonProperty
  private String name;

  @NotEmpty  
  @JsonProperty
  private String url;

  @NotEmpty
  @JsonProperty
  private String authenticationToken;

  @NotEmpty
  @JsonProperty
  private String certificate;

  public FederatedPeer() {}

  public FederatedPeer(String name, String url, String authenticationToken, String certificate) {
    this.name                = name;
    this.url                 = url;
    this.authenticationToken = authenticationToken;
    this.certificate         = certificate;
  }

  public String getUrl() {
    return url;
  }

  public String getName() {
    return name;
  }

  public String getAuthenticationToken() {
    return authenticationToken;
  }

  public String getCertificate() {
    return certificate;
  }
}
