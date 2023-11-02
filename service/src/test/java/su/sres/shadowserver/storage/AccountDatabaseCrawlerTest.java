/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

  private final AccountDatabaseCrawler crawler = new AccountDatabaseCrawler(accounts, cache, Arrays.asList(listener), CHUNK_SIZE, CHUNK_INTERVAL_MS);
 
  @BeforeEach
  void setup() {
    when(account1.getUuid()).thenReturn(ACCOUNT1);
    when(account2.getUuid()).thenReturn(ACCOUNT2);
  
    when(accounts.getAllFromScylla(anyInt())).thenReturn(new AccountCrawlChunk(Arrays.asList(account1, account2), ACCOUNT2));
    when(accounts.getAllFromScylla(eq(ACCOUNT1), anyInt())).thenReturn(new AccountCrawlChunk(Arrays.asList(account2), ACCOUNT2));
    when(accounts.getAllFromScylla(eq(ACCOUNT2), anyInt())).thenReturn(new AccountCrawlChunk(Collections.emptyList(), null));

    when(cache.claimActiveWork(any(), anyLong())).thenReturn(true);
    when(cache.isAccelerated()).thenReturn(false);
  }

  @Test
  void testCrawlStart() throws AccountDatabaseCrawlerRestartException {
    when(cache.getLastUuid()).thenReturn(Optional.empty());
    when(cache.getLastUuidScylla()).thenReturn(Optional.empty());

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(0)).getLastUuid();
    verify(cache, times(1)).getLastUuidScylla();
    verify(listener, times(1)).onCrawlStart();    
    verify(accounts, times(1)).getAllFromScylla(eq(CHUNK_SIZE));
    verify(accounts, times(0)).getAllFromScylla(any(UUID.class), eq(CHUNK_SIZE));
    verify(account1, times(0)).getUuid();

    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.empty()), eq(Arrays.asList(account1, account2)));
    verify(cache, times(0)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoMoreInteractions(account1);
    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testCrawlChunk() throws AccountDatabaseCrawlerRestartException {
    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(0)).getLastUuid();
    verify(cache, times(1)).getLastUuidScylla();
    verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
    verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(0)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testCrawlChunkAccelerated() throws AccountDatabaseCrawlerRestartException {

    when(cache.isAccelerated()).thenReturn(true);
    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isTrue();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(0)).getLastUuid();
    verify(cache, times(1)).getLastUuidScylla();
    verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
    verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(0)).setLastUuid(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).setLastUuidScylla(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testCrawlChunkRestart() throws AccountDatabaseCrawlerRestartException {

    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT1));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT1));
    doThrow(AccountDatabaseCrawlerRestartException.class).when(listener).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(0)).getLastUuid();
    verify(cache, times(1)).getLastUuidScylla();
    verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
    verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT1), eq(CHUNK_SIZE));
    verify(account2, times(0)).getUserLogin();
    verify(listener, times(1)).timeAndProcessCrawlChunk(eq(Optional.of(ACCOUNT1)), eq(Arrays.asList(account2)));
    verify(cache, times(0)).setLastUuid(eq(Optional.empty()));
    verify(cache, times(1)).setLastUuidScylla(eq(Optional.empty()));
    verify(cache, times(1)).setAccelerated(false);
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoInteractions(account1);

    verifyNoMoreInteractions(account2);
    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }

  @Test
  void testCrawlEnd() {

    when(cache.getLastUuid()).thenReturn(Optional.of(ACCOUNT2));
    when(cache.getLastUuidScylla()).thenReturn(Optional.of(ACCOUNT2));

    boolean accelerated = crawler.doPeriodicWork();
    assertThat(accelerated).isFalse();

    verify(cache, times(1)).claimActiveWork(any(String.class), anyLong());
    verify(cache, times(0)).getLastUuid();
    verify(cache, times(1)).getLastUuidScylla();
    verify(accounts, times(0)).getAllFromScylla(eq(CHUNK_SIZE));
    verify(accounts, times(1)).getAllFromScylla(eq(ACCOUNT2), eq(CHUNK_SIZE));
    verify(account1, times(0)).getUserLogin();
    verify(account2, times(0)).getUserLogin();
    verify(listener, times(1)).onCrawlEnd(eq(Optional.of(ACCOUNT2)));
    verify(cache, times(0)).setLastUuid(eq(Optional.empty()));
    verify(cache, times(1)).setLastUuidScylla(eq(Optional.empty()));
    verify(cache, times(1)).setAccelerated(false);
    verify(cache, times(1)).isAccelerated();
    verify(cache, times(1)).releaseActiveWork(any(String.class));

    verifyNoInteractions(account1);
    verifyNoInteractions(account2);

    verifyNoMoreInteractions(accounts);
    verifyNoMoreInteractions(listener);
    verifyNoMoreInteractions(cache);
  }
}