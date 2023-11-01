/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.recaptcha;

public interface RecaptchaClient {
  boolean verify(String token, String ip);
}
