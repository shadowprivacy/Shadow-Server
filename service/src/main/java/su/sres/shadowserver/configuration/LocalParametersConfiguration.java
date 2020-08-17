package su.sres.shadowserver.configuration;

import javax.validation.constraints.NotEmpty;

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
	
	@JsonProperty
	@NotEmpty
	private String licensePath;

	public int getVerificationCodeLifetime() {
		return verificationCodeLifetime;
	}

	public String getKeyStorePath() {
		return keyStorePath;
	}

	public String getKeyStorePassword() {
		return keyStorePassword;
	}
	
	public String getLicensePath() {
		return licensePath;
	}

}