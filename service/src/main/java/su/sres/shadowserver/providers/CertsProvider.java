package su.sres.shadowserver.providers;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;

import su.sres.shadowserver.configuration.LocalParametersConfiguration;
import su.sres.shadowserver.configuration.ServiceConfiguration;

public class CertsProvider {

	private static final String CLOUD_CERT_ALIAS = "cloud";
	private static final String SHADOW_CERT_ALIAS = "shadow";
	private static final String STORAGE_CERT_ALIAS = "storage";

	private LocalParametersConfiguration localParametersConfiguration;
	private ServiceConfiguration serviceConfiguration;

	public CertsProvider(LocalParametersConfiguration localParametersConfiguration, ServiceConfiguration serviceConfiguration) {
		this.localParametersConfiguration = localParametersConfiguration;
		this.serviceConfiguration = serviceConfiguration;
	}

	public SystemCerts getCerts() {

		String  keystorePath     = localParametersConfiguration.getKeyStorePath(),
				keystorePassword = localParametersConfiguration.getKeyStorePassword();

		byte[]  cloudCertificateA   = null,
				cloudCertificateB   = null,
				shadowCertificateA  = null,
				shadowCertificateB  = null,
				storageCertificateA = null,
				storageCertificateB = null;

		try (InputStream keystoreInputStream = new FileInputStream(keystorePath)) {

			KeyStore keystore;

			try {
				keystore = KeyStore.getInstance("PKCS12");
				keystore.load(keystoreInputStream, keystorePassword.toCharArray());

			} catch (KeyStoreException | NoSuchAlgorithmException | IOException	| CertificateException e) {
				return new SystemCerts(null, null, null, null, null, null);
			}

			try {
				if (keystore.containsAlias(CLOUD_CERT_ALIAS + "_a")) {
				cloudCertificateA = keystore.getCertificate(CLOUD_CERT_ALIAS + "_a").getEncoded();
				}
			} catch (KeyStoreException | CertificateEncodingException e) {}

			try {
				if (keystore.containsAlias(CLOUD_CERT_ALIAS + "_b")) {
				cloudCertificateB = keystore.getCertificate(CLOUD_CERT_ALIAS + "_b").getEncoded();
				}
			} catch (KeyStoreException | CertificateEncodingException e) {}

			try {
				if (keystore.containsAlias(SHADOW_CERT_ALIAS + "_a")) {
				shadowCertificateA = keystore.getCertificate(SHADOW_CERT_ALIAS + "_a").getEncoded();
				}
			} catch (KeyStoreException | CertificateEncodingException e) {}

			try {
				if (keystore.containsAlias(SHADOW_CERT_ALIAS + "_b")) {
				shadowCertificateB = keystore.getCertificate(SHADOW_CERT_ALIAS + "_b").getEncoded();
				}
			} catch (KeyStoreException | CertificateEncodingException e) {}

			try {
				if (keystore.containsAlias(STORAGE_CERT_ALIAS + "_a")) {
				storageCertificateA = keystore.getCertificate(STORAGE_CERT_ALIAS + "_a").getEncoded();
				}
			} catch (KeyStoreException | CertificateEncodingException e) {}

			try {
				if (keystore.containsAlias(STORAGE_CERT_ALIAS + "_b")) {
				storageCertificateB = keystore.getCertificate(STORAGE_CERT_ALIAS + "_b").getEncoded();
				}
			} catch (KeyStoreException | CertificateEncodingException e) {}

		} catch (IOException e) {
			return new SystemCerts(cloudCertificateA, cloudCertificateB, shadowCertificateA, shadowCertificateB,
					storageCertificateA, storageCertificateB);
		}

		return new SystemCerts(cloudCertificateA, cloudCertificateB, shadowCertificateA, shadowCertificateB,
				storageCertificateA, storageCertificateB);

	}

	public SystemCertsVersion getCertsVersion() {

		return new SystemCertsVersion(serviceConfiguration.getCertsVersion());
	}
}