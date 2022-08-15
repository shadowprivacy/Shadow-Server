/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AccountDatabaseCrawlerConfiguration {

  @JsonProperty
  private int chunkSize = 1000;

  @JsonProperty
  private long chunkIntervalMs = 8000L;

  public int getChunkSize() {
    return chunkSize;
  }

  public long getChunkIntervalMs() {
    return chunkIntervalMs;
  }
}