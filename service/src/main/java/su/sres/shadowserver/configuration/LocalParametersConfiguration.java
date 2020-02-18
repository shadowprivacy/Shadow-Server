package su.sres.shadowserver.configuration;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LocalParametersConfiguration {

  @JsonProperty
  @NotEmpty
	private int verificationCodeLifetime;

  public int getVerificationCodeLifetime() {
    return verificationCodeLifetime;
  }
}