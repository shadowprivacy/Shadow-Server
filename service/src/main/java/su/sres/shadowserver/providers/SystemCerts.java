/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.providers;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import su.sres.shadowserver.util.Base64;

public class SystemCerts {

	@JsonProperty
	@JsonSerialize(using = Serializing.class)
	private byte[] shadowCertA;

	@JsonProperty
	@JsonSerialize(using = Serializing.class)
	private byte[] shadowCertB;

	@JsonProperty
	@JsonSerialize(using = Serializing.class)
	private byte[] cloudCertA;

	@JsonProperty
	@JsonSerialize(using = Serializing.class)
	private byte[] cloudCertB;
	
	@JsonProperty
	@JsonSerialize(using = Serializing.class)
	private byte[] storageCertA;
	
	@JsonProperty
	@JsonSerialize(using = Serializing.class)
	private byte[] storageCertB;

	public SystemCerts(byte[] cloudCertA, byte[] cloudCertB, byte[] shadowCertA, byte[] shadowCertB, byte[] storageCertA, byte[] storageCertB) {
		this.cloudCertA = cloudCertA;
		this.cloudCertB = cloudCertB;
		this.shadowCertA = shadowCertA;
		this.shadowCertB = shadowCertB;
		this.storageCertA = storageCertA;
		this.storageCertB = storageCertB;
	}

	public byte[] getCloudCertA() {
		return cloudCertA;
	}
	
	public byte[] getCloudCertB() {
		return cloudCertB;
	}
	
	public byte[] getShadowCertA() {
		return shadowCertA;
	}
	
	public byte[] getShadowCertB() {
		return shadowCertB;
	}
	
	public byte[] getStorageCertA() {
		return storageCertA;
	}
	
	public byte[] getStorageCertB() {
		return storageCertB;
	}

	public void setCloudCertA(byte[] cert) {
		cloudCertA = cert;
	}
	
	public void setCloudCertB(byte[] cert) {
		cloudCertB = cert;
	}
	
	public void setShadowCertA(byte[] cert) {
		shadowCertA = cert;
	}
	
	public void setShadowCertB(byte[] cert) {
		shadowCertB = cert;
	}
	
	public void setStorageCertA(byte[] cert) {
		storageCertA = cert;
	}
	
	public void setStorageCertB(byte[] cert) {
		storageCertB = cert;
	}
	
	public static class Serializing extends JsonSerializer<byte[]> {
	    @Override
	    public void serialize(byte[] bytes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
	        throws IOException, JsonProcessingException
	    {
	      jsonGenerator.writeString(Base64.encodeBytes(bytes));
	    }
	  }
}