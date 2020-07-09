package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UserRemoteConfig {

  @JsonProperty
  private String name;

  @JsonProperty
  private boolean enabled;

  public UserRemoteConfig() {}

  public UserRemoteConfig(String name, boolean enabled) {
    this.name    = name;
    this.enabled = enabled;
  }

  public String getName() {
    return name;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
