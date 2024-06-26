/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKeyCommitment;

import java.io.IOException;
import java.util.Base64;

public class ProfileKeyCommitmentAdapter {

  public static class Serializing extends JsonSerializer<ProfileKeyCommitment> {
    @Override
    public void serialize(ProfileKeyCommitment value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
      gen.writeString(Base64.getEncoder().encodeToString(value.serialize()));
    }
  }

  public static class Deserializing extends JsonDeserializer<ProfileKeyCommitment> {

    @Override
    public ProfileKeyCommitment deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      try {
        return new ProfileKeyCommitment(Base64.getDecoder().decode(p.getValueAsString()));
      } catch (InvalidInputException e) {
        throw new IOException(e);
      }
    }
  }
}


