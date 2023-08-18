package su.sres.shadowserver.configuration.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import java.util.Collections;
import java.util.Set;

public class DynamicConfiguration {
  
  @JsonProperty
  @Valid
  private DynamicRateLimitsConfiguration limits = new DynamicRateLimitsConfiguration();
  
  @JsonProperty
  private DynamicAccountsScyllaDbMigrationConfiguration accountsScyllaDbMigration = new DynamicAccountsScyllaDbMigrationConfiguration();
  
  @JsonProperty
  @Valid
  private DynamicRateLimitChallengeConfiguration rateLimitChallenge = new DynamicRateLimitChallengeConfiguration();

  @JsonProperty
  @Valid
  private DynamicRemoteDeprecationConfiguration remoteDeprecation = new DynamicRemoteDeprecationConfiguration();
  
  @JsonProperty
  @Valid
  private DynamicMessageRateConfiguration messageRate = new DynamicMessageRateConfiguration();

  @JsonProperty
  private Set<String> featureFlags = Collections.emptySet();
  
  public DynamicRateLimitsConfiguration getLimits() {
    return limits;
  }

  public DynamicRemoteDeprecationConfiguration getRemoteDeprecationConfiguration() {
    return remoteDeprecation;
  }
  
  public DynamicMessageRateConfiguration getMessageRateConfiguration() {
    return messageRate;
  }

  public Set<String> getActiveFeatureFlags() {
    return featureFlags;
  }
  
  public DynamicAccountsScyllaDbMigrationConfiguration getAccountsScyllaDbMigrationConfiguration() {
    return accountsScyllaDbMigration;
  }
  
  public DynamicRateLimitChallengeConfiguration getRateLimitChallengeConfiguration() {
    return rateLimitChallenge;
  }
}
