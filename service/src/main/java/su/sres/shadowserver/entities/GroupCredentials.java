/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

public class GroupCredentials {

  @JsonProperty
  private List<GroupCredential> credentials;

  public GroupCredentials() {}

  public GroupCredentials(List<GroupCredential> credentials) {
    this.credentials = credentials;
  }

  public List<GroupCredential> getCredentials() {
    return credentials;
  }

  public static class GroupCredential {

    @JsonProperty
    @JsonSerialize(using = ByteArraySerializer.class)
    @JsonDeserialize(using = ByteArrayDeserializer.class)
    private byte[] credential;

    @JsonProperty
    private int redemptionTime;

    public GroupCredential() {}

    public GroupCredential(byte[] credential, int redemptionTime) {
      this.credential     = credential;
      this.redemptionTime = redemptionTime;
    }

    public byte[] getCredential() {
      return credential;
    }

    public int getRedemptionTime() {
      return redemptionTime;
    }
  }

  public static class ByteArraySerializer extends JsonSerializer<byte[]> {
    @Override
    public void serialize(byte[] bytes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
      jsonGenerator.writeString(Base64.getEncoder().encodeToString(bytes));
    }
  }

  public static class ByteArrayDeserializer extends JsonDeserializer<byte[]> {
    @Override
    public byte[] deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
      return Base64.getDecoder().decode(jsonParser.getValueAsString());
    }
  }

}
