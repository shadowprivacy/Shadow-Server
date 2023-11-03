/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.annotation.Timed;
import org.signal.zkgroup.auth.ServerZkAuthOperations;

import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.CertificateGenerator;
import su.sres.shadowserver.entities.DeliveryCertificate;
import su.sres.shadowserver.entities.GroupCredentials;
import su.sres.shadowserver.util.Util;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.security.InvalidKeyException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/certificate")
public class CertificateController {

  private final CertificateGenerator certificateGenerator;
  private final ServerZkAuthOperations serverZkAuthOperations;

  private static final String GENERATE_DELIVERY_CERTIFICATE_COUNTER_NAME = name(CertificateGenerator.class, "generateCertificate");
  private static final String INCLUDE_USER_LOGIN_TAG_NAME = "includeUserLogin";

  public CertificateController(CertificateGenerator certificateGenerator, ServerZkAuthOperations serverZkAuthOperations) {
    this.certificateGenerator = certificateGenerator;
    this.serverZkAuthOperations = serverZkAuthOperations;
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/delivery")
  public DeliveryCertificate getDeliveryCertificate(@Auth AuthenticatedAccount auth,
      @QueryParam("includeE164") Optional<Boolean> maybeIncludeUserLogin)
      throws InvalidKeyException {
    if (Util.isEmpty(auth.getAccount().getIdentityKey())) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }

    final boolean includeUserLogin = maybeIncludeUserLogin.orElse(true);

    Metrics.counter(GENERATE_DELIVERY_CERTIFICATE_COUNTER_NAME, INCLUDE_USER_LOGIN_TAG_NAME, String.valueOf(includeUserLogin)).increment();

    return new DeliveryCertificate(certificateGenerator.createFor(auth.getAccount(), auth.getAuthenticatedDevice(), includeUserLogin));
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/group/{startRedemptionTime}/{endRedemptionTime}")
  public GroupCredentials getAuthenticationCredentials(@Auth AuthenticatedAccount auth,
      @PathParam("startRedemptionTime") int startRedemptionTime,
      @PathParam("endRedemptionTime") int endRedemptionTime) {

    if (startRedemptionTime > endRedemptionTime) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    if (endRedemptionTime > Util.currentDaysSinceEpoch() + 7) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    if (startRedemptionTime < Util.currentDaysSinceEpoch()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }

    List<GroupCredentials.GroupCredential> credentials = new LinkedList<>();

    for (int i = startRedemptionTime; i <= endRedemptionTime; i++) {
      credentials.add(new GroupCredentials.GroupCredential(
          serverZkAuthOperations.issueAuthCredential(auth.getAccount().getUuid(), i)
              .serialize(),
          i));
    }

    return new GroupCredentials(credentials);
  }

}