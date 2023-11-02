/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

import javax.annotation.Nullable;

import io.dropwizard.jetty.HttpsConnectorFactory;
import io.dropwizard.server.DefaultServerFactory;
import javax0.license3j.Feature;
import javax0.license3j.License;
import javax0.license3j.io.LicenseReader;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.AccountsScyllaDbConfiguration;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;

public class ServerLicenseUtil {
  
  public static final byte[] pubKey = new byte[] {
      (byte) 0x52,
      (byte) 0x53, (byte) 0x41, (byte) 0x00, (byte) 0x30, (byte) 0x82, (byte) 0x01, (byte) 0x22, (byte) 0x30,
      (byte) 0x0D, (byte) 0x06, (byte) 0x09, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0x86, (byte) 0xF7,
      (byte) 0x0D, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x05, (byte) 0x00, (byte) 0x03, (byte) 0x82,
      (byte) 0x01, (byte) 0x0F, (byte) 0x00, (byte) 0x30, (byte) 0x82, (byte) 0x01, (byte) 0x0A, (byte) 0x02,
      (byte) 0x82, (byte) 0x01, (byte) 0x01, (byte) 0x00, (byte) 0x8C, (byte) 0x1C, (byte) 0xEB, (byte) 0xFE,
      (byte) 0x46, (byte) 0x87, (byte) 0x19, (byte) 0x99, (byte) 0x4B, (byte) 0x7F, (byte) 0x4C, (byte) 0x42,
      (byte) 0x17, (byte) 0x83, (byte) 0x36, (byte) 0x44, (byte) 0xAE, (byte) 0xDA, (byte) 0x73, (byte) 0xB0,
      (byte) 0x73, (byte) 0xA0, (byte) 0x0E, (byte) 0x81, (byte) 0x63, (byte) 0xBA, (byte) 0xFC, (byte) 0x08,
      (byte) 0xFC, (byte) 0x88, (byte) 0x5F, (byte) 0x42, (byte) 0x57, (byte) 0x70, (byte) 0xA1, (byte) 0xFF,
      (byte) 0x96, (byte) 0x72, (byte) 0xD3, (byte) 0xAA, (byte) 0x09, (byte) 0x2F, (byte) 0x7E, (byte) 0x6B,
      (byte) 0x4C, (byte) 0xA8, (byte) 0x37, (byte) 0xCB, (byte) 0x1A, (byte) 0xB6, (byte) 0x25, (byte) 0x1A,
      (byte) 0x61, (byte) 0x22, (byte) 0xC4, (byte) 0x39, (byte) 0xB6, (byte) 0x89, (byte) 0x65, (byte) 0x78,
      (byte) 0x25, (byte) 0x18, (byte) 0x3C, (byte) 0x83, (byte) 0x23, (byte) 0x73, (byte) 0x6C, (byte) 0x4C,
      (byte) 0xAF, (byte) 0x45, (byte) 0xF1, (byte) 0xFB, (byte) 0x49, (byte) 0x50, (byte) 0x87, (byte) 0xB8,
      (byte) 0xAE, (byte) 0x07, (byte) 0xBC, (byte) 0x2C, (byte) 0x2E, (byte) 0x5C, (byte) 0x1C, (byte) 0x20,
      (byte) 0x86, (byte) 0xD6, (byte) 0x1B, (byte) 0xB5, (byte) 0x41, (byte) 0x1F, (byte) 0xA5, (byte) 0x19,
      (byte) 0x87, (byte) 0x9A, (byte) 0x20, (byte) 0x51, (byte) 0x5F, (byte) 0x8A, (byte) 0xC5, (byte) 0x64,
      (byte) 0xEA, (byte) 0x1F, (byte) 0x55, (byte) 0x84, (byte) 0x2C, (byte) 0x0F, (byte) 0xAA, (byte) 0x2B,
      (byte) 0x66, (byte) 0xB1, (byte) 0xF1, (byte) 0x19, (byte) 0xCE, (byte) 0x6F, (byte) 0xBD, (byte) 0x0F,
      (byte) 0x75, (byte) 0xAD, (byte) 0x8E, (byte) 0x61, (byte) 0x4C, (byte) 0x95, (byte) 0x5A, (byte) 0x54,
      (byte) 0xFD, (byte) 0x92, (byte) 0x33, (byte) 0xDF, (byte) 0xE5, (byte) 0xDD, (byte) 0x41, (byte) 0xAC,
      (byte) 0x63, (byte) 0xB2, (byte) 0x49, (byte) 0xA1, (byte) 0xE7, (byte) 0x82, (byte) 0xC8, (byte) 0x7C,
      (byte) 0x13, (byte) 0x49, (byte) 0xBF, (byte) 0xC0, (byte) 0xB3, (byte) 0x3D, (byte) 0xD3, (byte) 0x1F,
      (byte) 0x01, (byte) 0x84, (byte) 0x9E, (byte) 0xC4, (byte) 0x40, (byte) 0x36, (byte) 0x7E, (byte) 0x48,
      (byte) 0x9B, (byte) 0xA9, (byte) 0x0B, (byte) 0x73, (byte) 0x56, (byte) 0x24, (byte) 0x7E, (byte) 0x09,
      (byte) 0x5E, (byte) 0x12, (byte) 0xEB, (byte) 0x07, (byte) 0x7E, (byte) 0xDE, (byte) 0x3C, (byte) 0xC3,
      (byte) 0xE8, (byte) 0xA7, (byte) 0x80, (byte) 0x7E, (byte) 0xCD, (byte) 0x27, (byte) 0xCB, (byte) 0xB7,
      (byte) 0xF2, (byte) 0xF3, (byte) 0xC5, (byte) 0xA3, (byte) 0x11, (byte) 0xA0, (byte) 0x3D, (byte) 0xEE,
      (byte) 0xAB, (byte) 0xB1, (byte) 0xC0, (byte) 0x92, (byte) 0xC3, (byte) 0x1B, (byte) 0xC9, (byte) 0xA2,
      (byte) 0xF7, (byte) 0x51, (byte) 0xB8, (byte) 0x66, (byte) 0x64, (byte) 0x5D, (byte) 0x74, (byte) 0x4D,
      (byte) 0x94, (byte) 0xA1, (byte) 0x9A, (byte) 0x43, (byte) 0x92, (byte) 0x38, (byte) 0xDB, (byte) 0xF8,
      (byte) 0x55, (byte) 0x56, (byte) 0x5B, (byte) 0xB9, (byte) 0x25, (byte) 0xA0, (byte) 0xD7, (byte) 0xE4,
      (byte) 0xCB, (byte) 0xCF, (byte) 0xB1, (byte) 0x47, (byte) 0xA7, (byte) 0x39, (byte) 0x74, (byte) 0x21,
      (byte) 0xDE, (byte) 0x9E, (byte) 0xC6, (byte) 0xCE, (byte) 0x0C, (byte) 0x95, (byte) 0xED, (byte) 0x52,
      (byte) 0xE8, (byte) 0xAD, (byte) 0xFB, (byte) 0x8C, (byte) 0xCC, (byte) 0xF6, (byte) 0x4E, (byte) 0x44,
      (byte) 0xF4, (byte) 0xE4, (byte) 0x6F, (byte) 0x5B, (byte) 0x6E, (byte) 0xDB, (byte) 0x84, (byte) 0xF8,
      (byte) 0x11, (byte) 0x56, (byte) 0x7A, (byte) 0x75, (byte) 0x02, (byte) 0x03, (byte) 0x01, (byte) 0x00,
      (byte) 0x01
  };

