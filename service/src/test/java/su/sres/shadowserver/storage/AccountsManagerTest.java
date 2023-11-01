/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import redis.clients.jedis.Jedis;

import org.junit.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Device.DeviceCapabilities;
import su.sres.shadowserver.util.JsonHelpers;
import su.sres.shadowserver.util.RedisClusterHelper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AccountsManagerTest {
  
  private Accounts accounts;
  private AccountsScyllaDb accountsScyllaDb;
  private DirectoryManager directory;
  
  private RedisAdvancedClusterCommands<String, String> commands;
  private AccountsManager accountsManager;
  private Jedis jedis;
  private KeysScyllaDb keys;
  private MessagesManager messagesManager;
  private ProfilesManager profilesManager;
  
  private static final Answer<?> ACCOUNT_UPDATE_ANSWER = (answer) -> {
    // it is implicit in the update() contract is that a successful call will
    // result in an incremented version
    final Account updatedAccount = answer.getArgument(0, Account.class);
    updatedAccount.setVersion(updatedAccount.getVersion() + 1);
    return null;
  };
  
  @BeforeEach
  void setup() {
    accounts = mock(Accounts.class);
    accountsScyllaDb = mock(AccountsScyllaDb.class);
    directory = new DirectoryManager(mock(ReplicatedJedisPool.class));
    jedis = mock(Jedis.class);
    keys = mock(KeysScyllaDb.class);
    messagesManager = mock(MessagesManager.class);
    profilesManager = mock(ProfilesManager.class);
    
    //noinspection unchecked
    commands = mock(RedisAdvancedClusterCommands.class);
    
    accountsManager = new AccountsManager(
        accounts,
        accountsScyllaDb,
        directory,
        RedisClusterHelper.buildMockRedisCluster(commands),
        mock(DeletedAccounts.class),
        keys,
        messagesManager,
        mock(MigrationMismatchedAccounts.class),
        mock(UsernamesManager.class),
        profilesManager,
        mock(StoredVerificationCodeManager.class));
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByNumberInCache(final boolean dynamoEnabled) {
    
    UUID uuid = UUID.randomUUID();
        
    when(commands.get(eq("AccountMap::johndoe"))).thenReturn(uuid.toString());
    when(commands.get(eq("Account3::" + uuid))).thenReturn("{\"userLogin\": \"johndoe\", \"name\": \"test\"}");

    Optional<Account> account = accountsManager.get("johndoe");

    assertTrue(account.isPresent());
    assertEquals(account.get().getUserLogin(), "johndoe");
    assertEquals(account.get().getProfileName(), "test");

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verifyNoMoreInteractions(commands);
    verifyNoMoreInteractions(accounts);
    
    verifyNoInteractions(accountsScyllaDb);
  }
   
    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUuidInCache(boolean dynamoEnabled) {
    
    UUID uuid = UUID.randomUUID();

    when(commands.get(eq("Account3::" + uuid))).thenReturn("{\"userLogin\": \"johndoe\", \"name\": \"test\"}");

    Optional<Account> account = accountsManager.get(uuid);

    assertTrue(account.isPresent());
    assertEquals(account.get().getUserLogin(), "johndoe");
    assertEquals(account.get().getUuid(), uuid);
    assertEquals(account.get().getProfileName(), "test");

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verifyNoMoreInteractions(commands);
    verifyNoMoreInteractions(accounts);
    
    verifyNoInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUserLoginNotInCache(boolean dynamoEnabled) {
    
    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("AccountMap::johndoe"))).thenReturn(null);
    when(accounts.get(eq("johndoe"))).thenReturn(Optional.of(account));

    Optional<Account> retrieved = accountsManager.get("johndoe");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid), anyString());
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
    
    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
    when(accounts.get(eq(uuid))).thenReturn(Optional.of(account));

    Optional<Account> retrieved = accountsManager.get(uuid);

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq(uuid));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).get(eq(uuid));
    verifyNoMoreInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUserLoginBrokenCache(boolean dynamoEnabled) {
    
    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("AccountMap::johndoe"))).thenThrow(new RedisException("Connection lost!"));
    when(accounts.get(eq("johndoe"))).thenReturn(Optional.of(account));
    
    Optional<Account> retrieved = accountsManager.get("johndoe");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq("johndoe"));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).get(eq("johndoe"));
    verifyNoMoreInteractions(accountsScyllaDb);
  }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testGetAccountByUuidBrokenCache(boolean dynamoEnabled) {
   
    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("Account3::" + uuid))).thenThrow(new RedisException("Connection lost!"));
    when(accounts.get(eq(uuid))).thenReturn(Optional.of(account));
    
    Optional<Account> retrieved = accountsManager.get(uuid);

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq(uuid));
    verifyNoMoreInteractions(accounts);
    
    verify(accountsScyllaDb, dynamoEnabled ? times(1) : never()).get(eq(uuid));
    verifyNoMoreInteractions(accountsScyllaDb);
  }
    
    @ParameterizedTest
    @ValueSource(booleans = {true})
    void testUpdate_dynamoDbMigration(boolean scyllaEnabled) throws IOException {
      
      UUID uuid = UUID.randomUUID();
      Account account = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);
      
      when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
      // database fetches should always return new instances
      when(accounts.get(uuid)).thenReturn(Optional.of(new Account("+14152222222", uuid, new HashSet<>(), new byte[16])));
      when(accountsScyllaDb.get(uuid)).thenReturn(Optional.of(new Account("+14152222222", uuid, new HashSet<>(), new byte[16])));
      doAnswer(ACCOUNT_UPDATE_ANSWER).when(accounts).update(any(Account.class));
      
      Account updatedAccount = accountsManager.update(account, a -> a.setProfileName("name"));

      assertThrows(AssertionError.class, account::getProfileName, "Account passed to update() should be stale");

      assertNotSame(updatedAccount, account);

      verify(accounts, times(1)).update(account);
      verifyNoMoreInteractions(accounts);

      if (scyllaEnabled) {
        ArgumentCaptor<Account> argumentCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountsScyllaDb, times(1)).update(argumentCaptor.capture());
        assertEquals(uuid, argumentCaptor.getValue().getUuid());
      } else {
        verify(accountsScyllaDb, never()).update(any());
      }
      verify(accountsScyllaDb, scyllaEnabled ? times(1) : never()).get(uuid);
      verifyNoMoreInteractions(accountsScyllaDb);

      ArgumentCaptor<String> redisSetArgumentCapture = ArgumentCaptor.forClass(String.class);

      verify(commands, times(2)).set(anyString(), redisSetArgumentCapture.capture());

      Account accountCached = JsonHelpers.fromJson(redisSetArgumentCapture.getAllValues().get(1), Account.class);

      // uuid is @JsonIgnore, so we need to set it for compareAccounts to work
      accountCached.setUuid(uuid);

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(updatedAccount), Optional.of(accountCached)));
    }

    @Test
    void testUpdate_dynamoMissing() {
        
      UUID                                         uuid                = UUID.randomUUID();
      Account                                      account             = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);
      
      when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
      when(accountsScyllaDb.get(uuid)).thenReturn(Optional.empty());
      doAnswer(ACCOUNT_UPDATE_ANSWER).when(accounts).update(any());
      doAnswer(ACCOUNT_UPDATE_ANSWER).when(accountsScyllaDb).update(any());    

      Account updatedAccount = accountsManager.update(account,  a -> {});

      verify(accounts, times(1)).update(account);
      verifyNoMoreInteractions(accounts);

      verify(accountsScyllaDb, never()).update(account);
      verify(accountsScyllaDb, times(1)).get(uuid);
      verifyNoMoreInteractions(accountsScyllaDb);
      
      assertEquals(1, updatedAccount.getVersion());
    }
    
    @Test
    void testUpdate_optimisticLockingFailure() {
            
      UUID                                         uuid                = UUID.randomUUID();
      Account                                      account             = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);
      
      long directoryVersion = 10L;
      
      when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
      when(accounts.get(uuid)).thenReturn(Optional.of(new Account("+14152222222", uuid, new HashSet<>(), new byte[16])));
      doThrow(ContestedOptimisticLockException.class)
          .doAnswer(ACCOUNT_UPDATE_ANSWER)
          .when(accounts).update(any());

      when(accountsScyllaDb.get(uuid)).thenReturn(Optional.of(new Account("+14152222222", uuid, new HashSet<>(), new byte[16])));
      doThrow(ContestedOptimisticLockException.class)
          .doAnswer(ACCOUNT_UPDATE_ANSWER)
          .when(accountsScyllaDb).update(any());
      
      account = accountsManager.update(account, a -> a.setProfileName("name"));

      assertEquals(1, account.getVersion());
      assertEquals("name", account.getProfileName());

      verify(accounts, times(1)).get(uuid);
      verify(accounts, times(2)).update(any());
      verifyNoMoreInteractions(accounts);

      // dynamo has an extra get() because the account is fetched before every update
      verify(accountsScyllaDb, times(2)).get(uuid);
      verify(accountsScyllaDb, times(2)).update(any());
      verifyNoMoreInteractions(accountsScyllaDb);
    }

    @Test
    void testUpdate_dynamoOptimisticLockingFailureDuringCreate() {
      
      UUID                                         uuid                = UUID.randomUUID();
      Account                                      account             = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);
      
      when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
      when(accountsScyllaDb.get(uuid)).thenReturn(Optional.empty())
                                      .thenReturn(Optional.of(account));
      when(accountsScyllaDb.create(any(), anyLong())).thenThrow(ContestedOptimisticLockException.class);     

      accountsManager.update(account, a -> {});

      verify(accounts, times(1)).update(account);
      verifyNoMoreInteractions(accounts);

      verify(accountsScyllaDb, times(1)).get(uuid);
      
      verifyNoMoreInteractions(accountsScyllaDb);
    }
    
    @Test
    void testUpdateDevice() {

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.empty(), Optional.empty()));

      final UUID uuid = UUID.randomUUID();
      Account account = new Account("+14152222222", uuid, new HashSet<>(), new byte[16]);

      when(accounts.get(uuid)).thenReturn(Optional.of(new Account("+14152222222", uuid, new HashSet<>(), new byte[16])));

      assertTrue(account.getDevices().isEmpty());

      Device enabledDevice = new Device();
      enabledDevice.setFetchesMessages(true);
      enabledDevice.setSignedPreKey(new SignedPreKey(1L, "key", "signature"));
      enabledDevice.setLastSeen(System.currentTimeMillis());
      final long deviceId = account.getNextDeviceId();
      enabledDevice.setId(deviceId);
      account.addDevice(enabledDevice);

      @SuppressWarnings("unchecked") Consumer<Device> deviceUpdater = mock(Consumer.class);
      @SuppressWarnings("unchecked") Consumer<Device> unknownDeviceUpdater = mock(Consumer.class);

      account = accountsManager.updateDevice(account, deviceId, deviceUpdater);
      account = accountsManager.updateDevice(account, deviceId, d -> d.setName("deviceName"));

      assertEquals("deviceName", account.getDevice(deviceId).orElseThrow().getName());

      verify(deviceUpdater, times(1)).accept(any(Device.class));

      accountsManager.updateDevice(account, account.getNextDeviceId(), unknownDeviceUpdater);

      verify(unknownDeviceUpdater, never()).accept(any(Device.class));
    }


    @Test
    void testCompareAccounts() throws Exception {
      
      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.empty(), Optional.empty()));

      final UUID uuidA = UUID.randomUUID();
      final Account a1 = new Account("+14152222222", uuidA, new HashSet<>(), new byte[16]);

      assertEquals(Optional.of("primaryMissing"), accountsManager.compareAccounts(Optional.empty(), Optional.of(a1)));

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

        assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
        
        a1.removeDevice(1L);
        a2.removeDevice(1L);

        assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
      }

      assertEquals(Optional.empty(), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

      a1.setVersion(1);

      assertEquals(Optional.of("version"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));

      a2.setVersion(1);

      a2.setProfileName("name");

      assertEquals(Optional.of("profileName"), accountsManager.compareAccounts(Optional.of(a1), Optional.of(a2)));
    }
    
    @Test
    void testCreateFreshAccount() throws InterruptedException {
      when(accounts.create(any(), anyLong())).thenReturn(true);

      final String e164 = "+18005550123";
      final AccountAttributes attributes = new AccountAttributes(false, 0, null, true, null);
      accountsManager.create(e164, "password", null, attributes);

      verify(accounts).create(argThat(account -> e164.equals(account.getUserLogin())), anyLong());
      verifyNoInteractions(keys);
      verifyNoInteractions(messagesManager);
      verifyNoInteractions(profilesManager);
    }

    @Test
    void testReregisterAccount() throws InterruptedException {
      final UUID existingUuid = UUID.randomUUID();

      when(accounts.create(any(), anyLong())).thenAnswer(invocation -> {
        invocation.getArgument(0, Account.class).setUuid(existingUuid);
        return false;
      });

      final String e164 = "+18005550123";
      final AccountAttributes attributes = new AccountAttributes(false, 0, null, true, null);
      accountsManager.create(e164, "password", null, attributes);

      verify(accounts).create(argThat(account -> e164.equals(account.getUserLogin()) && existingUuid.equals(account.getUuid())), anyLong());
      verify(keys).delete(existingUuid);
      verify(messagesManager).clear(existingUuid);
      verify(profilesManager).deleteAll(existingUuid);
    }

    @Test
    void testCreateAccountRecentlyDeleted() throws InterruptedException {
      final UUID recentlyDeletedUuid = UUID.randomUUID();

     /*  doAnswer(invocation -> {
        //noinspection unchecked
        invocation.getArgument(1, Consumer.class).accept(Optional.of(recentlyDeletedUuid));
        return null;
      }).when(deletedAccountsManager).lockAndTake(anyString(), any()); */

      when(accounts.create(any(), anyLong())).thenReturn(true);

      final String e164 = "+18005550123";
      final AccountAttributes attributes = new AccountAttributes(false, 0, null, true, null);
      accountsManager.create(e164, "password", null, attributes);

      verify(accounts).create(argThat(account -> e164.equals(account.getUserLogin()) && recentlyDeletedUuid.equals(account.getUuid())), anyLong());
      verifyNoInteractions(keys);
      verifyNoInteractions(messagesManager);
      verifyNoInteractions(profilesManager);
    }
    
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCreateWithDiscoverability(final boolean discoverable) {
      when(directory.accessDirectoryCache().getWriteResource()).thenReturn(jedis);
      
      final AccountAttributes attributes = new AccountAttributes(false, 0, null, discoverable, null);
      final Account account = accountsManager.create("+18005550123", "password", null, attributes);

      assertEquals(discoverable, account.isDiscoverableByUserLogin());     
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCreateWithStorageCapability(final boolean hasStorage) {      
      when(directory.accessDirectoryCache().getWriteResource()).thenReturn(jedis);
      
      final AccountAttributes attributes = new AccountAttributes(false, 0, null, true,
          new DeviceCapabilities(false, false, false, hasStorage, false, false, false, false, false));

      final Account account = accountsManager.create("+18005550123", "password", null, attributes);

      assertEquals(hasStorage, account.isStorageSupported());
    }
    
    @ParameterizedTest
    @MethodSource
    void testUpdateDeviceLastSeen(final boolean expectUpdate, final long initialLastSeen, final long updatedLastSeen) {
      final Account account = new Account("+14152222222", UUID.randomUUID(), new HashSet<>(), new byte[16]);
      final Device device = new Device(Device.MASTER_ID, "device", "token", "salt", null, null, null, true, 1,
          new SignedPreKey(1, "key", "sig"), initialLastSeen, 0,
          "OWT", 0, new DeviceCapabilities());
      account.addDevice(device);

      accountsManager.updateDeviceLastSeen(account, device, updatedLastSeen);

      assertEquals(expectUpdate ? updatedLastSeen : initialLastSeen, device.getLastSeen());
      verify(accounts, expectUpdate ? times(1) : never()).update(account);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> testUpdateDeviceLastSeen() {
      return Stream.of(
          Arguments.of(true, 1, 2),
          Arguments.of(false, 1, 1),
          Arguments.of(false, 2, 1)
      );
    }
}