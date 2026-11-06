package com.weavo.auth.example;

import java.util.regex.Pattern;

// 예시: 인증번호 형식만 확인합니다. 발급·만료·인증 처리는 구현하지 않습니다.
public final class EmailVerificationCode {
  private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{6}");

  private EmailVerificationCode() {}

  public static boolean hasValidFormat(String code) {
    return code != null && SIX_DIGITS.matcher(code).matches();
  }
}
