/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.annotations.VisibleForTesting;

import su.sres.shadowserver.configuration.LocalParametersConfiguration;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.codahale.metrics.MetricRegistry.name;

public class AccountCleaner extends AccountDatabaseCrawlerListener {
  private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private static final Meter expiredAccountsMeter = metricRegistry.meter(name(AccountCleaner.class, "expiredAccounts"));
  private static final Histogram deletableAccountHistogram = metricRegistry.histogram(name(AccountCleaner.class, "deletableAccountsPerChunk"));

  @VisibleForTesting
  public static final int MAX_ACCOUNT_UPDATES_PER_CHUNK = 40;

  private final AccountsManager accountsManager;
  private final int action;
  private final int interval;

  @VisibleForTesting
  protected final Logger logger = LoggerFactory.getLogger(AccountCleaner.class);

  public AccountCleaner(AccountsManager accountsManager, int action, int interval) {
    this.accountsManager = accountsManager;
    this.action = action;
    this.interval = interval;
  }

  @Override
  public void onCrawlStart() {
  }

  @Override
  public void onCrawlEnd(Optional<UUID> fromUuid) {
  }

  @Override
  protected void onCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts) {
          
    int accountUpdateCount = 0;
    int deletableAccountCount = 0;
    HashSet<Account> accountsToDelete = new HashSet<Account>();
    for (Account account : chunkAccounts) {
      if (isExpired(account)) {
        deletableAccountCount++;
      }
      if (needsExplicitRemoval(account)) {
        expiredAccountsMeter.mark();

        if (accountUpdateCount < MAX_ACCOUNT_UPDATES_PER_CHUNK) {
          accountsToDelete.add(account);
          accountUpdateCount++;          
        }
      }
    }

    if (!accountsToDelete.isEmpty()) {
     
      if (action == 2) {        
        accountsManager.delete(accountsToDelete, AccountsManager.DeletionReason.EXPIRED); 
        
      } else if (action == 1) {
        
        List<String> accounts = new ArrayList<String>();
        
        for (Account account : accountsToDelete) {
          accounts.add(account.getUserLogin());
        }
        
        Collections.sort(accounts);
        
        logger.info("Account(s) " + String.join(", ", accounts) + " have not been seen for at least " + interval + " days. You may wish to remove them.");
        
      } else {
        // noop; we should not fall here
      }      
      
    }
      

    deletableAccountHistogram.update(deletableAccountCount);
  }

  private boolean needsExplicitRemoval(Account account) {
    return account.getMasterDevice().isPresent() && hasPushToken(account.getMasterDevice().get()) && isExpired(account);
  }

  private boolean hasPushToken(Device device) {
    return !Util.isEmpty(device.getGcmId()) || !Util.isEmpty(device.getApnId())
        || !Util.isEmpty(device.getVoipApnId()) || device.getFetchesMessages();
  }

  private boolean isExpired(Account account) {
    return account.getLastSeen() + TimeUnit.DAYS.toMillis(interval) < System.currentTimeMillis();
  }

}