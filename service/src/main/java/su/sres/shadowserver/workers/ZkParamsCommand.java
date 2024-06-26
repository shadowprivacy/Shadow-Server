/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.util.Base64;

import org.signal.zkgroup.ServerPublicParams;
import org.signal.zkgroup.ServerSecretParams;

import io.dropwizard.cli.Command;
import io.dropwizard.setup.Bootstrap;

public class ZkParamsCommand extends Command {

  public ZkParamsCommand() {
    super("zkparams", "Generates server zkparams");
  }

  @Override
  public void configure(Subparser subparser) {

  }

  @Override
  public void run(Bootstrap<?> bootstrap, Namespace namespace) throws Exception {
    ServerSecretParams serverSecretParams = ServerSecretParams.generate();
    ServerPublicParams serverPublicParams = serverSecretParams.getPublicParams();

    System.out.println("Public: " + Base64.getEncoder().withoutPadding().encodeToString(serverPublicParams.serialize()));
    System.out.println("Private: " + Base64.getEncoder().withoutPadding().encodeToString(serverSecretParams.serialize()));
  }

}
