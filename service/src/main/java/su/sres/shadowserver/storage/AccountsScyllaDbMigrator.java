package su.sres.shadowserver.storage;

import static com.codahale.metrics.MetricRegistry.name;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AccountsScyllaDbMigrator extends AccountDatabaseCrawlerListener { 

  private final AccountsScyllaDb accountsScyllaDb;
  private final DynamicConfiguration dynamicConfiguration;
    
  public AccountsScyllaDbMigrator(final AccountsScyllaDb accountsScyllaDb) {
    this.accountsScyllaDb = accountsScyllaDb;
    this.dynamicConfiguration = new DynamicConfiguration();
  }

  @Override
  public void onCrawlStart() {

  }

  @Override
  public void onCrawlEnd(Optional<UUID> fromUuid) {

  }

  @Override
  protected void onCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts) {

    
    final CompletableFuture<Void> migrationBatch = accountsScyllaDb.migrate(chunkAccounts,
        dynamicConfiguration.getAccountsScyllaDbMigrationConfiguration().getBackgroundMigrationExecutorThreads());

    migrationBatch.join();
  }
}
