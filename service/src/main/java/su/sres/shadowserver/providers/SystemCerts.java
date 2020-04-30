package su.sres.shadowserver.providers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import su.sres.shadowserver.util.ByteArrayAdapter;

public class SystemCerts {

  @JsonProperty
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  private byte[] cloudCert;
 
  public SystemCerts(byte[] cloudCert) {
    this.cloudCert = cloudCert;    
  }
  
  public byte[] getCloudCert() {
		return cloudCert;
	}
  
  public void setCloudCert(byte[] cert) {
		cloudCert = cert;
	}
}