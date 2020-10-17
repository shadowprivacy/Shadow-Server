package su.sres.shadowserver.workers;

import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import su.sres.shadowserver.util.Base64;

import java.io.FileInputStream;
import java.io.IOException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import io.dropwizard.cli.Command;
import io.dropwizard.setup.Bootstrap;

public class CertHashCommand extends Command {

	public CertHashCommand() {
		super("hashcalc", "Generates a SHA256 hash of an X509 certificate");
	}

	@Override
	public void configure(Subparser subparser) {
		subparser.addArgument("-certfile", "--certfile")
		         .dest("certificate_file")
		         .type(String.class)
		         .required(true)
				 .help("Path to the certificate file");
	}

	@Override
	public void run(Bootstrap<?> bootstrap, Namespace namespace) throws Exception {

		FileInputStream inputStream = null;

		try {
			inputStream = new FileInputStream(namespace.getString("certificate_file"));
			X509Certificate x509Certificate = (X509Certificate) CertificateFactory.getInstance("X509")
					                                                              .generateCertificate(inputStream);

			System.out.println("The certificate's public key hash is: sha256/"
					+ calculatePublicKeyHash(x509Certificate.getEncoded()));

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if (inputStream != null) {
					inputStream.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	private static String calculatePublicKeyHash(byte[] cert) throws NoSuchAlgorithmException {
		MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
		return Base64.encodeBytes(messageDigest.digest(cert));
	}

}