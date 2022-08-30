/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.push;

import io.lettuce.core.cluster.SlotHash;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.redis.AbstractRedisClusterTest;
import su.sres.shadowserver.redis.RedisException;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.util.Pair;
import redis.clients.jedis.Jedis;
import redis.embedded.RedisServer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ApnFallbackManagerTest extends AbstractRedisClusterTest {

  private Account account;
  private Device device;

  private APNSender apnSender;

  private ApnFallbackManager apnFallbackManager;

  private static final UUID ACCOUNT_UUID = UUID.randomUUID();
  private static final String ACCOUNT_NUMBER = "+18005551234";
  private static final long DEVICE_ID = 1L;
  private static final String VOIP_APN_ID = RandomStringUtils.randomAlphanumeric(32);

  @Before
  public void setUp() throws Exception {
    super.setUp();

    device = mock(Device.class);
    when(device.getId()).thenReturn(DEVICE_ID);
    when(device.getVoipApnId()).thenReturn(VOIP_APN_ID);
    when(device.getLastSeen()).thenReturn(System.currentTimeMillis());

    account = mock(Account.class);
    when(account.getUuid()).thenReturn(ACCOUNT_UUID);
    when(account.getUserLogin()).thenReturn(ACCOUNT_NUMBER);
    when(account.getDevice(DEVICE_ID)).thenReturn(Optional.of(device));

    final AccountsManager accountsManager = mock(AccountsManager.class);
    when(accountsManager.get(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
    when(accountsManager.get(ACCOUNT_UUID)).thenReturn(Optional.of(account));

    apnSender = mock(APNSender.class);

    apnFallbackManager = new ApnFallbackManager(getRedisCluster(), apnSender, accountsManager);
  }

  @Test
  public void testClusterInsert() throws RedisException {
    final String endpoint = apnFallbackManager.getEndpointKey(account, device);

    assertTrue(apnFallbackManager.getPendingDestinations(SlotHash.getSlot(endpoint), 1).isEmpty());

    apnFallbackManager.schedule(account, device, System.currentTimeMillis() - 30_000);

    final List<String> pendingDestinations = apnFallbackManager.getPendingDestinations(SlotHash.getSlot(endpoint), 2);
    assertEquals(1, pendingDestinations.size());

    final Optional<Pair<String, Long>> maybeUuidAndDeviceId = ApnFallbackManager.getSeparated(pendingDestinations.get(0));

    assertTrue(maybeUuidAndDeviceId.isPresent());
    assertEquals(ACCOUNT_UUID.toString(), maybeUuidAndDeviceId.get().first());
    assertEquals(DEVICE_ID, (long) maybeUuidAndDeviceId.get().second());

    assertTrue(apnFallbackManager.getPendingDestinations(SlotHash.getSlot(endpoint), 1).isEmpty());
  }

  @Test
  public void testProcessNextSlot() throws RedisException {
    final ApnFallbackManager.NotificationWorker worker = apnFallbackManager.new NotificationWorker();

    apnFallbackManager.schedule(account, device, System.currentTimeMillis() - 30_000);

    final int slot = SlotHash.getSlot(apnFallbackManager.getEndpointKey(account, device));
    final int previousSlot = (slot + SlotHash.SLOT_COUNT - 1) % SlotHash.SLOT_COUNT;

    getRedisCluster().withCluster(connection -> connection.sync().set(ApnFallbackManager.NEXT_SLOT_TO_PERSIST_KEY, String.valueOf(previousSlot)));

    assertEquals(1, worker.processNextSlot());

    final ArgumentCaptor<ApnMessage> messageCaptor = ArgumentCaptor.forClass(ApnMessage.class);
    verify(apnSender).sendMessage(messageCaptor.capture());

    final ApnMessage message = messageCaptor.getValue();

    assertEquals(VOIP_APN_ID, message.getApnId());
    assertEquals(ACCOUNT_NUMBER, message.getNumber());
    assertEquals(DEVICE_ID, message.getDeviceId());

    assertEquals(0, worker.processNextSlot());
  }
}
