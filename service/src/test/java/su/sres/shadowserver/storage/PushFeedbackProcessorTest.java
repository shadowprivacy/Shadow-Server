/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import su.sres.shadowserver.util.AccountsHelper;
import su.sres.shadowserver.util.Util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static su.sres.shadowserver.util.AccountsHelper.eqUuid;

class PushFeedbackProcessorTest {

  private AccountsManager accountsManager = mock(AccountsManager.class);

  private Account uninstalledAccount = mock(Account.class);
  private Account mixedAccount = mock(Account.class);
  private Account freshAccount = mock(Account.class);
  private Account cleanAccount = mock(Account.class);
  private Account stillActiveAccount = mock(Account.class);

  private Device uninstalledDevice = mock(Device.class);
  private Device uninstalledDeviceTwo = mock(Device.class);
  private Device installedDevice = mock(Device.class);
  private Device installedDeviceTwo = mock(Device.class);
  private Device recentUninstalledDevice = mock(Device.class);
  private Device stillActiveDevice = mock(Device.class);

  @BeforeEach
  void setup() {
    AccountsHelper.setupMockUpdate(accountsManager);
    when(uninstalledDevice.getUninstalledFeedbackTimestamp()).thenReturn(
        Util.todayInMillis() - TimeUnit.DAYS.toMillis(2));
    when(uninstalledDevice.getLastSeen()).thenReturn(Util.todayInMillis() - TimeUnit.DAYS.toMillis(2));
    when(uninstalledDevice.isEnabled()).thenReturn(true);
    when(uninstalledDeviceTwo.getUninstalledFeedbackTimestamp()).thenReturn(
        Util.todayInMillis() - TimeUnit.DAYS.toMillis(3));
    when(uninstalledDeviceTwo.getLastSeen()).thenReturn(Util.todayInMillis() - TimeUnit.DAYS.toMillis(3));
    when(uninstalledDeviceTwo.isEnabled()).thenReturn(true);

    when(installedDevice.getUninstalledFeedbackTimestamp()).thenReturn(0L);
    when(installedDevice.isEnabled()).thenReturn(true);
    when(installedDeviceTwo.getUninstalledFeedbackTimestamp()).thenReturn(0L);
    when(installedDeviceTwo.isEnabled()).thenReturn(true);

    when(recentUninstalledDevice.getUninstalledFeedbackTimestamp()).thenReturn(
        Util.todayInMillis() - TimeUnit.DAYS.toMillis(1));
    when(recentUninstalledDevice.getLastSeen()).thenReturn(Util.todayInMillis());
    when(recentUninstalledDevice.isEnabled()).thenReturn(true);

    when(stillActiveDevice.getUninstalledFeedbackTimestamp()).thenReturn(
        Util.todayInMillis() - TimeUnit.DAYS.toMillis(2));
    when(stillActiveDevice.getLastSeen()).thenReturn(Util.todayInMillis());

    when(stillActiveDevice.isEnabled()).thenReturn(true);

    when(uninstalledAccount.getDevices()).thenReturn(Set.of(uninstalledDevice));
    when(mixedAccount.getDevices()).thenReturn(Set.of(installedDevice, uninstalledDeviceTwo));
    when(freshAccount.getDevices()).thenReturn(Set.of(recentUninstalledDevice));
    when(cleanAccount.getDevices()).thenReturn(Set.of(installedDeviceTwo));
    when(stillActiveAccount.getDevices()).thenReturn(Set.of(stillActiveDevice));

    when(mixedAccount.getUuid()).thenReturn(UUID.randomUUID());
    when(freshAccount.getUuid()).thenReturn(UUID.randomUUID());
    when(cleanAccount.getUuid()).thenReturn(UUID.randomUUID());
    when(stillActiveAccount.getUuid()).thenReturn(UUID.randomUUID());

    when(uninstalledAccount.isEnabled()).thenReturn(true);
    when(uninstalledAccount.isDiscoverableByUserLogin()).thenReturn(true);
    when(uninstalledAccount.getUuid()).thenReturn(UUID.randomUUID());
    when(uninstalledAccount.getUserLogin()).thenReturn("+18005551234");

    AccountsHelper.setupMockGet(accountsManager,
        Set.of(uninstalledAccount, mixedAccount, freshAccount, cleanAccount, stillActiveAccount));
  }

  @Test
  void testEmpty() throws AccountDatabaseCrawlerRestartException {
    PushFeedbackProcessor processor = new PushFeedbackProcessor(accountsManager);
    processor.timeAndProcessCrawlChunk(Optional.of(UUID.randomUUID()), Collections.emptyList());

    verifyNoInteractions(accountsManager);
  }

  @Test
  void testUpdate() throws AccountDatabaseCrawlerRestartException {
    PushFeedbackProcessor processor = new PushFeedbackProcessor(accountsManager);
    processor.timeAndProcessCrawlChunk(Optional.of(UUID.randomUUID()),
        List.of(uninstalledAccount, mixedAccount, stillActiveAccount, freshAccount, cleanAccount));

    verify(uninstalledDevice).setApnId(isNull());
    verify(uninstalledDevice).setGcmId(isNull());
    verify(uninstalledDevice).setFetchesMessages(eq(false));
    when(uninstalledDevice.isEnabled()).thenReturn(false);

    verify(accountsManager).update(eqUuid(uninstalledAccount), any());

    verify(uninstalledDeviceTwo).setApnId(isNull());
    verify(uninstalledDeviceTwo).setGcmId(isNull());
    verify(uninstalledDeviceTwo).setFetchesMessages(eq(false));
    when(uninstalledDeviceTwo.isEnabled()).thenReturn(false);

    verify(installedDevice, never()).setApnId(any());
    verify(installedDevice, never()).setGcmId(any());
    verify(installedDevice, never()).setFetchesMessages(anyBoolean());

    verify(accountsManager).update(eqUuid(mixedAccount), any());

    verify(recentUninstalledDevice, never()).setApnId(any());
    verify(recentUninstalledDevice, never()).setGcmId(any());
    verify(recentUninstalledDevice, never()).setFetchesMessages(anyBoolean());

    verify(accountsManager, never()).update(eqUuid(freshAccount), any());

    verify(installedDeviceTwo, never()).setApnId(any());
    verify(installedDeviceTwo, never()).setGcmId(any());
    verify(installedDeviceTwo, never()).setFetchesMessages(anyBoolean());

    verify(accountsManager, never()).update(eqUuid(cleanAccount), any());

    verify(stillActiveDevice).setUninstalledFeedbackTimestamp(eq(0L));
    verify(stillActiveDevice, never()).setApnId(any());
    verify(stillActiveDevice, never()).setGcmId(any());
    verify(stillActiveDevice, never()).setFetchesMessages(anyBoolean());
    when(stillActiveDevice.getUninstalledFeedbackTimestamp()).thenReturn(0L);

    verify(accountsManager).update(eqUuid(stillActiveAccount), any());

    // there are un-verified calls to updateDevice
    clearInvocations(accountsManager);

    // a second crawl should not make any further updates
    processor.timeAndProcessCrawlChunk(Optional.of(UUID.randomUUID()),
        List.of(uninstalledAccount, mixedAccount, stillActiveAccount, freshAccount, cleanAccount));

    verify(accountsManager, never()).update(any(Account.class), any());
  }
}