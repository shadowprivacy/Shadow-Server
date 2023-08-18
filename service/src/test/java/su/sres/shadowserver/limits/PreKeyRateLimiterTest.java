package su.sres.shadowserver.limits;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicRateLimitChallengeConfiguration;
import su.sres.shadowserver.controllers.RateLimitExceededException;
import su.sres.shadowserver.storage.Account;

class PreKeyRateLimiterTest {

  private Account account;

  private PreKeyRateLimiter preKeyRateLimiter;

  private DynamicRateLimitChallengeConfiguration rateLimitChallengeConfiguration;
  private RateLimiter dailyPreKeyLimiter;

  @BeforeEach
  void setup() {
    final RateLimiters rateLimiters = mock(RateLimiters.class);

    dailyPreKeyLimiter = mock(RateLimiter.class);
    when(rateLimiters.getDailyPreKeysLimiter()).thenReturn(dailyPreKeyLimiter);
    
    rateLimitChallengeConfiguration = mock(DynamicRateLimitChallengeConfiguration.class);
    final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);
   
    when(dynamicConfiguration.getRateLimitChallengeConfiguration()).thenReturn(rateLimitChallengeConfiguration);

    preKeyRateLimiter = new PreKeyRateLimiter(rateLimiters, dynamicConfiguration.getRateLimitChallengeConfiguration(), mock(RateLimitResetMetricsManager.class));

    account = mock(Account.class);
    when(account.getUserLogin()).thenReturn("+18005551111");
    when(account.getUuid()).thenReturn(UUID.randomUUID());
  }

  @Test
  void enforcementConfiguration() throws RateLimitExceededException {

    doThrow(RateLimitExceededException.class)
      .when(dailyPreKeyLimiter).validate(any());

    when(rateLimitChallengeConfiguration.isPreKeyLimitEnforced()).thenReturn(false);

    preKeyRateLimiter.validate(account);

    when(rateLimitChallengeConfiguration.isPreKeyLimitEnforced()).thenReturn(true);

    assertThrows(RateLimitExceededException.class, () -> preKeyRateLimiter.validate(account));

    when(rateLimitChallengeConfiguration.isPreKeyLimitEnforced()).thenReturn(false);

    preKeyRateLimiter.validate(account);
  }
}
