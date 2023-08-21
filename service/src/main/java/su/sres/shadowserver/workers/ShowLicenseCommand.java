package su.sres.shadowserver.workers;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jetty.HttpsConnectorFactory;
import io.dropwizard.server.DefaultServerFactory;
import io.dropwizard.setup.Environment;
import javax0.license3j.Feature;
import javax0.license3j.License;
import javax0.license3j.io.LicenseReader;
import net.sourceforge.argparse4j.inf.Namespace;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.ServerLicenseUtil;

import static su.sres.shadowserver.util.ServerLicenseUtil.pubKey;

public class ShowLicenseCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(ShowLicenseCommand.class);

  public ShowLicenseCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment) throws Exception {

      }
    }, "showlicense", "Shows licensing information");
  }

  @Override
  protected void run(Environment environment, Namespace namespace, WhisperServerConfiguration config) {

    try {
      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      DefaultServerFactory sf = (DefaultServerFactory) config.getServerFactory();
      HttpsConnectorFactory cf = (HttpsConnectorFactory) sf.getApplicationConnectors().get(0);
      String keystore = cf.getKeyStorePath();
      String password = cf.getKeyStorePassword();
      String filepath = config.getLocalParametersConfiguration().getLicensePath();

      String domain = ServerLicenseUtil.getDomain(keystore, password);

      try (LicenseReader reader = new LicenseReader(filepath + "/" + Constants.serverLicenseFilename)) {
        License license = reader.read();

        Map<String, Feature> features = license.getFeatures();

        SimpleDateFormat df = new SimpleDateFormat("dd MMM yyyy HH:mm:ss");
        String expiry = df.format(features.get("expiryDate").getDate()) + " UTC";
        long validFrom = features.get("Valid From").getLong();
        String start = df.format(new Date(validFrom)) + " UTC";

        String[] volumes = features.get("Volumes").getString().split(":");
        int licensedUsers = Integer.valueOf(volumes[1]);

        if (!license.isOK(pubKey)) {
          logger.warn("The license key has been tampered with");
          return;
        } else if (!ServerLicenseUtil.featuresOK(license)) {
          logger.warn("The license key is corrupted");
          return;
        } else if (license.isExpired()) {
          logger.warn("The license has expired");
        } else if (validFrom > System.currentTimeMillis()) {
          logger.warn("The license is not yet valid");
        } else if (!ServerLicenseUtil.isHashValid(features.get("Shared").getString(), domain)) {
          logger.warn("The license is not relevant to your domain " + domain);
        } else {
          logger.info("The license key is OK");
        }

        logger.info(String.format("The license is valid from %s to %s, maximum number of users: %s", start, expiry, licensedUsers));

      } catch (NoSuchAlgorithmException e) {
        logger.error("Could not parse the license key");
      }

      catch (IOException e) {
        logger.warn("No license key found. You have a full-featured installation, maximum number of users: 3");
      }

    } catch (Exception ex) {
      logger.warn("Processing Exception", ex);
      throw new RuntimeException(ex);
    }
  }
}
