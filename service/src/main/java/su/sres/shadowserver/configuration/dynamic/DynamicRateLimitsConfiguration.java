package su.sres.shadowserver.configuration.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import su.sres.shadowserver.configuration.RateLimitsConfiguration.RateLimitConfiguration;

public class DynamicRateLimitsConfiguration {

  @JsonProperty
  private RateLimitConfiguration unsealedSenderNumber = new RateLimitConfiguration(60, 1.0 / 60);

  @JsonProperty
  private RateLimitConfiguration unsealedSenderIp = new RateLimitConfiguration(120, 2.0 / 60);

  public RateLimitConfiguration getUnsealedSenderIp() {
    return unsealedSenderIp;
  }

  public RateLimitConfiguration getUnsealedSenderNumber() {
    return unsealedSenderNumber;
  }
}
