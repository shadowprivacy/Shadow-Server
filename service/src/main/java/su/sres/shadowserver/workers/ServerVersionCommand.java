/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.workers;

import io.dropwizard.cli.Command;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import su.sres.shadowserver.WhisperServerVersion;

public class ServerVersionCommand extends Command {

  public ServerVersionCommand() {
    super("version", "Print the version of the service");
  }

  @Override
  public void configure(final Subparser subparser) {
  }

  @Override
  public void run(final Bootstrap<?> bootstrap, final Namespace namespace) throws Exception {
    System.out.println(WhisperServerVersion.getServerVersion());
  }
}
