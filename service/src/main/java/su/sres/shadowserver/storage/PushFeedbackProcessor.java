/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Util;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

public class PushFeedbackProcessor extends AccountDatabaseCrawlerListener {

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter          expired        = metricRegistry.meter(name(getClass(), "unregistered", "expired"));
  private final Meter          recovered      = metricRegistry.meter(name(getClass(), "unregistered", "recovered"));

  private final AccountsManager accountsManager;
  
  public PushFeedbackProcessor(AccountsManager accountsManager) {
    this.accountsManager = accountsManager;    
  }

  @Override
  public void onCrawlStart() {}

  @Override
  public void onCrawlEnd(Optional<UUID> toUuid) {}

  @Override
  protected void onCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts) {
    for (Account account : chunkAccounts) {
      boolean update = false;

      for (Device device : account.getDevices()) {
        if (device.getUninstalledFeedbackTimestamp() != 0 &&
            device.getUninstalledFeedbackTimestamp() + TimeUnit.DAYS.toMillis(2) <= Util.todayInMillis())
        {
          if (device.getLastSeen() + TimeUnit.DAYS.toMillis(2) <= Util.todayInMillis()) {
            device.setGcmId(null);
            device.setApnId(null);
            device.setVoipApnId(null);
            device.setFetchesMessages(false);
            expired.mark();
          } else {
            device.setUninstalledFeedbackTimestamp(0);
            recovered.mark();
          }

          update = true;
        }
      }

      if (update) {
        accountsManager.update(account);
        
      }
    }
  }  
}