package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import su.sres.shadowserver.auth.ExternalServiceCredentials;

public class RegistrationLockFailure {

  @JsonProperty
  private long timeRemaining;
    
  public RegistrationLockFailure() {}

  public RegistrationLockFailure(long timeRemaining) {
	    this.timeRemaining     = timeRemaining;	    
  }

  @JsonIgnore
  public long getTimeRemaining() {
    return timeRemaining;
  } 
}