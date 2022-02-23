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
