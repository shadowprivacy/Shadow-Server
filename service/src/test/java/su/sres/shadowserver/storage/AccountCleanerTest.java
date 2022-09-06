/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;


public class AccountCleanerTest {

  private final AccountsManager accountsManager = mock(AccountsManager.class);

  private final Account deletedDisabledAccount = mock(Account.class);
  private final Account undeletedDisabledAccount = mock(Account.class);
  private final Account undeletedEnabledAccount = mock(Account.class);
  
  private final Device deletedDisabledDevice = mock(Device.class);
  private final Device undeletedDisabledDevice = mock(Device.class);
  private final Device undeletedEnabledDevice = mock(Device.class);    

  @Captor
  private ArgumentCaptor<HashSet<Account>> delCaptor;
  
  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when(deletedDisabledDevice.isEnabled()).thenReturn(false);
    when(deletedDisabledDevice.getGcmId()).thenReturn(null);
    when(deletedDisabledDevice.getApnId()).thenReturn(null);
    when(deletedDisabledDevice.getVoipApnId()).thenReturn(null);
    when(deletedDisabledDevice.getFetchesMessages()).thenReturn(false);
    when(deletedDisabledAccount.isEnabled()).thenReturn(false);
    when(deletedDisabledAccount.getLastSeen()).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1000));
    when(deletedDisabledAccount.getMasterDevice()).thenReturn(Optional.of(deletedDisabledDevice));
    when(deletedDisabledAccount.getUserLogin()).thenReturn("deleteddisabledacc");
    when(deletedDisabledAccount.getUuid()).thenReturn(UUID.randomUUID());

    when(undeletedDisabledDevice.isEnabled()).thenReturn(false);
    when(undeletedDisabledDevice.getGcmId()).thenReturn("foo");
    when(undeletedDisabledAccount.isEnabled()).thenReturn(false);
    when(undeletedDisabledAccount.getLastSeen()).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(366));
    when(undeletedDisabledAccount.getMasterDevice()).thenReturn(Optional.of(undeletedDisabledDevice));
    when(undeletedDisabledAccount.getUserLogin()).thenReturn("undeleteddisabledacc");
    when(undeletedDisabledAccount.getUuid()).thenReturn(UUID.randomUUID());

    when(undeletedEnabledDevice.isEnabled()).thenReturn(true);
    when(undeletedEnabledDevice.getApnId()).thenReturn("bar");
    when(undeletedEnabledAccount.isEnabled()).thenReturn(true);
    when(undeletedEnabledAccount.getMasterDevice()).thenReturn(Optional.of(undeletedEnabledDevice));
    when(undeletedEnabledAccount.getUserLogin()).thenReturn("undeletedenabledacc");
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
      String login = "undeletedDisabledAccountOfBatch" + addedAccountCount;
      final Account undeletedDisabledAccountOfBatch = mock(Account.class);
      when(undeletedDisabledAccountOfBatch.isEnabled()).thenReturn(false);
      when(undeletedDisabledAccountOfBatch.getLastSeen()).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(366));
      when(undeletedDisabledAccountOfBatch.getMasterDevice()).thenReturn(Optional.of(undeletedDisabledDevice));
      when(undeletedDisabledAccountOfBatch.getUserLogin()).thenReturn(login);
      when(undeletedDisabledAccountOfBatch.getUuid()).thenReturn(UUID.randomUUID());
      
      accounts.add(undeletedDisabledAccountOfBatch);      
    }
    
    accounts.add(deletedDisabledAccount);       

    AccountCleaner accountCleaner = new AccountCleaner(accountsManager);
    accountCleaner.onCrawlStart();
    accountCleaner.timeAndProcessCrawlChunk(Optional.empty(), accounts);
    accountCleaner.onCrawlEnd(Optional.empty());

    verify(accountsManager).delete(delCaptor.capture(), eq(AccountsManager.DeletionReason.EXPIRED));

    assertEquals(AccountCleaner.MAX_ACCOUNT_UPDATES_PER_CHUNK, delCaptor.getValue().size());
    
    assertEquals(false, delCaptor.getValue().contains(undeletedEnabledAccount));
    assertEquals(false, delCaptor.getValue().contains(deletedDisabledAccount));
        
    verifyNoMoreInteractions(accountsManager);
  }

}