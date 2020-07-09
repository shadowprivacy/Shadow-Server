package su.sres.shadowserver.configuration;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LocalParametersConfiguration {

	@JsonProperty
	@NotEmpty
	private int verificationCodeLifetime;

	@JsonProperty
	@NotEmpty
	private String keyStorePath;

	@JsonProperty
	@NotEmpty
	private String keyStorePassword;

	public int getVerificationCodeLifetime() {
		return verificationCodeLifetime;
	}

	public String getKeyStorePath() {
		return keyStorePath;
	}

	public String getKeyStorePassword() {
		return keyStorePassword;
	}

}