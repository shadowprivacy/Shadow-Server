package su.sres.shadowserver.configuration.dynamic;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;
import su.sres.shadowserver.configuration.RateLimitsConfiguration.RateLimitConfiguration;
import su.sres.shadowserver.configuration.RateLimitsConfiguration.CardinalityRateLimitConfiguration;

public class DynamicRateLimitsConfiguration {

  @JsonProperty
  private CardinalityRateLimitConfiguration unsealedSenderUserLogin = new CardinalityRateLimitConfiguration(100, Duration.ofDays(1), Duration.ofDays(1));

  @JsonProperty
  private RateLimitConfiguration unsealedSenderIp = new RateLimitConfiguration(120, 2.0 / 60);

  public RateLimitConfiguration getUnsealedSenderIp() {
    return unsealedSenderIp;
  }

  public CardinalityRateLimitConfiguration getUnsealedSenderUserLogin() {
    return unsealedSenderUserLogin;
  }
}
