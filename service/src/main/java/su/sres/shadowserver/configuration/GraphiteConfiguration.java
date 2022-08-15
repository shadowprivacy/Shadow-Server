/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GraphiteConfiguration {
  @JsonProperty
  private String host;

  @JsonProperty
  private int port;

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public boolean isEnabled() {
    return host != null && port != 0;
  }
}
