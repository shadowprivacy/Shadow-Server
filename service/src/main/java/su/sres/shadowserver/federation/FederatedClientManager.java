/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.federation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.setup.Environment;
import su.sres.shadowserver.configuration.FederationConfiguration;

public class FederatedClientManager {

  private final Logger logger = LoggerFactory.getLogger(FederatedClientManager.class);

  private final HashMap<String, FederatedClient> clients = new HashMap<>();

  public FederatedClientManager(Environment environment,
                                JerseyClientConfiguration clientConfig,
                                FederationConfiguration federationConfig)
      throws IOException
  {
    List<FederatedPeer> peers    = federationConfig.getPeers();
    String              identity = federationConfig.getName();

    if (peers != null) {
      for (FederatedPeer peer : peers) {
        logger.info("Adding peer: " + peer.getName());
        clients.put(peer.getName(), new FederatedClient(environment, clientConfig, identity, peer));
      }
    }
  }

  public FederatedClient getClient(String name) throws NoSuchPeerException {
    FederatedClient client = clients.get(name);

    if (client == null) {
      throw new NoSuchPeerException(name);
    }

    return client;
  }

  public List<FederatedClient> getClients() {
    return new LinkedList<>(clients.values());
  }

}
