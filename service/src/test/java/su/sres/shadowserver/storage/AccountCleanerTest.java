/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import org.junit.Before;
import org.junit.Test;

import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountCleaner;
import su.sres.shadowserver.storage.AccountDatabaseCrawlerRestartException;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.util.AuthHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

public class AccountCleanerTest {

    private final AccountsManager accountsManager = mock(AccountsManager.class);

    private final Account deletedDisabledAccount = mock(Account.class);
    private final Account undeletedDisabledAccount = mock(Account.class);
    private final Account undeletedEnabledAccount = mock(Account.class);

    private final Device deletedDisabledDevice = mock(Device.class);
    private final Device undeletedDisabledDevice = mock(Device.class);
    private final Device undeletedEnabledDevice = mock(Device.class);

    @Before
    public void setup() {
	when(deletedDisabledDevice.isEnabled()).thenReturn(false);
	when(deletedDisabledDevice.getGcmId()).thenReturn(null);
	when(deletedDisabledDevice.getApnId()).thenReturn(null);
	when(deletedDisabledDevice.getVoipApnId()).thenReturn(null);
	when(deletedDisabledDevice.getFetchesMessages()).thenReturn(false);
	when(deletedDisabledAccount.isEnabled()).thenReturn(false);
	when(deletedDisabledAccount.getLastSeen()).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1000));
	when(deletedDisabledAccount.getMasterDevice()).thenReturn(Optional.of(deletedDisabledDevice));
	when(deletedDisabledAccount.getUserLogin()).thenReturn("+14151231234");
	when(deletedDisabledAccount.getUuid()).thenReturn(UUID.randomUUID());

	when(undeletedDisabledDevice.isEnabled()).thenReturn(false);
	when(undeletedDisabledDevice.getGcmId()).thenReturn("foo");
	when(undeletedDisabledAccount.isEnabled()).thenReturn(false);
	when(undeletedDisabledAccount.getLastSeen()).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(366));
	when(undeletedDisabledAccount.getMasterDevice()).thenReturn(Optional.of(undeletedDisabledDevice));
	when(undeletedDisabledAccount.getUserLogin()).thenReturn("+14152222222");
	when(undeletedDisabledAccount.getUuid()).thenReturn(UUID.randomUUID());

	when(undeletedEnabledDevice.isEnabled()).thenReturn(true);
	when(undeletedEnabledDevice.getApnId()).thenReturn("bar");
	when(undeletedEnabledAccount.isEnabled()).thenReturn(true);
	when(undeletedEnabledAccount.getMasterDevice()).thenReturn(Optional.of(undeletedEnabledDevice));
	when(undeletedEnabledAccount.getUserLogin()).thenReturn("+14153333333");
	when(undeletedEnabledAccount.getLastSeen()).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(364));
	when(undeletedEnabledAccount.getUuid()).thenReturn(UUID.randomUUID());
    }

    @Test
    public void testAccounts() throws AccountDatabaseCrawlerRestartException {
	AccountCleaner accountCleaner = new AccountCleaner(accountsManager);
	accountCleaner.onCrawlStart();
	accountCleaner.timeAndProcessCrawlChunk(Optional.empty(), Arrays.asList(deletedDisabledAccount, undeletedDisabledAccount, undeletedEnabledAccount));
	accountCleaner.onCrawlEnd(Optional.empty());

	HashSet<Account> accountsToDelete1 = new HashSet<Account>();
	HashSet<Account> accountsToDelete2 = new HashSet<Account>();
	HashSet<Account> accountsToDelete3 = new HashSet<Account>();
	accountsToDelete1.add(deletedDisabledAccount);
	accountsToDelete2.add(undeletedDisabledAccount);
	accountsToDelete3.add(undeletedEnabledAccount);

	verify(accountsManager, never()).delete(eq(accountsToDelete1), any());
	verify(accountsManager).delete(accountsToDelete2, AccountsManager.DeletionReason.EXPIRED);
	verify(accountsManager, never()).delete(eq(accountsToDelete3), any());

	verifyNoMoreInteractions(accountsManager);
    }

    @Test
    public void testMaxAccountUpdates() throws AccountDatabaseCrawlerRestartException {
	List<Account> accounts = new LinkedList<>();
	accounts.add(undeletedEnabledAccount);

	int activeExpiredAccountCount = AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK + 1;
	for (int addedAccountCount = 0; addedAccountCount < activeExpiredAccountCount; addedAccountCount++) {
	    accounts.add(undeletedDisabledAccount);
	}

	accounts.add(deletedDisabledAccount);

	AccountCleaner accountCleaner = new AccountCleaner(accountsManager);
	accountCleaner.onCrawlStart();
	accountCleaner.timeAndProcessCrawlChunk(Optional.empty(), accounts);
	accountCleaner.onCrawlEnd(Optional.empty());
	
	HashSet<Account> accountsToDelete = new HashSet<Account>();	
	accountsToDelete.add(deletedDisabledAccount);

	verify(accountsManager, times(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK)).delete(accountsToDelete, AccountsManager.DeletionReason.EXPIRED);

	verifyNoMoreInteractions(accountsManager);
    }

}