/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jetty.HttpsConnectorFactory;
import io.dropwizard.server.DefaultServerFactory;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.util.ServerLicenseUtil;

public class LicenseHashCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(LicenseHashCommand.class);

  public LicenseHashCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment) throws Exception {

      }
    }, "token", "Generates a token for activation key generation");
  }

  @Override
  protected void run(Environment environment, Namespace namespace, WhisperServerConfiguration config) {

    try {
      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      DefaultServerFactory sf = (DefaultServerFactory) config.getServerFactory();
      HttpsConnectorFactory cf = (HttpsConnectorFactory) sf.getApplicationConnectors().get(0);
      String keystore = cf.getKeyStorePath();
      String password = cf.getKeyStorePassword();

      String domain = ServerLicenseUtil.getDomain(keystore, password);

      logger.info("Token generated successfully. Record this token and communicate it to your distributor for generation of the activation key:\n" + ServerLicenseUtil.calculateHash(domain));

    } catch (Exception ex) {
      logger.warn("Processing Exception", ex);
      throw new RuntimeException(ex);
    }
  }
}
