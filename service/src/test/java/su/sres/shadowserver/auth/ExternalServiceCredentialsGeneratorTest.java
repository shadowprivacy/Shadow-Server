/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import org.junit.Test;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class ExternalServiceCredentialsGeneratorTest {

  @Test
  public void testGenerateDerivedUsername() {
    ExternalServiceCredentialGenerator generator = new ExternalServiceCredentialGenerator(new byte[32], new byte[32], true);
    ExternalServiceCredentials credentials = generator.generateFor("+14152222222");

    assertThat(credentials.getUsername()).isNotEqualTo("+14152222222");
    assertThat(credentials.getPassword().startsWith("+14152222222")).isFalse();
  }

  @Test
  public void testGenerateNoDerivedUsername() {
    ExternalServiceCredentialGenerator generator = new ExternalServiceCredentialGenerator(new byte[32], new byte[32], false);
    ExternalServiceCredentials credentials = generator.generateFor("+14152222222");

    assertThat(credentials.getUsername()).isEqualTo("+14152222222");
    assertThat(credentials.getPassword().startsWith("+14152222222")).isTrue();
  }

}