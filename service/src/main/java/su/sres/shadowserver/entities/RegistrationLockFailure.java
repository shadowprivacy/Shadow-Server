package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import su.sres.shadowserver.auth.ExternalServiceCredentials;

public class RegistrationLockFailure {

  @JsonProperty
  private long timeRemaining;
  
  @JsonProperty
  private ExternalServiceCredentials backupCredentials;

  public RegistrationLockFailure() {}

  public RegistrationLockFailure(long timeRemaining, ExternalServiceCredentials backupCredentials) {
	    this.timeRemaining     = timeRemaining;
	    this.backupCredentials = backupCredentials;
  }

  @JsonIgnore
  public long getTimeRemaining() {
    return timeRemaining;
  }  

  @JsonIgnore
  public ExternalServiceCredentials getBackupCredentials() {
	    return backupCredentials;
  }
}
