package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import su.sres.shadowserver.util.ByteArrayAdapter;

import javax.validation.constraints.NotNull;

public class ZkConfig {

  @JsonProperty
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
  @NotNull
  private byte[] serverSecret;

/*  @JsonProperty
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
  @NotNull
  private byte[] serverPublic;
  */
  
  @JsonProperty
  @NotNull
  private Boolean enabled;

  public byte[] getServerSecret() {
    return serverSecret;
  }

/*  public byte[] getServerPublic() {
    return serverPublic;
  }
  */
  
  public boolean isEnabled() {
	    return enabled;
  }
}
