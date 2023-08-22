/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import org.junit.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.util.RedisClusterHelper;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class AccountsManagerTest {
  
  @BeforeEach
  void setup() {}

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByNumberInCache(final boolean dynamoEnabled) {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
        
    when(commands.get(eq("AccountMap::johndoe"))).thenReturn(uuid.toString());
    when(commands.get(eq("Account3::" + uuid.toString()))).thenReturn("{\"userLogin\": \"johndoe\", \"name\": \"test\"}");

    AccountsManager accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> account = accountsManager.get("johndoe");

    assertTrue(account.isPresent());
    assertEquals(account.get().getUserLogin(), "johndoe");
    assertEquals(account.get().getProfileName(), "test");

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).get(eq("Account3::" + uuid.toString()));
    verifyNoMoreInteractions(commands);
    verifyNoMoreInteractions(accounts);
    
    verifyZeroInteractions(accountsScyllaDb);
  }
   
    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUuidInCache(boolean dynamoEnabled) {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();

    when(commands.get(eq("Account3::" + uuid.toString()))).thenReturn("{\"userLogin\": \"johndoe\", \"name\": \"test\"}");

    AccountsManager accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> account = accountsManager.get(uuid);

    assertTrue(account.isPresent());
    assertEquals(account.get().getUserLogin(), "johndoe");
    assertEquals(account.get().getUuid(), uuid);
    assertEquals(account.get().getProfileName(), "test");

    verify(commands, times(1)).get(eq("Account3::" + uuid.toString()));
    verifyNoMoreInteractions(commands);
    verifyNoMoreInteractions(accounts);
    
    verifyZeroInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUserLoginNotInCache(boolean dynamoEnabled) {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("AccountMap::johndoe"))).thenReturn(null);
    when(accounts.get(eq("johndoe"))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get("johndoe");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq("johndoe"));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never())
    .get(eq("johndoe"));
verifyNoMoreInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUuidNotInCache(boolean dynamoEnabled) {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
    when(accounts.get(eq(uuid))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get(uuid);

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq(uuid));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).get(eq(uuid));
    verifyNoMoreInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUserLoginBrokenCache(boolean dynamoEnabled) {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("AccountMap::johndoe"))).thenThrow(new RedisException("Connection lost!"));
    when(accounts.get(eq("johndoe"))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get("johndoe");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq("johndoe"));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).get(eq("johndoe"));
    verifyNoMoreInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUuidBrokenCache(boolean dynamoEnabled) {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("Account3::" + uuid))).thenThrow(new RedisException("Connection lost!"));
    when(accounts.get(eq(uuid))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get(uuid);

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq(uuid));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).get(eq(uuid));
    verifyNoMoreInteractions(accountsScyllaDb);
  }
    
    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testUpdate_dynamoDbMigration(boolean dynamoEnabled) {
      RedisAdvancedClusterCommands<String, String> commands            = mock(RedisAdvancedClusterCommands.class);
      FaultTolerantRedisCluster                    cacheCluster        = RedisClusterHelper.buildMockRedisCluster(commands);
      Accounts                                     accounts            = mock(Accounts.class);
      AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
      DirectoryManager directoryManager = mock(DirectoryManager.class);
      KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
      MessagesManager                              messagesManager     = mock(MessagesManager.class);
      UsernamesManager                             usernamesManager    = mock(UsernamesManager.class);
      ProfilesManager                              profilesManager     = mock(ProfilesManager.class);      
      UUID                                         uuid                = UUID.randomUUID();
      Account                                      account             = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);
      
      when(commands.get(eq("Account3::" + uuid))).thenReturn(null);

      AccountsManager   accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);

      assertEquals(0, account.getScyllaDbMigrationVersion());

      accountsManager.update(account);

      assertEquals(1, account.getScyllaDbMigrationVersion());

      verify(accounts, times(1)).update(account);
      verifyNoMoreInteractions(accounts);

      verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).update(account);
      verifyNoMoreInteractions(accountsScyllaDb);
    }
    
    @Test
    void testUpdate_dynamoConditionFailed() {
      RedisAdvancedClusterCommands<String, String> commands            = mock(RedisAdvancedClusterCommands.class);
      FaultTolerantRedisCluster                    cacheCluster        = RedisClusterHelper.buildMockRedisCluster(commands);
      Accounts                                     accounts            = mock(Accounts.class);
      AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
      DirectoryManager directoryManager = mock(DirectoryManager.class);
      KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
      MessagesManager                              messagesManager     = mock(MessagesManager.class);
      UsernamesManager                             usernamesManager    = mock(UsernamesManager.class);
      ProfilesManager                              profilesManager     = mock(ProfilesManager.class);      
      UUID                                         uuid                = UUID.randomUUID();
      Account                                      account             = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);
      
      long directoryVersion = 10L;
      
      when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
      doThrow(ConditionalCheckFailedException.class).when(accountsScyllaDb).update(any(Account.class));

      AccountsManager   accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);

      assertEquals(0, account.getScyllaDbMigrationVersion());

      accountsManager.update(account);

      assertEquals(1, account.getScyllaDbMigrationVersion());

      verify(accounts, times(1)).update(account);
      verifyNoMoreInteractions(accounts);

      verify(accountsScyllaDb, times(1)).update(account);
      verify(accountsScyllaDb, times(1)).create(account, directoryVersion);
      verifyNoMoreInteractions(accountsScyllaDb);
    }


    @Test
    void testCompareAccounts() throws Exception {
      RedisAdvancedClusterCommands<String, String> commands            = mock(RedisAdvancedClusterCommands.class);
      FaultTolerantRedisCluster                    cacheCluster        = RedisClusterHelper.buildMockRedisCluster(commands);
      Accounts                                     accounts            = mock(Accounts.class);
      AccountsScyllaDb accountsScyllaDb = mock(AccountsScyllaDb.class);
      DirectoryManager directoryManager = mock(DirectoryManager.class);
      KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
      MessagesManager                              messagesManager     = mock(MessagesManager.class);
      UsernamesManager                             usernamesManager    = mock(UsernamesManager.class);
      ProfilesManager                              profilesManager     = mock(ProfilesManager.class);
      
      AccountsManager   accountsManager = new AccountsManager(accounts, accountsScyllaDb, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.empty(), Optional.empty()));

      final UUID uuidA = UUID.randomUUID();
      final Account a1 = new Account("+14152222222", uuidA, new HashSet<>(), new byte[16]);

      assertEquals(Optional.of("dbMissing"), accountsManager.compareAccounts(Optional.empty(), Optional.of(a1)));

      final Account a2 = new Account("+14152222222", uuidA, new HashSet<>(), new byte[16]);

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
      
      {
        Device device1 = new Device();
        device1.setId(1L);

        a1.addDevice(device1);

        assertEquals(Optional.of("devices"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

        Device device2 = new Device();
        device2.setId(1L);

        a2.addDevice(device2);

        assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

        device1.setLastSeen(1L);

        assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

        device1.setName("name");

        assertEquals(Optional.of("devices"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
        
        device1.setName(null);
        
        device1.setSignedPreKey(new SignedPreKey(1L, "123", "456"));
        device2.setSignedPreKey(new SignedPreKey(2L, "123", "456"));

        assertEquals(Optional.of("masterDeviceSignedPreKey"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

        device1.setSignedPreKey(null);
        device2.setSignedPreKey(null);

        assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

        device1.setApnId("123");
        Thread.sleep(5);
        device2.setApnId("123");

        assertEquals(Optional.of("masterDevicePushTimestamp"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
        
        a1.removeDevice(1L);
        a2.removeDevice(1L);

        assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
      }

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

      a1.setScyllaDbMigrationVersion(1);

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

      a2.setProfileName("name");

      assertEquals(Optional.of("profileName"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
    }
}