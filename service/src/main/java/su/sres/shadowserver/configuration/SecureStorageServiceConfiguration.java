/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import javax.validation.constraints.NotEmpty;

public class SecureStorageServiceConfiguration {

  @NotEmpty
  @JsonProperty
  private String userAuthenticationTokenSharedSecret;

  public byte[] getUserAuthenticationTokenSharedSecret() throws DecoderException {
    return Hex.decodeHex(userAuthenticationTokenSharedSecret.toCharArray());
  }

}