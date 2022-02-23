/*
 * Copyright (C) 2019 Open WhisperSystems
 * Modified version copyright (C) 2020 Sophisticated Research
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

public class AccountCleanerTest {

  private final AccountsManager accountsManager = mock(AccountsManager.class);
  
  private final Account deletedDisabledAccount   = mock(Account.class);
  private final Account undeletedDisabledAccount = mock(Account.class);
  private final Account undeletedEnabledAccount  = mock(Account.class);

  private final Device  deletedDisabledDevice    = mock(Device.class );
  private final Device  undeletedDisabledDevice  = mock(Device.class );
  private final Device  undeletedEnabledDevice   = mock(Device.class );
  
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

    verify(deletedDisabledDevice, never()).setGcmId(any());
    verify(deletedDisabledDevice, never()).setApnId(any());
    verify(deletedDisabledDevice, never()).setVoipApnId(any());
    verify(deletedDisabledDevice, never()).setFetchesMessages(anyBoolean());

    verify(accountsManager, never()).update(eq(deletedDisabledAccount));
    
    verify(undeletedDisabledDevice, times(1)).setGcmId(isNull());
    verify(undeletedDisabledDevice, times(1)).setApnId(isNull());
    verify(undeletedDisabledDevice, times(1)).setVoipApnId(isNull());
    verify(undeletedDisabledDevice, times(1)).setFetchesMessages(eq(false));

    verify(accountsManager, times(1)).update(eq(undeletedDisabledAccount));
    
    verify(undeletedEnabledDevice, never()).setGcmId(any());
    verify(undeletedEnabledDevice, never()).setApnId(any());
    verify(undeletedEnabledDevice, never()).setVoipApnId(any());
    verify(undeletedEnabledDevice, never()).setFetchesMessages(anyBoolean());

    verify(accountsManager, never()).update(eq(undeletedEnabledAccount));
    
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

	    verify(undeletedDisabledDevice, times(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK)).setGcmId(isNull());
	    verify(undeletedDisabledDevice, times(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK)).setApnId(isNull());
	    verify(undeletedDisabledDevice, times(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK)).setVoipApnId(isNull());
	    verify(undeletedDisabledDevice, times(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK)).setFetchesMessages(eq(false));

	    verify(accountsManager, times(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK)).update(eq(undeletedDisabledAccount));
	    
	    verify(deletedDisabledDevice, never()).setGcmId(any());
	    verify(deletedDisabledDevice, never()).setApnId(any());
	    verify(deletedDisabledDevice, never()).setFetchesMessages(anyBoolean());

	    verify(undeletedEnabledDevice, never()).setGcmId(any());
	    verify(undeletedEnabledDevice, never()).setApnId(any());
	    verify(undeletedEnabledDevice, never()).setVoipApnId(any());
	    verify(undeletedEnabledDevice, never()).setFetchesMessages(anyBoolean());

	    verifyNoMoreInteractions(accountsManager);	    
  }

}