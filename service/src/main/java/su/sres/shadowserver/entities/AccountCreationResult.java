package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class AccountCreationResult {

  @JsonProperty
  private UUID uuid;

  public AccountCreationResult() {}

  public AccountCreationResult(UUID uuid) {
    this.uuid = uuid;
  }

  public UUID getUuid() {
    return uuid;
  }
}