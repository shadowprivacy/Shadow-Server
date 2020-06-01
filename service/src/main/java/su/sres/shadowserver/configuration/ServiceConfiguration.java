package su.sres.shadowserver.configuration;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import su.sres.shadowserver.util.ByteArrayAdapter;

public class ServiceConfiguration {

	@JsonProperty
	@NotEmpty
	private String cloudUri;

	@JsonProperty
	@NotEmpty
	private String statusUri;
	
	@JsonProperty
	@NotEmpty
	private String storageUri;
	
	@JsonProperty
	@NotEmpty
	private String keyStorePath;
	
	@JsonProperty
	@NotEmpty
	private String keyStorePassword;

	@JsonProperty
	@JsonSerialize(using = ByteArrayAdapter.Serializing.class)
	@JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
	@NotNull
	@Size(min = 32, max = 32)
	private byte[] unidentifiedDeliveryCaPublicKey;
	
	@JsonProperty
	@NotEmpty
	private int certsVersion;
	
	@JsonProperty
	@NotEmpty
	private String supportEmail;

	public String getCloudUri() {
		return cloudUri;
	}

	public String getStatusUri() {
		return statusUri;
	}
	
	public String getStorageUri() {
		return storageUri;
	}
	
	public String getKeyStorePath() {
		return keyStorePath;
	}
	
	public String getKeyStorePassword() {
		return keyStorePassword;
	}
	
	public byte[] getUnidentifiedDeliveryCaPublicKey() {
		return unidentifiedDeliveryCaPublicKey;
	}	
	
	public int getCertsVersion() {
		return certsVersion;
	}
	
	public String getSupportEmail() {
		return supportEmail;
	}
	
}