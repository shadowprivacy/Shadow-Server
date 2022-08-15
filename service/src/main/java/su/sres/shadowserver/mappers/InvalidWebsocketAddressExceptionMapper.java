/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.mappers;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import su.sres.shadowserver.websocket.InvalidWebsocketAddressException;

@Provider
public class InvalidWebsocketAddressExceptionMapper implements ExceptionMapper<InvalidWebsocketAddressException> {
  @Override
  public Response toResponse(InvalidWebsocketAddressException exception) {
    return Response.status(Response.Status.BAD_REQUEST).build();
  }
}
