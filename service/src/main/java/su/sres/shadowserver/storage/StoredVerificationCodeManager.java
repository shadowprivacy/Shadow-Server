/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import java.util.Optional;
import su.sres.shadowserver.auth.StoredVerificationCode;

public class StoredVerificationCodeManager {

  private final VerificationCodeStore verificationCodeStore;
  private final int lifetime;

  public StoredVerificationCodeManager(final VerificationCodeStore verificationCodeStore, final int lifetime) {
    this.verificationCodeStore = verificationCodeStore;
    this.lifetime = lifetime;
  }

  public void store(String userLogin, StoredVerificationCode code) {
    verificationCodeStore.insert(userLogin, code, lifetime);
  }

  public void remove(String userLogin) {
    verificationCodeStore.remove(userLogin);
  }

  public Optional<StoredVerificationCode> getCodeForUserLogin(String userLogin) {
    return verificationCodeStore.findForUserLogin(userLogin);
  }
}
