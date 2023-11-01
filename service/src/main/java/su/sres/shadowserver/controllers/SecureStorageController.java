/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;

import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.dropwizard.auth.Auth;

@Path("/v1/storage")
public class SecureStorageController {

  private final ExternalServiceCredentialGenerator storageServiceCredentialGenerator;

  public SecureStorageController(ExternalServiceCredentialGenerator storageServiceCredentialGenerator) {
    this.storageServiceCredentialGenerator = storageServiceCredentialGenerator;
  }

  @Timed
  @GET
  @Path("/auth")
  @Produces(MediaType.APPLICATION_JSON)
  public ExternalServiceCredentials getAuth(@Auth AuthenticatedAccount auth) {
    return storageServiceCredentialGenerator.generateFor(auth.getAccount().getUuid().toString());
  }  
}