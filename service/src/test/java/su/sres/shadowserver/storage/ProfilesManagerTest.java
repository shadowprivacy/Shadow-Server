/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import org.junit.Test;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.storage.Profiles;
import su.sres.shadowserver.storage.ProfilesManager;
import su.sres.shadowserver.storage.VersionedProfile;
import su.sres.shadowserver.util.Base64;
import su.sres.shadowserver.util.RedisClusterHelper;

import java.util.Optional;
import java.util.UUID;

import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.assertTrue;
import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import redis.clients.jedis.exceptions.JedisException;

public class ProfilesManagerTest {

    @Test
    public void testGetProfileInCache() {
	RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
	FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
	Profiles profiles = mock(Profiles.class);

	UUID uuid = UUID.randomUUID();

	when(commands.hget(eq("profiles::" + uuid.toString()), eq("someversion"))).thenReturn("{\"version\": \"someversion\", \"name\": \"somename\", \"avatar\": \"someavatar\", \"commitment\":\"" + Base64.encodeBytes("somecommitment".getBytes()) + "\"}");

	ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
	Optional<VersionedProfile> profile = profilesManager.get(uuid, "someversion");

	assertTrue(profile.isPresent());
	assertEquals(profile.get().getName(), "somename");
	assertEquals(profile.get().getAvatar(), "someavatar");
	assertThat(profile.get().getCommitment()).isEqualTo("somecommitment".getBytes());

	verify(commands, times(1)).hget(eq("profiles::" + uuid.toString()), eq("someversion"));
	verifyNoMoreInteractions(commands);
	verifyNoMoreInteractions(profiles);
    }

    @Test
    public void testGetProfileNotInCache() {
	RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
	FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
	Profiles profiles = mock(Profiles.class);

	UUID uuid = UUID.randomUUID();
	VersionedProfile profile = new VersionedProfile("someversion", "somename", "someavatar", "somecommitment".getBytes());

	when(commands.hget(eq("profiles::" + uuid.toString()), eq("someversion"))).thenReturn(null);
	when(profiles.get(eq(uuid), eq("someversion"))).thenReturn(Optional.of(profile));

	ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
	Optional<VersionedProfile> retrieved = profilesManager.get(uuid, "someversion");

	assertTrue(retrieved.isPresent());
	assertSame(retrieved.get(), profile);

	verify(commands, times(1)).hget(eq("profiles::" + uuid.toString()), eq("someversion"));
	verify(commands, times(1)).hset(eq("profiles::" + uuid.toString()), eq("someversion"), anyString());
	verifyNoMoreInteractions(commands);

	verify(profiles, times(1)).get(eq(uuid), eq("someversion"));
	verifyNoMoreInteractions(profiles);
    }

    @Test
    public void testGetProfileBrokenCache() {
	RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
	FaultTolerantRedisCluster cacheCluster = RedisClusterHelper.buildMockRedisCluster(commands);
	Profiles profiles = mock(Profiles.class);

	UUID uuid = UUID.randomUUID();
	VersionedProfile profile = new VersionedProfile("someversion", "somename", "someavatar", "somecommitment".getBytes());

	when(commands.hget(eq("profiles::" + uuid.toString()), eq("someversion"))).thenThrow(new RedisException("Connection lost"));
	when(profiles.get(eq(uuid), eq("someversion"))).thenReturn(Optional.of(profile));

	ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
	Optional<VersionedProfile> retrieved = profilesManager.get(uuid, "someversion");

	assertTrue(retrieved.isPresent());
	assertSame(retrieved.get(), profile);

	verify(commands, times(1)).hget(eq("profiles::" + uuid.toString()), eq("someversion"));
	verify(commands, times(1)).hset(eq("profiles::" + uuid.toString()), eq("someversion"), anyString());
	verifyNoMoreInteractions(commands);

	verify(profiles, times(1)).get(eq(uuid), eq("someversion"));
	verifyNoMoreInteractions(profiles);
    }
}
