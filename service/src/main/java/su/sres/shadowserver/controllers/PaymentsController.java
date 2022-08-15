package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.auth.Auth;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.storage.Account;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/v1/payments")
public class PaymentsController {

  private final ExternalServiceCredentialGenerator paymentsServiceCredentialGenerator;

  public PaymentsController(ExternalServiceCredentialGenerator paymentsServiceCredentialGenerator) {
    this.paymentsServiceCredentialGenerator = paymentsServiceCredentialGenerator;
  }

  @Timed
  @GET
  @Path("/auth")
  @Produces(MediaType.APPLICATION_JSON)
  public ExternalServiceCredentials getAuth(@Auth Account account) {
    return paymentsServiceCredentialGenerator.generateFor(account.getUuid().toString());
  }
}
