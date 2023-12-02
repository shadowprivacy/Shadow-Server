/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.util.List;
import java.util.Map;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

public class PaymentsServiceConfiguration {
  
  @NotEmpty
  @JsonProperty
  private String userAuthenticationTokenSharedSecret;
  
  @NotBlank
  @JsonProperty
  private String coinMarketCapApiKey;

  @JsonProperty
  @NotEmpty
  private Map<@NotBlank String, Integer> coinMarketCapCurrencyIds;

  @NotEmpty
  @JsonProperty
  private String fixerApiKey;
  
  @NotNull
  @JsonProperty
  private boolean fixerPaid;

  @NotEmpty
  @JsonProperty
  private List<String> paymentCurrencies;  

  public byte[] getUserAuthenticationTokenSharedSecret() throws DecoderException {
    return Hex.decodeHex(userAuthenticationTokenSharedSecret.toCharArray());
  }
  
  public String getCoinMarketCapApiKey() {
    return coinMarketCapApiKey;
  }

  public Map<String, Integer> getCoinMarketCapCurrencyIds() {
    return coinMarketCapCurrencyIds;
  }

  public String getFixerApiKey() {
    return fixerApiKey;
  }
  
  public boolean isFixerPaid() {
    return fixerPaid;
  }

  public List<String> getPaymentCurrencies() {
    return paymentCurrencies;
  }

}
