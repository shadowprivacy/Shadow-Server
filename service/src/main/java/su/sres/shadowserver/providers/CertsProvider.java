package su.sres.shadowserver.providers;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import su.sres.shadowserver.configuration.ServiceConfiguration;
import su.sres.shadowserver.util.Base64;

public class CertsProvider {


	private static final String CLOUD_CERT_ALIAS = "cloud";

	private ServiceConfiguration serviceConfiguration;


	public CertsProvider(ServiceConfiguration serviceConfiguration) {
		this.serviceConfiguration = serviceConfiguration;
	}

	public SystemCerts getCerts() {

		String  keystorePath = serviceConfiguration.getKeyStorePath(),
		    	keystorePassword = serviceConfiguration.getKeyStorePassword();
		
		byte[] cloudCertificate;							

			try(InputStream keystoreInputStream = new FileInputStream(keystorePath)) {

				KeyStore keystore = KeyStore.getInstance("PKCS12");
	     		keystore.load(keystoreInputStream, keystorePassword.toCharArray());		
	
				cloudCertificate = keystore.getCertificate(CLOUD_CERT_ALIAS).getEncoded();								
				
			} catch (CertificateException | KeyStoreException | NoSuchAlgorithmException | IOException e) {
				e.printStackTrace();
				return new SystemCerts(null);
			}			
			
			return new SystemCerts(cloudCertificate);		
	}
	
	public static class Serializing extends JsonSerializer<byte[]> {   
		
		@Override
	    public void serialize(byte[] bytes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
	        throws IOException, JsonProcessingException
	    {
	      jsonGenerator.writeString(Base64.encodeBytes(bytes));	  	   	
	      	      
	    }
	  }
}