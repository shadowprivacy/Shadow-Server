/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import su.sres.shadowserver.util.SystemMapper;

import java.io.IOException;

import static io.dropwizard.testing.FixtureHelpers.fixture;

public class JsonHelpers {

	private static final ObjectMapper objectMapper = SystemMapper.getMapper();

  public static String asJson(Object object) throws JsonProcessingException {
    return objectMapper.writeValueAsString(object);
  }

  public static <T> T fromJson(String value, Class<T> clazz) throws IOException {
    return objectMapper.readValue(value, clazz);
  }

  public static String jsonFixture(String filename) throws IOException {
    return objectMapper.writeValueAsString(objectMapper.readValue(fixture(filename), JsonNode.class));
  }
}