  public static Pair<LicenseStatus, Pair<Integer, Integer>> validate(WhisperServerConfiguration config) throws NumberFormatException, KeyStoreException, CertificateException {

    DefaultServerFactory sf = (DefaultServerFactory) config.getServerFactory();
    HttpsConnectorFactory cf = (HttpsConnectorFactory) sf.getApplicationConnectors().get(0);
    String keystore = cf.getKeyStorePath();
    String password = cf.getKeyStorePassword();
    
    String filepath = config.getLocalParametersConfiguration().getLicensePath();
    int actualUsers = actualUsers(config);

    try (LicenseReader reader = new LicenseReader(filepath + "/" + Constants.serverLicenseFilename)) {
      License license = reader.read();

      Map<String, Feature> features = license.getFeatures();

      if (!license.isOK(pubKey)) {
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.TAMPERED, new Pair<Integer, Integer>(-1, actualUsers));
      } else if (!featuresOK(license)) {
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.CORRUPTED, new Pair<Integer, Integer>(-1, actualUsers));
      } else if (license.isExpired()) {
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.EXPIRED, new Pair<Integer, Integer>(-1, actualUsers));
      } else if (features.get("Valid From").getLong() > System.currentTimeMillis()) {
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.NYV, new Pair<Integer, Integer>(-1, actualUsers));
      } else if (!isHashValid(features.get("Shared").getString(), getDomain(keystore, password))) {
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.IRRELEVANT, new Pair<Integer, Integer>(-1, actualUsers));
      } else {

        String[] volumes = features.get("Volumes").getString().split(":");
        int licensedUsers = Integer.valueOf(volumes[1]);

        if (licensedUsers >= actualUsers || actualUsers <= 3) {
          return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.OK, new Pair<Integer, Integer>(licensedUsers, actualUsers));
        } else {
          return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.OVERSUBSCRIBED, new Pair<Integer, Integer>(licensedUsers, actualUsers));
        }
      }

    } catch (NoSuchAlgorithmException e) {
      return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.UNSPECIFIED, new Pair<Integer, Integer>(-1, actualUsers));
    }

    catch (IOException e) {

      if (actualUsers <= 3) {
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.OK, new Pair<Integer, Integer>(3, actualUsers));
      } else
        return new Pair<LicenseStatus, Pair<Integer, Integer>>(LicenseStatus.ABSENT, new Pair<Integer, Integer>(-1, actualUsers));
    }    
  }  

  private static int actualUsers(WhisperServerConfiguration config) {
    
    AccountsScyllaDbConfiguration scyllaAccountsConfig = config.getAccountsScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaPendingAccountsConfig = config.getPendingAccountsScyllaDbConfiguration();
    DynamoDbClient accountsScyllaDbClient = ScyllaDbFromConfig.client(scyllaAccountsConfig);
    DynamoDbClient pendingAccountsScyllaDbClient = ScyllaDbFromConfig.client(scyllaPendingAccountsConfig);
    String accountsTableName = config.getAccountsScyllaDbConfiguration().getTableName();
    String pendingAccountsTableName = config.getPendingAccountsScyllaDbConfiguration().getTableName();
        
    final ScanRequest.Builder accountsScanRequestBuilder = ScanRequest.builder();
    final ScanRequest.Builder pendingAccountsScanRequestBuilder = ScanRequest.builder();
    
    accountsScanRequestBuilder.tableName(accountsTableName);
    pendingAccountsScanRequestBuilder.tableName(pendingAccountsTableName);  
    
    int activeUsers = (int) accountsScyllaDbClient.scanPaginator(accountsScanRequestBuilder.build()).items().stream().count();    
    int pendingUsers = (int) pendingAccountsScyllaDbClient.scanPaginator(pendingAccountsScanRequestBuilder.build()).items().stream().count();    

    return (activeUsers + pendingUsers);
  }

  public static boolean featuresOK(License license) {
    Map<String, Feature> featureMap = license.getFeatures();
    @Nullable
    Feature assignee = featureMap.get("Assignee");
    @Nullable
    Feature validFrom = featureMap.get("Valid From");
    @Nullable
    Feature shared = featureMap.get("Shared");
    @Nullable
    Feature volumes = featureMap.get("Volumes");

    if (assignee == null || validFrom == null || shared == null || volumes == null) {
      return false;
    } else {
      return assignee.getString() != null;
    }
  }

  public static boolean isHashValid(String hash, String domain) throws NoSuchAlgorithmException {

    return calculateHash(domain).equals(hash);
  }

  public static String calculateHash(String domain) throws NoSuchAlgorithmException {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
    return Base64.getEncoder().encodeToString(messageDigest.digest(domain.getBytes(StandardCharsets.UTF_8)));
  }

  public static String getDomain(String keystore, String password) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
    KeyStore ks = KeyStore.getInstance("pkcs12");
    ks.load(new FileInputStream(keystore), password.toCharArray());

    if (ks.containsAlias("shadow_a")) {
      Certificate shadowcertA = ks.getCertificate("shadow_a");
      return extractCN(shadowcertA);

    } else if (ks.containsAlias("shadow_b")) {
      Certificate shadowcertB = ks.getCertificate("shadow_b");
      return extractCN(shadowcertB);

    } else
      return "example.com";
  }

  private static String extractCN(Certificate cert) {
    X509Certificate xcert = (X509Certificate) cert;
    String dname = xcert.getSubjectX500Principal().getName();

    int start = dname.indexOf("CN");
    String tmpName, name = "";
    if (start >= 0) {
      tmpName = dname.substring(start + 3);
      int end = tmpName.indexOf(",");
      if (end > 0) {
        name = tmpName.substring(0, end);
      } else {
        name = tmpName;
      }
    }

    return name;
  }

  public enum LicenseStatus {
    OK,
    TAMPERED,
    CORRUPTED,
    EXPIRED,
    NYV,
    ABSENT,
    OVERSUBSCRIBED,
    IRRELEVANT,
    UNSPECIFIED
  }

}
