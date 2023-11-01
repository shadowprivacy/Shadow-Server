/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.auth.Auth;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.currency.CurrencyConversionManager;
import su.sres.shadowserver.entities.CurrencyConversionEntityList;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/v1/payments")
public class PaymentsController {

  private final ExternalServiceCredentialGenerator paymentsServiceCredentialGenerator;
  private final CurrencyConversionManager currencyManager;

  public PaymentsController(CurrencyConversionManager currencyManager, ExternalServiceCredentialGenerator paymentsServiceCredentialGenerator) {
    this.currencyManager = currencyManager;
    this.paymentsServiceCredentialGenerator = paymentsServiceCredentialGenerator;
  }

  @Timed
  @GET
  @Path("/auth")
  @Produces(MediaType.APPLICATION_JSON)
  public ExternalServiceCredentials getAuth(@Auth AuthenticatedAccount auth) {
    return paymentsServiceCredentialGenerator.generateFor(auth.getAccount().getUuid().toString());
  }

  @Timed
  @GET
  @Path("/conversions")
  @Produces(MediaType.APPLICATION_JSON)
  public CurrencyConversionEntityList getConversions(@Auth AuthenticatedAccount auth) {
    return currencyManager.getCurrencyConversions().orElseThrow();
  }
}
