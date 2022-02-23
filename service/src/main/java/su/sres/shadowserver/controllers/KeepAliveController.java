package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.sres.websocket.session.WebSocketSession;
import su.sres.websocket.session.WebSocketSessionContext;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import io.dropwizard.auth.Auth;
import su.sres.shadowserver.push.ClientPresenceManager;
import su.sres.shadowserver.storage.Account;

@Path("/v1/keepalive")
public class KeepAliveController {

    private final Logger logger = LoggerFactory.getLogger(KeepAliveController.class);

    private final ClientPresenceManager clientPresenceManager;

    public KeepAliveController(final ClientPresenceManager clientPresenceManager) {
	this.clientPresenceManager = clientPresenceManager;
    }

    @Timed
    @GET
    public Response getKeepAlive(@Auth Account account,
	    @WebSocketSession WebSocketSessionContext context) {
	if (account != null) {
	    if (!clientPresenceManager.isLocallyPresent(account.getUuid(), account.getAuthenticatedDevice().get().getId())) {
		logger.warn("***** No local subscription found for {}::{}", account.getUuid(), account.getAuthenticatedDevice().get().getId());
		context.getClient().close(1000, "OK");
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
