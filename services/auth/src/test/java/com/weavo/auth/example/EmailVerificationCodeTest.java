package com.weavo.auth.example;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailVerificationCodeTest {
  @Test
  void acceptsSixDigitsOnly() {
    assertTrue(EmailVerificationCode.hasValidFormat("123456"));
    assertFalse(EmailVerificationCode.hasValidFormat("12a456"));
    assertFalse(EmailVerificationCode.hasValidFormat(null));
  }
}
