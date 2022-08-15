/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import io.dropwizard.cli.Command;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import su.sres.shadowserver.util.ServerLicenseUtil;

public class LicenseHashCommand extends Command {

    public LicenseHashCommand() {
	super("token", "Generates a token for activation key generation");
    }

    @Override
    public void configure(Subparser subparser) {
	subparser.addArgument("-d", "--domain")
		.dest("domain_name")
		.type(String.class)
		.required(true)
		.help("Fully qualified domain name of your Shadow server");
    }

    @Override
    public void run(Bootstrap<?> bootstrap, Namespace namespace) throws Exception {

	System.out.println("Token generated successfully. Record this token and communicate it to your distributor for generation of the activation key: " + ServerLicenseUtil.calculateHash(namespace.getString("domain_name")));

    }
}
