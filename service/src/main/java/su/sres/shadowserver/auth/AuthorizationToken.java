package su.sres.shadowserver.auth;


import com.fasterxml.jackson.annotation.JsonProperty;

import su.sres.shadowserver.util.Util;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

public class AuthorizationToken {

  @JsonProperty
  private String token;

  public AuthorizationToken(String token) {
    this.token = token;
  }

  public AuthorizationToken() {}

}
