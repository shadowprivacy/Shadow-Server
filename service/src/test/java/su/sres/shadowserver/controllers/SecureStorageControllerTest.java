/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.DisabledPermittedAuthenticatedAccount;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.SystemMapper;

import javax.ws.rs.core.Response;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@ExtendWith(DropwizardExtensionsSupport.class)
class SecureStorageControllerTest {

  private static final ExternalServiceCredentialGenerator storageCredentialGenerator = new ExternalServiceCredentialGenerator(new byte[32], new byte[32], false);

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(AuthenticatedAccount.class, DisabledPermittedAuthenticatedAccount.class)))
      .setMapper(SystemMapper.getMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new SecureStorageController(storageCredentialGenerator))
      .build();

  @Test
  void testGetCredentials() throws Exception {
    ExternalServiceCredentials credentials = resources.getJerseyTest()
        .target("/v1/storage/auth")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(ExternalServiceCredentials.class);

    assertThat(credentials.getPassword()).isNotEmpty();
    assertThat(credentials.getUsername()).isNotEmpty();
  }

  @Test
  void testGetCredentialsBadAuth() throws Exception {
    Response response = resources.getJerseyTest()
        .target("/v1/storage/auth")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.INVALID_UUID, AuthHelper.INVALID_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }
}