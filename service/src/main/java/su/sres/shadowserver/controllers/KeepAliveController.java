/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.sres.websocket.session.WebSocketSession;
import su.sres.websocket.session.WebSocketSessionContext;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import static com.codahale.metrics.MetricRegistry.name;

import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.push.ClientPresenceManager;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.ua.UnrecognizedUserAgentException;
import su.sres.shadowserver.util.ua.UserAgentUtil;

@Path("/v1/keepalive")
public class KeepAliveController {

    private final Logger logger = LoggerFactory.getLogger(KeepAliveController.class);

    private final ClientPresenceManager clientPresenceManager;

    private static final String NO_LOCAL_SUBSCRIPTION_COUNTER_NAME = name(KeepAliveController.class, "noLocalSubscription");
    private static final String NO_LOCAL_SUBSCRIPTION_PLATFORM_TAG_NAME = "platform";

    public KeepAliveController(final ClientPresenceManager clientPresenceManager) {
	this.clientPresenceManager = clientPresenceManager;
    }

    @Timed
    @GET
    public Response getKeepAlive(@Auth Account account,
	    @WebSocketSession WebSocketSessionContext context) {
	if (account != null) {
	    if (!clientPresenceManager.isLocallyPresent(account.getUuid(), account.getAuthenticatedDevice().get().getId())) {
		logger.warn("***** No local subscription found for {}::{}; age = {}ms, User-Agent = {}",
			account.getUuid(), account.getAuthenticatedDevice().get().getId(),
			System.currentTimeMillis() - context.getClient().getCreatedTimestamp(),
			context.getClient().getUserAgent());

		context.getClient().close(1000, "OK");

		String platform;

		try {
		    platform = UserAgentUtil.parseUserAgentString(context.getClient().getUserAgent()).getPlatform().name().toLowerCase();
		} catch (UnrecognizedUserAgentException e) {
		    platform = "unknown";
		}

		Metrics.counter(NO_LOCAL_SUBSCRIPTION_COUNTER_NAME, NO_LOCAL_SUBSCRIPTION_PLATFORM_TAG_NAME, platform).increment();
	    }
	}

	return Response.ok().build();
    }

    @Timed
    @GET
    @Path("/provisioning")
    public Response getProvisioningKeepAlive() {
	return Response.ok().build();
    }

}
