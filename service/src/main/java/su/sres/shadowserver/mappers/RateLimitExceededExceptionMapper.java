/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.mappers;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import su.sres.shadowserver.controllers.RateLimitExceededException;

@Provider
public class RateLimitExceededExceptionMapper implements ExceptionMapper<RateLimitExceededException> {
  @Override
  public Response toResponse(RateLimitExceededException e) {
    return Response.status(429).build();
  }
}
