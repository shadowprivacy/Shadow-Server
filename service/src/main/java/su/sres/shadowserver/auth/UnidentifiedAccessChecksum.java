/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class UnidentifiedAccessChecksum {

  public static String generateFor(Optional<byte[]> unidentifiedAccessKey) {
    try {
      if (!unidentifiedAccessKey.isPresent()|| unidentifiedAccessKey.get().length != 16) return null;

      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(unidentifiedAccessKey.get(), "HmacSHA256"));

      return Base64.getEncoder().encodeToString(mac.doFinal(new byte[32]));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

}