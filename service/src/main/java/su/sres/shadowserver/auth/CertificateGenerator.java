/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
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

    private final ECPrivateKey privateKey;
    private final int expiresDays;
    private final ServerCertificate serverCertificate;

    public CertificateGenerator(byte[] serverCertificate, ECPrivateKey privateKey, int expiresDays)
	    throws InvalidProtocolBufferException {
	this.privateKey = privateKey;
	this.expiresDays = expiresDays;
	this.serverCertificate = ServerCertificate.parseFrom(serverCertificate);
    }

    public byte[] createFor(Account account, Device device, boolean includeUserLogin, boolean includeUuid) throws IOException, InvalidKeyException {
	SenderCertificate.Certificate.Builder builder = SenderCertificate.Certificate.newBuilder()
		.setSenderDevice(Math.toIntExact(device.getId()))
		.setExpires(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(expiresDays))
		.setIdentityKey(ByteString.copyFrom(Base64.decode(account.getIdentityKey())))
		.setSigner(serverCertificate);

	if (!includeUserLogin && !includeUuid) {
	    throw new IllegalArgumentException("Certificates must include one of a sender user login or UUID");
	}

	if (includeUserLogin) {
	    builder.setSender(account.getUserLogin());
	}

	if (includeUuid) {
	    builder.setSenderUuid(account.getUuid().toString());
	}

	byte[] certificate = builder.build().toByteArray();
	byte[] signature = Curve.calculateSignature(privateKey, certificate);

	return SenderCertificate.newBuilder()
		.setCertificate(ByteString.copyFrom(certificate))
		.setSignature(ByteString.copyFrom(signature))
		.build()
		.toByteArray();
    }

}