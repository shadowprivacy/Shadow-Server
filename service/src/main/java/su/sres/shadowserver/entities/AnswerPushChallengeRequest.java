/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.entities;

import javax.validation.constraints.NotBlank;

public class AnswerPushChallengeRequest extends AnswerChallengeRequest {

  @NotBlank
  private String challenge;

  public String getChallenge() {
    return challenge;
  }
}
