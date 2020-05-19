package su.sres.shadowserver.providers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public class SystemCertsVersion {

  @JsonProperty
  @JsonSerialize
  private int certsVersion;
 
  public SystemCertsVersion(int certsVersion) {
    this.certsVersion = certsVersion;    
  }
  
  public int getCertsVersion() {
		return certsVersion;
	}
  
  public void setCertsVersion(int version) {
	  certsVersion = version;
	}
}