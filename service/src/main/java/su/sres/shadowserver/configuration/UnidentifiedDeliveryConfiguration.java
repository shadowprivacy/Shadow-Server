package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import su.sres.shadowserver.crypto.Curve;
import su.sres.shadowserver.crypto.ECPrivateKey;
import su.sres.shadowserver.util.ByteArrayAdapter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class UnidentifiedDeliveryConfiguration {

  @JsonProperty
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
  @NotNull
  private byte[] certificate;

  @JsonProperty
  @JsonSerialize(using = ByteArrayAdapter.Serializing.class)
  @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
  @NotNull
  @Size(min = 32, max = 32)
  private byte[] privateKey;

  @NotNull
  private int expiresDays;

  public byte[] getCertificate() {
    return certificate;
  }

  public ECPrivateKey getPrivateKey() {
    return Curve.decodePrivatePoint(privateKey);
  }

  public int getExpiresDays() {
    return expiresDays;
  }
}