package su.sres.shadowserver.workers;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.util.Base64;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jetty.HttpsConnectorFactory;
import io.dropwizard.server.DefaultServerFactory;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.util.ServerLicenseUtil;

public class GenerateQRCodeCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(GenerateQRCodeCommand.class);

  private static final String SERVER_URL = "SRVURL";
  private static final String SERVER_CERTIFICATE_HASH = "SRVCH";
  private static final String FCM_SENDER_ID = "FCMSID";
  private static final String PROXY = "PRX";
  private static final String LINEBREAK = "\n";

  private static final String QR_PATH = "qrcode.png";

  public GenerateQRCodeCommand() {
    super(new Application<WhisperServerConfiguration>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment) throws Exception {

      }
    }, "qrcode", "Generate the registration QR code based on the contents of the configuration file");
  }

  @Override
  public void configure(Subparser subparser) {

    super.configure(subparser);

    subparser.addArgument("-p", "--port")
        .dest("port")
        .type(Integer.class)
        .required(false)
        .setDefault(0)
        .help("The port on which your Shadow server is accessible from the Internet");
    subparser.addArgument("-x", "--proxy")
    .dest("proxy")
    .type(String.class)
    .required(false)   
    .help("Hostname of your proxy module (optional)");
  }

  @Override
  protected void run(Environment environment, Namespace namespace, WhisperServerConfiguration config) {

    try {
      environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      DefaultServerFactory sf = (DefaultServerFactory) config.getServerFactory();
      HttpsConnectorFactory cf = (HttpsConnectorFactory) sf.getApplicationConnectors().get(0);
      String keystore = cf.getKeyStorePath();
      String password = cf.getKeyStorePassword();
      String defaultPort = String.valueOf(cf.getPort());

      String domain = ServerLicenseUtil.getDomain(keystore, password);

      Integer port = namespace.getInt("port");
      @Nullable String proxy = namespace.getString("proxy");
      String serverUrl;

      if (port != 0) {
        serverUrl = "https://" + domain + ":" + String.valueOf(port);
      } else {
        logger.warn("Server port not specified, using the value from the configuration file. This may be invalid if your server is behind NAT");
        serverUrl = "https://" + domain + ":" + defaultPort;
      }

      Certificate cert = null;

      KeyStore ks = KeyStore.getInstance("pkcs12");
      ks.load(new FileInputStream(keystore), password.toCharArray());

      if (ks.containsAlias("shadow_a")) {
        cert = ks.getCertificate("shadow_a");
      } else if (ks.containsAlias("shadow_b")) {
        cert = ks.getCertificate("shadow_b");
      }

      String hash = "sha256/" + calculateCertHash(cert.getEncoded());

      String fcmId = config.getServiceConfiguration().getFcmSenderId();

      String toEncode;
      
      if (proxy != null) {
        toEncode = SERVER_URL + "," + SERVER_CERTIFICATE_HASH + "," + FCM_SENDER_ID + "," + PROXY
            + LINEBREAK
            + serverUrl + "," + hash + "," + fcmId + "," + proxy;
      } else {
        toEncode = SERVER_URL + "," + SERVER_CERTIFICATE_HASH + "," + FCM_SENDER_ID
            + LINEBREAK
            + serverUrl + "," + hash + "," + fcmId;
      }

      QRCodeWriter barcodeWriter = new QRCodeWriter();
      BitMatrix bitMatrix = barcodeWriter.encode(toEncode, BarcodeFormat.QR_CODE, 300, 300);

      File file = new File("./", QR_PATH);

      MatrixToImageWriter.writeToPath(bitMatrix, "png", file.toPath());

      logger.info("The registration QR code has been successfully generated and placed to the current directory");

    } catch (Exception ex) {
      logger.warn("Processing Exception", ex);
      throw new RuntimeException(ex);
    }
  }

  private static String calculateCertHash(byte[] cert) throws NoSuchAlgorithmException {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
    return Base64.getEncoder().encodeToString(messageDigest.digest(cert));
  }
}
