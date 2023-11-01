/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import su.sres.shadowserver.configuration.dynamic.DynamicAccountsScyllaDbMigrationConfiguration;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountDatabaseCrawlerTest {

  private static final UUID ACCOUNT1 = UUID.randomUUID();
  private static final UUID ACCOUNT2 = UUID.randomUUID();

  private static final int CHUNK_SIZE = 1000;
  private static final long CHUNK_INTERVAL_MS = 30_000L;

  private final Account account1 = mock(Account.class);
  private final Account account2 = mock(Account.class);

  private final AccountsManager accounts = mock(AccountsManager.class);
  private final AccountDatabaseCrawlerListener listener = mock(AccountDatabaseCrawlerListener.class);
  private final AccountDatabaseCrawlerCache cache = mock(AccountDatabaseCrawlerCache.class);
  private final ExecutorService chunkPreReadExecutorService = mock(ExecutorService.class);

  private final AccountDatabaseCrawler crawler = new AccountDatabaseCrawler(accounts, cache, Arrays.asList(listener), CHUNK_SIZE, CHUNK_INTERVAL_MS, chunkPreReadExecutorService);
  private DynamicAccountsScyllaDbMigrationConfiguration dynamicAccountsDynamoDbMigrationConfiguration = new DynamicAccountsScyllaDbMigrationConfiguration();

  @BeforeEach
  void setup() {
    when(account1.getUuid()).thenReturn(ACCOUNT1);
    when(account2.getUuid()).thenReturn(ACCOUNT2);

    when(accounts.getAllFrom(anyInt())).thenReturn(new AccountCrawlChunk(Arrays.asList(account1, account2), ACCOUNT2));
    when(accounts.getAllFrom(eq(ACCOUNT1), anyInt())).thenReturn(new AccountCrawlChunk(Arrays.asList(account2), ACCOUNT2));
    when(accounts.getAllFrom(eq(ACCOUNT2), anyInt())).thenReturn(new AccountCrawlChunk(Collections.emptyList(), null));

    when(accounts.getAllFromScylla(anyInt())).thenReturn(new AccountCrawlChunk(Arrays.asList(account1, account2), ACCOUNT2));
    when(accounts.getAllFromScylla(eq(ACCOUNT1), anyInt())).thenReturn(new AccountCrawlChunk(Arrays.asList(account2), ACCOUNT2));
    when(accounts.getAllFromScylla(eq(ACCOUNT2), anyInt())).thenReturn(new AccountCrawlChunk(Collections.emptyList(), null));

    when(cache.claimActiveWork(any(), anyLong())).thenReturn(true);
    when(cache.isAccelerated()).thenReturn(false);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true })
  void testCrawlStart(final boolean useScylla) throws AccountDatabaseCrawlerRestartException {
    when(cache.getLastUuid()).thenReturn(Optional.empty());
    when(cache.getLastUuidScylla()).thenReturn(Optional.empty());

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(useScylla ? 0 : 1)).getLastUuid();
    verify(cache, times(useScylla ? 1 : 0)).getLastUuidScylla();
    verify(listener, times(1)).onCrawlStart();
    verify(accounts, times(1)).getAllFrom(eq(CHUNK_SIZE));
    verify(accounts, times(0)).getAllFrom(any(UUID.class), eq(CHUNK_SIZE));
    if (useScylla) {
      verify(accounts, times(1)).getAllFromScylla(eq(CHUNK_SIZE));
      verify(accounts, times(0)).getAllFromScylla(any(UUID.class), eq(CHUNK_SIZE));
    } else {
      verify(accounts, times(1)).getAllFrom(eq(CHUNK_SIZE));
      verify(accounts, times(0)).getAllFrom(any(UUID.class), eq(CHUNK_SIZE));
    }
    verify(account1, times(0)).getUuid();

    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.empty()), eq(Arrays.asList(account1, account2)));
    verify(cache, times(useScylla ? 0 : 1)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(useScylla ? 1 : 0)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoMoreInteractions(account1);
    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true })
  void testCrawlChunk(final boolean useScylla) throws AccountDatabaseCrawlerRestartException {

    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(useScylla ? 0 : 1)).getLastUuid();
    verify(cache, times(useScylla ? 1 : 0)).getLastUuidScylla();
    if (useScylla) {
      verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    } else {
      verify(accounts, times(0)).getAllFrom(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFrom(eq(ACCOUNT1), eq(CHUNK_SIZE));
    }
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(useScylla ? 0 : 1)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(useScylla ? 1 : 0)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testCrawlChunk_useDynamoDedicatedMigrationCrawler(final boolean dedicatedMigrationCrawler) throws Exception {
    crawler.setDedicatedDynamoMigrationCrawler(dedicatedMigrationCrawler);

    when(dynamicAccountsDynamoDbMigrationConfiguration.isScyllaCrawlerEnabled()).thenReturn(true);
    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(dedicatedMigrationCrawler ? 1 : 0)).getLastUuid();
    verify(cache, times(dedicatedMigrationCrawler ? 0 : 1)).getLastUuidScylla();
    if (dedicatedMigrationCrawler) {
      verify(accounts, times(0)).getAllFrom(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFrom(eq(ACCOUNT1), eq(CHUNK_SIZE));
    } else {
      verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    }
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(dedicatedMigrationCrawler ? 1 : 0)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(dedicatedMigrationCrawler ? 0 : 1)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true })
  void testCrawlChunkAccelerated(final boolean useScylla) throws AccountDatabaseCrawlerRestartException {

    when(cache.isAccelerated()).thenReturn(true);
    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isTrue();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(useScylla ? 0 : 1)).getLastUuid();
    verify(cache, times(useScylla ? 1 : 0)).getLastUuidScylla();
    if (useScylla) {
      verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    } else {
      verify(accounts, times(0)).getAllFrom(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFrom(eq(ACCOUNT1), eq(CHUNK_SIZE));
    }
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(useScylla ? 0 : 1)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(useScylla ? 1 : 0)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyZeroInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true })
  void testCrawlChunkRestart(final boolean useScylla) throws AccountDatabaseCrawlerRestartException {

    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));
    doThrow(AccountDatabaseCrawlerRestartException.class).when(listener).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(useScylla ? 0 : 1)).getLastUuid();
    verify(cache, times(useScylla ? 1 : 0)).getLastUuidScylla();
    if (useScylla) {
      verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    } else {
      verify(accounts, times(0)).getAllFrom(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFrom(eq(ACCOUNT1), eq(CHUNK_SIZE));
    }
    verify(account2, times(0)).getUserLogin();
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(useScylla ? 0 : 1)).setLastUuid(eq(Optional.empty()));
    verify(cache, times(useScylla ? 1 : 0)).setLastUuidScylla(eq(Optional.empty()));
    verify(cache, times(1)).setAccelerated(false);
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyZeroInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void testCrawlEnd(final boolean useScylla) {

    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT2));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT2));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(useScylla ? 0 : 1)).getLastUuid();
    verify(cache, times(useScylla ? 1 : 0)).getLastUuidScylla();
    if (useScylla) {
      verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT2), eq(CHUNK_SIZE));
    } else {
      verify(accounts, times(0)).getAllFrom(eq(CHUNK_SIZE));
      verify(accounts, times(1)).getAllFrom(eq(ACCOUNT2), eq(CHUNK_SIZE));
    }
    verify(account1, times(0)).getUserLogin();
    verify(account2, times(0)).getUserLogin();
    verify(listener, times(1)).onCrawlEnd(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(useScylla ? 0 : 1)).setLastUuid(eq(Optional.empty()));
    verify(cache, times(useScylla ? 1 : 0)).setLastUuidScylla(eq(Optional.empty()));
    verify(cache, times(1)).setAccelerated(false);
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyZeroInteractions(account1);
    verifyZeroInteractions(account2);

    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }
}