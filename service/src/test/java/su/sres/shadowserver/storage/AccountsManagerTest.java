/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import org.junit.Test;

import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.util.RedisClusterHelper;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AccountsManagerTest {

  @Test
  public void testGetAccountByUserLoginInCache() {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();

    when(commands.get(eq("AccountMap::johndoe"))).thenReturn(uuid.toString());
    when(commands.get(eq("Account3::" + uuid.toString()))).thenReturn("{\"userLogin\": \"johndoe\", \"name\": \"test\"}");

    AccountsManager accountsManager = new AccountsManager(accounts, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> account = accountsManager.get("johndoe");

    assertTrue(account.isPresent());
    assertEquals(account.get().getUserLogin(), "johndoe");
    assertEquals(account.get().getProfileName(), "test");

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).get(eq("Account3::" + uuid.toString()));
    verifyNoMoreInteractions(commands);
    verifyNoMoreInteractions(accounts);
  }

  @Test
  public void testGetAccountByUuidInCache() {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();

    when(commands.get(eq("Account3::" + uuid.toString()))).thenReturn("{\"userLogin\": \"johndoe\", \"name\": \"test\"}");

    AccountsManager accountsManager = new AccountsManager(accounts, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> account = accountsManager.get(uuid);

    assertTrue(account.isPresent());
    assertEquals(account.get().getUserLogin(), "johndoe");
    assertEquals(account.get().getUuid(), uuid);
    assertEquals(account.get().getProfileName(), "test");

    verify(commands, times(1)).get(eq("Account3::" + uuid.toString()));
    verifyNoMoreInteractions(commands);
    verifyNoMoreInteractions(accounts);
  }

  @Test
  public void testGetAccountByUserLoginNotInCache() {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("AccountMap::johndoe"))).thenReturn(null);
    when(accounts.get(eq("johndoe"))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get("johndoe");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq("johndoe"));
    verifyNoMoreInteractions(accounts);
  }

  @Test
  public void testGetAccountByUuidNotInCache() {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("Account3::" + uuid))).thenReturn(null);
    when(accounts.get(eq(uuid))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get(uuid);

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq(uuid));
    verifyNoMoreInteractions(accounts);
  }

  @Test
  public void testGetAccountByUserLoginBrokenCache() {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("AccountMap::johndoe"))).thenThrow(new RedisException("Connection lost!"));
    when(accounts.get(eq("johndoe"))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get("johndoe");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("AccountMap::johndoe"));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq("johndoe"));
    verifyNoMoreInteractions(accounts);
  }

  @Test
  public void testGetAccountByUuidBrokenCache() {
    RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
    FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
    Accounts accounts = mock(Accounts.class);
    DirectoryManager directoryManager = mock(DirectoryManager.class);
    KeysScyllaDb keysScyllaDb = mock(KeysScyllaDb.class);
    MessagesManager messagesManager = mock(MessagesManager.class);
    UsernamesManager usernamesManager = mock(UsernamesManager.class);
    ProfilesManager profilesManager = mock(ProfilesManager.class);

    UUID uuid = UUID.randomUUID();
    Account account = new Account("johndoe", uuid, new HashSet<>(), new byte[16]);

    when(commands.get(eq("Account3::" + uuid))).thenThrow(new RedisException("Connection lost!"));
    when(accounts.get(eq(uuid))).thenReturn(Optional.of(account));

    AccountsManager accountsManager = new AccountsManager(accounts, directoryManager, cacheCluster, keysScyllaDb, messagesManager, usernamesManager, profilesManager);
    Optional<Account> retrieved = accountsManager.get(uuid);

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), account);

    verify(commands, times(1)).get(eq("Account3::" + uuid));
    verify(commands, times(1)).set(eq("AccountMap::johndoe"), eq(uuid.toString()));
    verify(commands, times(1)).set(eq("Account3::" + uuid.toString()), anyString());
    verifyNoMoreInteractions(commands);

    verify(accounts, times(1)).get(eq(uuid));
    verifyNoMoreInteractions(accounts);
  }
}