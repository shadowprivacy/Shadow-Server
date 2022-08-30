/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit.ResourceTestRule;
import org.assertj.core.api.Assertions;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.controllers.PaymentsController;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PaymentsControllerTest {

  private static final ExternalServiceCredentialGenerator paymentsCredentialGenerator = mock(ExternalServiceCredentialGenerator.class);
  private static final CurrencyConversionManager currencyManager                      = mock(CurrencyConversionManager.class);

  private final ExternalServiceCredentials validCredentials = new ExternalServiceCredentials("username", "password");

  @ClassRule
  public static final ResourceTestRule resources = ResourceTestRule.builder()
                                                                   .addProvider(AuthHelper.getAuthFilter())
                                                                   .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(Account.class, DisabledPermittedAccount.class)))
                                                                   .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
                                                                   .addResource(new PaymentsController(currencyManager, paymentsCredentialGenerator))
                                                                   .build();


  @Before
  public void setup() {
    when(paymentsCredentialGenerator.generateFor(eq(AuthHelper.VALID_UUID.toString()))).thenReturn(validCredentials);
    when(currencyManager.getCurrencyConversions()).thenReturn(Optional.of(new CurrencyConversionEntityList(List.of(new CurrencyConversionEntity("FOO", Map.of("USD", 2.35, "EUR", 1.89)), new CurrencyConversionEntity("BAR", Map.of("USD", 1.50, "EUR", 0.98))), System.currentTimeMillis())));
  }

  @Test
  public void testGetAuthToken() {
    ExternalServiceCredentials token =
        resources.getJerseyTest()
            .target("/v1/payments/auth")
            .request()
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .get(ExternalServiceCredentials.class);

    assertThat(token.getUsername()).isEqualTo(validCredentials.getUsername());
    assertThat(token.getPassword()).isEqualTo(validCredentials.getPassword());
  }

  @Test
  public void testInvalidAuthGetAuthToken() {
    Response response =
        resources.getJerseyTest()
            .target("/v1/payments/auth")
            .request()
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.INVVALID_NUMBER, AuthHelper.INVALID_PASSWORD))
            .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  public void testDisabledGetAuthToken() {
    Response response =
        resources.getJerseyTest()
            .target("/v1/payments/auth")
            .request()
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
            .get();
    assertThat(response.getStatus()).isEqualTo(401);
  }
  
  @Test
  public void testGetCurrencyConversions() {
    CurrencyConversionEntityList conversions =
        resources.getJerseyTest()
                 .target("/v1/payments/conversions")
                 .request()
                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                 .get(CurrencyConversionEntityList.class);


    assertThat(conversions.getCurrencies().size()).isEqualTo(2);
    assertThat(conversions.getCurrencies().get(0).getBase()).isEqualTo("FOO");
    assertThat(conversions.getCurrencies().get(0).getConversions().get("USD")).isEqualTo(2.35);
  }

}
