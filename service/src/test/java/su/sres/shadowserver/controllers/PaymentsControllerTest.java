/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.DisabledPermittedAuthenticatedAccount;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.currency.CurrencyConversionManager;
import su.sres.shadowserver.entities.CurrencyConversionEntity;
import su.sres.shadowserver.entities.CurrencyConversionEntityList;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.AuthHelper;

import javax.ws.rs.core.Response;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(DropwizardExtensionsSupport.class)
class PaymentsControllerTest {

  private static final ExternalServiceCredentialGenerator paymentsCredentialGenerator = mock(ExternalServiceCredentialGenerator.class);
  private static final CurrencyConversionManager currencyManager = mock(CurrencyConversionManager.class);

  private final ExternalServiceCredentials validCredentials = new ExternalServiceCredentials("username", "password");

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(AuthenticatedAccount.class, DisabledPermittedAuthenticatedAccount.class)))
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new PaymentsController(currencyManager, paymentsCredentialGenerator))
      .build();

  @BeforeEach
  void setup() {
    when(paymentsCredentialGenerator.generateFor(eq(AuthHelper.VALID_UUID.toString()))).thenReturn(validCredentials);
    when(currencyManager.getCurrencyConversions()).thenReturn(Optional.of(
        new CurrencyConversionEntityList(List.of(
            new CurrencyConversionEntity("FOO", Map.of(
                "USD", new BigDecimal("2.35"),
                "EUR", new BigDecimal("1.89"))),
            new CurrencyConversionEntity("BAR", Map.of(
                "USD", new BigDecimal("1.50"),
                "EUR", new BigDecimal("0.98")))),
            System.currentTimeMillis())));
  }

  @Test
  void testGetAuthToken() {
    ExternalServiceCredentials token = resources.getJerseyTest()
        .target("/v1/payments/auth")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(ExternalServiceCredentials.class);

    assertThat(token.getUsername()).isEqualTo(validCredentials.getUsername());
    assertThat(token.getPassword()).isEqualTo(validCredentials.getPassword());
  }

  @Test
  void testInvalidAuthGetAuthToken() {
    Response response = resources.getJerseyTest()
        .target("/v1/payments/auth")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.INVALID_UUID, AuthHelper.INVALID_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testDisabledGetAuthToken() {
    Response response = resources.getJerseyTest()
        .target("/v1/payments/auth")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.DISABLED_UUID, AuthHelper.DISABLED_PASSWORD))
        .get();
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testGetCurrencyConversions() {
    CurrencyConversionEntityList conversions = resources.getJerseyTest()
        .target("/v1/payments/conversions")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(CurrencyConversionEntityList.class);

    assertThat(conversions.getCurrencies().size()).isEqualTo(2);
    assertThat(conversions.getCurrencies().get(0).getBase()).isEqualTo("FOO");
    assertThat(conversions.getCurrencies().get(0).getConversions().get("USD")).isEqualTo(new BigDecimal("2.35"));
  }

  @Test
  void testGetCurrencyConversions_Json() {
    String json = resources.getJerseyTest()
        .target("/v1/payments/conversions")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(String.class);

    // the currency serialization might occur in either order
    assertThat(json).containsPattern("\\{(\"EUR\":1.89,\"USD\":2.35|\"USD\":2.35,\"EUR\":1.89)}");
  }

}
