package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;

import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.storage.Account;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.dropwizard.auth.Auth;

@Path("/v1/backup")
public class SecureBackupController {

  private final ExternalServiceCredentialGenerator backupServiceCredentialGenerator;

  public SecureBackupController(ExternalServiceCredentialGenerator backupServiceCredentialGenerator) {
    this.backupServiceCredentialGenerator = backupServiceCredentialGenerator;
  }

  @Timed
  @GET
  @Path("/auth")
  @Produces(MediaType.APPLICATION_JSON)
  public ExternalServiceCredentials getAuth(@Auth Account account) {
	    return backupServiceCredentialGenerator.generateFor(account.getNumber());
  }
}