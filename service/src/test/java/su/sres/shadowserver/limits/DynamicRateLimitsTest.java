package su.sres.shadowserver.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.sres.shadowserver.configuration.RateLimitsConfiguration;
import su.sres.shadowserver.configuration.RateLimitsConfiguration.RateLimitConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicRateLimitsConfiguration;
import su.sres.shadowserver.limits.CardinalityRateLimiter;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;

class DynamicRateLimitsTest {

  private DynamicConfiguration dynamicConfig;
  private FaultTolerantRedisCluster   redisCluster;

  @BeforeEach
  void setup() {
    this.dynamicConfig = new DynamicConfiguration();
    this.redisCluster  = mock(FaultTolerantRedisCluster.class);   
  }

  @Test
  void testUnchangingConfiguration() {
    DynamicRateLimitsConfiguration limitsConfiguration = new DynamicRateLimitsConfiguration();
    RateLimiters rateLimiters = new RateLimiters(new RateLimitsConfiguration(), limitsConfiguration, redisCluster);

    RateLimiter limiter = rateLimiters.getUnsealedIpLimiter();

    assertThat(limiter.getBucketSize()).isEqualTo(dynamicConfig.getLimits().getUnsealedSenderIp().getBucketSize());
    assertThat(limiter.getLeakRatePerMinute()).isEqualTo(dynamicConfig.getLimits().getUnsealedSenderIp().getLeakRatePerMinute());
    assertSame(rateLimiters.getUnsealedIpLimiter(), limiter);
  }

  @Test
  void testChangingConfiguration() {
    DynamicConfiguration configuration = mock(DynamicConfiguration.class);
    DynamicRateLimitsConfiguration limitsConfiguration = mock(DynamicRateLimitsConfiguration.class);

    when(configuration.getLimits()).thenReturn(limitsConfiguration);
    when(limitsConfiguration.getUnsealedSenderUserLogin()).thenReturn(new RateLimitsConfiguration.CardinalityRateLimitConfiguration(10, Duration.ofHours(1)));
    when(limitsConfiguration.getRecaptchaChallengeAttempt()).thenReturn(new RateLimitConfiguration());
    when(limitsConfiguration.getRecaptchaChallengeSuccess()).thenReturn(new RateLimitConfiguration());
    when(limitsConfiguration.getPushChallengeAttempt()).thenReturn(new RateLimitConfiguration());
    when(limitsConfiguration.getPushChallengeSuccess()).thenReturn(new RateLimitConfiguration());
    when(limitsConfiguration.getDailyPreKeys()).thenReturn(new RateLimitConfiguration());

    final RateLimitConfiguration initialRateLimitConfiguration = new RateLimitConfiguration(4, 1.0);
    when(limitsConfiguration.getUnsealedSenderIp()).thenReturn(initialRateLimitConfiguration);
    when(limitsConfiguration.getRateLimitReset()).thenReturn(initialRateLimitConfiguration);
   
    RateLimiters rateLimiters = new RateLimiters(new RateLimitsConfiguration(), limitsConfiguration, redisCluster);

    CardinalityRateLimiter limiter = rateLimiters.getUnsealedSenderCardinalityLimiter();

    assertThat(limiter.getDefaultMaxCardinality()).isEqualTo(10);
    assertThat(limiter.getInitialTtl()).isEqualTo(Duration.ofHours(1));
    assertSame(rateLimiters.getUnsealedSenderCardinalityLimiter(), limiter);

    when(limitsConfiguration.getUnsealedSenderUserLogin()).thenReturn(new RateLimitsConfiguration.CardinalityRateLimitConfiguration(20, Duration.ofHours(2)));

    CardinalityRateLimiter changed = rateLimiters.getUnsealedSenderCardinalityLimiter();

    assertThat(changed.getDefaultMaxCardinality()).isEqualTo(20);
    assertThat(changed.getInitialTtl()).isEqualTo(Duration.ofHours(2));
    assertNotSame(limiter, changed);
  }

}
