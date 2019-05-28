package su.sres.shadowserver.auth;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import su.sres.shadowserver.crypto.Curve;
import su.sres.shadowserver.crypto.ECPrivateKey;
import su.sres.shadowserver.entities.MessageProtos.SenderCertificate;
import su.sres.shadowserver.entities.MessageProtos.ServerCertificate;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.util.Base64;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.concurrent.TimeUnit;

public class CertificateGenerator {

  private final ECPrivateKey      privateKey;
  private final int               expiresDays;
  private final ServerCertificate serverCertificate;

  public CertificateGenerator(byte[] serverCertificate, ECPrivateKey privateKey, int expiresDays)
      throws InvalidProtocolBufferException
  {
    this.privateKey        = privateKey;
    this.expiresDays       = expiresDays;
    this.serverCertificate = ServerCertificate.parseFrom(serverCertificate);
  }

  public byte[] createFor(Account account, Device device) throws IOException, InvalidKeyException {
    byte[] certificate = SenderCertificate.Certificate.newBuilder()
                                                      .setSender(account.getNumber())
                                                      .setSenderDevice(Math.toIntExact(device.getId()))
                                                      .setExpires(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(expiresDays))
                                                      .setIdentityKey(ByteString.copyFrom(Base64.decode(account.getIdentityKey())))
                                                      .setSigner(serverCertificate)
                                                      .build()
                                                      .toByteArray();

    byte[] signature = Curve.calculateSignature(privateKey, certificate);

    return SenderCertificate.newBuilder()
                            .setCertificate(ByteString.copyFrom(certificate))
                            .setSignature(ByteString.copyFrom(signature))
                            .build()
                            .toByteArray();
  }

}