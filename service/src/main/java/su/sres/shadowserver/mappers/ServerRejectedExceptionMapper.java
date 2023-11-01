/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.mappers;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import su.sres.shadowserver.controllers.ServerRejectedException;

public class ServerRejectedExceptionMapper implements ExceptionMapper<ServerRejectedException> {

  @Override
  public Response toResponse(final ServerRejectedException exception) {
    return Response.status(508).build();
  }
}
