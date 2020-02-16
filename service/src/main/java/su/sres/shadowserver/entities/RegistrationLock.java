package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.NotEmpty;

public class RegistrationLock {

  @JsonProperty
  @Length(min=64,max=64)
  @NotEmpty
  private String registrationLock;

  public RegistrationLock() {}

  @VisibleForTesting
  public RegistrationLock(String registrationLock) {
	    this.registrationLock = registrationLock;
  }

  public String getRegistrationLock() {
	    return registrationLock;
  }

}
