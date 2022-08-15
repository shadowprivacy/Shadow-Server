/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.filters;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;

import su.sres.shadowserver.util.TimestampHeaderUtil;

/**
 * Injects a timestamp header into all outbound responses.
 */
public class TimestampResponseFilter implements ContainerResponseFilter {   

    @Override
    public void filter(final ContainerRequestContext requestContext, final ContainerResponseContext responseContext) {
	responseContext.getHeaders().add(TimestampHeaderUtil.TIMESTAMP_HEADER, System.currentTimeMillis());
    }
}
