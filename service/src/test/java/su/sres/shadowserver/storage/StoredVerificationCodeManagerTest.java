/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.sres.shadowserver.auth.StoredVerificationCode;

class StoredVerificationCodeManagerTest {

  private VerificationCodeStore verificationCodeStore;

  private StoredVerificationCodeManager storedVerificationCodeManager;
  
  private int lifetime = 24;

  @BeforeEach
  void setUp() {
    verificationCodeStore = mock(VerificationCodeStore.class);

    storedVerificationCodeManager = new StoredVerificationCodeManager(verificationCodeStore, lifetime);
  }

  @Test
  void store() {
    final String number = "+18005551234";
    final StoredVerificationCode code = mock(StoredVerificationCode.class);

    storedVerificationCodeManager.store(number, code);

    verify(verificationCodeStore).insert(number, code, lifetime);
  }

  @Test
  void remove() {
    final String number = "+18005551234";

    storedVerificationCodeManager.remove(number);

    verify(verificationCodeStore).remove(number);
  }

  @Test
  void getCodeForNumber() {
    final String number = "+18005551234";

    when(verificationCodeStore.findForUserLogin(number)).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), storedVerificationCodeManager.getCodeForUserLogin(number));

    final StoredVerificationCode storedVerificationCode = mock(StoredVerificationCode.class);

    when(verificationCodeStore.findForUserLogin(number)).thenReturn(Optional.of(storedVerificationCode));
    assertEquals(Optional.of(storedVerificationCode), storedVerificationCodeManager.getCodeForUserLogin(number));
  }
}
