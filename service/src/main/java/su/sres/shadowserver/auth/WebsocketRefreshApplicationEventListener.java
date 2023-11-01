/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.auth;

import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;
import su.sres.shadowserver.push.ClientPresenceManager;

/**
 * Delegates request events to a listener that watches for intra-request changes that require websocket refreshes
 */
public class WebsocketRefreshApplicationEventListener implements ApplicationEventListener {

  private final WebsocketRefreshRequestEventListener websocketRefreshRequestEventListener;

  public WebsocketRefreshApplicationEventListener(final ClientPresenceManager clientPresenceManager) {
    this.websocketRefreshRequestEventListener = new WebsocketRefreshRequestEventListener(clientPresenceManager,
        new AuthEnablementRefreshRequirementProvider(),
        new UserLoginChangeRefreshRequirementProvider());
  }

  @Override
  public void onEvent(final ApplicationEvent event) {
  }

  @Override
  public RequestEventListener onRequest(final RequestEvent requestEvent) {
    return websocketRefreshRequestEventListener;
  }
}

