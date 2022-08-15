/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

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
	
	@JsonProperty
	@NotEmpty
	private String fcmSenderId;	

    @JsonProperty
	@JsonSerialize(using = ByteArrayAdapter.Serializing.class)
	@JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
	@NotNull
	private byte[] serverZkPublic;

	public String getCloudUri() {
		return cloudUri;
	}

	public String getStatusUri() {
		return statusUri;
	}
	
	public String getStorageUri() {
		return storageUri;
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
	
	public String getFcmSenderId() {
		return fcmSenderId;
	}
	
	public byte[] getServerZkPublic() {
	    return serverZkPublic;
	  }
	
}