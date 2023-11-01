/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.mappers;

import su.sres.shadowserver.entities.RateLimitChallenge;
import su.sres.shadowserver.limits.RateLimitChallengeManager;
import su.sres.shadowserver.limits.RateLimitChallengeException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import java.util.UUID;

public class RateLimitChallengeExceptionMapper implements ExceptionMapper<RateLimitChallengeException> {

  private final RateLimitChallengeManager rateLimitChallengeManager;

  public RateLimitChallengeExceptionMapper(final RateLimitChallengeManager rateLimitChallengeManager) {
    this.rateLimitChallengeManager = rateLimitChallengeManager;
  }

  @Override
  public Response toResponse(final RateLimitChallengeException exception) {
    return Response.status(428)
        .entity(new RateLimitChallenge(UUID.randomUUID().toString(), rateLimitChallengeManager.getChallengeOptions(exception.getAccount())))
        .header("Retry-After", exception.getRetryAfter().toSeconds())
        .build();
  }
}
