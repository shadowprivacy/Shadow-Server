/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.mappers;

import su.sres.shadowserver.controllers.RateLimitExceededException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import su.sres.shadowserver.controllers.RetryLaterException;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.time.Duration;

@Provider
public class RetryLaterExceptionMapper implements ExceptionMapper<RetryLaterException> {
  @Override
  public Response toResponse(RetryLaterException e) {
    return Response.status(413)
                   .header("Retry-After", e.getBackoffDuration().toSeconds())
                   .build();
  }
}