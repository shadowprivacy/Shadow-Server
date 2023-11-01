/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StoredVerificationCodeTest {

  @ParameterizedTest
  @MethodSource
  void isValid(final StoredVerificationCode storedVerificationCode, final String code, final Instant currentTime, final boolean expectValid, final int lifetime) {
    assertEquals(expectValid, storedVerificationCode.isValid(code, lifetime, currentTime));
  }

  private static Stream<Arguments> isValid() {
    final Instant now = Instant.now();

    return Stream.of(
        Arguments.of(new StoredVerificationCode("code", now.toEpochMilli(), null), "code", now, true, 24),
        Arguments.of(new StoredVerificationCode("code", now.toEpochMilli(), null), "incorrect", now, false, 24),
        Arguments.of(new StoredVerificationCode("code", now.toEpochMilli(), null), "code", now.plus(Duration.ofHours(3)), false, 2),
        Arguments.of(new StoredVerificationCode("", now.toEpochMilli(), null), "", now, false, 24)
    );
  }
}
