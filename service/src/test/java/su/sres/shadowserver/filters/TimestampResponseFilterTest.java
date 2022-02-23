package su.sres.shadowserver.filters;

import org.glassfish.jersey.message.internal.HeaderUtils;
import org.junit.Test;

import su.sres.shadowserver.filters.TimestampResponseFilter;
import su.sres.shadowserver.util.TimestampHeaderUtil;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TimestampResponseFilterTest {

    @Test
    public void testFilter() {
        final ContainerRequestContext        requestContext  = mock(ContainerRequestContext.class);
        final ContainerResponseContext       responseContext = mock(ContainerResponseContext.class);

        final MultivaluedMap<String, Object> headers         = HeaderUtils.createOutbound();

        when(responseContext.getHeaders()).thenReturn(headers);

        new TimestampResponseFilter().filter(requestContext, responseContext);

        assertTrue(headers.containsKey(TimestampHeaderUtil.TIMESTAMP_HEADER));
    }
}
