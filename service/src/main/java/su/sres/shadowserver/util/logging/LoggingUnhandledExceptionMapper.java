/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.util.logging;

import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.jersey.errors.LoggingExceptionMapper;

import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.slf4j.Logger;
import su.sres.shadowserver.util.ua.UnrecognizedUserAgentException;
import su.sres.shadowserver.util.ua.UserAgent;
import su.sres.shadowserver.util.ua.UserAgentUtil;

public class LoggingUnhandledExceptionMapper extends LoggingExceptionMapper<Throwable> {

  @Context
  private Provider<ContainerRequest> request;

  public LoggingUnhandledExceptionMapper() {
    super();
  }

  @VisibleForTesting
  LoggingUnhandledExceptionMapper(final Logger logger) {
    super(logger);
  }

  @Override
  protected String formatLogMessage(final long id, final Throwable exception) {
    String requestMethod = "unknown method";
    String userAgent = "missing";
    String requestPath = "/{unknown path}";
    try {
   // request shouldn’t be `null`, but it is technically possible
      requestMethod = request.get().getMethod();
      requestPath = UriInfoUtil.getPathTemplate(request.get().getUriInfo());
      userAgent = request.get().getHeaderString("user-agent");

      // streamline the user-agent if it is recognized
      final UserAgent ua = UserAgentUtil.parseUserAgentString(userAgent);
      userAgent = String.format("%s %s", ua.getPlatform(), ua.getVersion());
    } catch (final UnrecognizedUserAgentException ignored) {

    } catch (final Exception e) {
      logger.warn("Unexpected exception getting request details", e);
    }

    return String.format("%s at %s %s (%s)",
        super.formatLogMessage(id, exception),
        requestMethod,
        requestPath,
        userAgent) ;
  }

}
