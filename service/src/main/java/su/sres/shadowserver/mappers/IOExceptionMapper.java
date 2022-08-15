/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.mappers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class IOExceptionMapper implements ExceptionMapper<IOException> {

  private final Logger logger = LoggerFactory.getLogger(IOExceptionMapper.class);

  @Override
  public Response toResponse(IOException e) {
    if (!(e.getCause() instanceof java.util.concurrent.TimeoutException)) {
      logger.warn("IOExceptionMapper", e);
    }
    return Response.status(503).build();
  }
}
