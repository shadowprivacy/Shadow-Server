package su.sres.shadowserver.tests.storage;

import org.junit.Test;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.storage.Profiles;
import su.sres.shadowserver.storage.ProfilesManager;
import su.sres.shadowserver.storage.VersionedProfile;
import su.sres.shadowserver.util.Base64;

import java.util.Optional;
import java.util.UUID;

import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.assertTrue;
import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

public class ProfilesManagerTest {

  @Test
  public void testGetProfileInCache() {
    ReplicatedJedisPool cacheClient      = mock(ReplicatedJedisPool.class);
    Jedis               jedis            = mock(Jedis.class              );
    Profiles            profiles         = mock(Profiles.class           );

    UUID uuid = UUID.randomUUID();

    when(cacheClient.getReadResource()).thenReturn(jedis);
    when(jedis.hget(eq("profiles::" + uuid.toString()), eq("someversion"))).thenReturn("{\"version\": \"someversion\", \"name\": \"somename\", \"avatar\": \"someavatar\", \"commitment\":\"" + Base64.encodeBytes("somecommitment".getBytes()) + "\"}");

    ProfilesManager            profilesManager = new ProfilesManager(profiles, cacheClient);
    Optional<VersionedProfile> profile         = profilesManager.get(uuid, "someversion");

    assertTrue(profile.isPresent());
    assertEquals(profile.get().getName(), "somename");
    assertEquals(profile.get().getAvatar(), "someavatar");
    assertThat(profile.get().getCommitment()).isEqualTo("somecommitment".getBytes());

    verify(jedis, times(1)).hget(eq("profiles::" + uuid.toString()), eq("someversion"));
    verify(jedis, times(1)).close();
    verifyNoMoreInteractions(jedis);
    verifyNoMoreInteractions(profiles);
  }

  @Test
  public void testGetProfileNotInCache() {
    ReplicatedJedisPool cacheClient = mock(ReplicatedJedisPool.class);
    Jedis               jedis       = mock(Jedis.class              );
    Profiles            profiles    = mock(Profiles.class           );

    UUID             uuid    = UUID.randomUUID();
    VersionedProfile profile = new VersionedProfile("someversion", "somename", "someavatar", "somecommitment".getBytes());

    when(cacheClient.getReadResource()).thenReturn(jedis);
    when(cacheClient.getWriteResource()).thenReturn(jedis);
    when(jedis.hget(eq("profiles::" + uuid.toString()), eq("someversion"))).thenReturn(null);
    when(profiles.get(eq(uuid), eq("someversion"))).thenReturn(Optional.of(profile));

    ProfilesManager            profilesManager = new ProfilesManager(profiles, cacheClient);
    Optional<VersionedProfile> retrieved       = profilesManager.get(uuid, "someversion");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), profile);

    verify(jedis, times(1)).hget(eq("profiles::" + uuid.toString()), eq("someversion"));
    verify(jedis, times(1)).hset(eq("profiles::" + uuid.toString()), eq("someversion"), anyString());
    verify(jedis, times(2)).close();
    verifyNoMoreInteractions(jedis);

    verify(profiles, times(1)).get(eq(uuid), eq("someversion"));
    verifyNoMoreInteractions(profiles);
  }

  @Test
  public void testGetProfileBrokenCache() {
    ReplicatedJedisPool cacheClient = mock(ReplicatedJedisPool.class);
    Jedis               jedis       = mock(Jedis.class              );
    Profiles            profiles    = mock(Profiles.class           );

    UUID             uuid    = UUID.randomUUID();
    VersionedProfile profile = new VersionedProfile("someversion", "somename", "someavatar", "somecommitment".getBytes());

    when(cacheClient.getReadResource()).thenReturn(jedis);
    when(cacheClient.getWriteResource()).thenReturn(jedis);
    when(jedis.hget(eq("profiles::" + uuid.toString()), eq("someversion"))).thenThrow(new JedisException("Connection lost"));
    when(profiles.get(eq(uuid), eq("someversion"))).thenReturn(Optional.of(profile));

    ProfilesManager            profilesManager = new ProfilesManager(profiles, cacheClient);
    Optional<VersionedProfile> retrieved       = profilesManager.get(uuid, "someversion");

    assertTrue(retrieved.isPresent());
    assertSame(retrieved.get(), profile);

    verify(jedis, times(1)).hget(eq("profiles::" + uuid.toString()), eq("someversion"));
    verify(jedis, times(1)).hset(eq("profiles::" + uuid.toString()), eq("someversion"), anyString());
    verify(jedis, times(2)).close();
    verifyNoMoreInteractions(jedis);

    verify(profiles, times(1)).get(eq(uuid), eq("someversion"));
    verifyNoMoreInteractions(profiles);
  }
}
