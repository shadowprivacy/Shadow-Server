/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.entities;

import javax.validation.constraints.NotBlank;

public class AnswerRecaptchaChallengeRequest extends AnswerChallengeRequest {

  @NotBlank
  private String token;

  @NotBlank
  private String captcha;

  public String getToken() {
    return token;
  }

  public String getCaptcha() {
    return captcha;
  }
}
