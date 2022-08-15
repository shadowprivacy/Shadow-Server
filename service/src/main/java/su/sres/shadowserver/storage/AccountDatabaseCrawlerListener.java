/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;

import su.sres.shadowserver.util.Constants;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.codahale.metrics.MetricRegistry.name;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class AccountDatabaseCrawlerListener {

	  private Timer processChunkTimer;

	  abstract public void onCrawlStart();

	  abstract public void onCrawlEnd(Optional<UUID> fromUuid);

	  abstract protected void onCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts) throws AccountDatabaseCrawlerRestartException;

	  public AccountDatabaseCrawlerListener() {
	    processChunkTimer = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME).timer(name(AccountDatabaseCrawlerListener.class, "processChunk", getClass().getSimpleName()));
	  }

	  public void timeAndProcessCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts) throws AccountDatabaseCrawlerRestartException {
	    try (Timer.Context timer = processChunkTimer.time()) {
	      onCrawlChunk(fromUuid, chunkAccounts);
	    }
	  }
}