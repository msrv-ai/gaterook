package com.gaterook.core.secrets.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/**
 * {@code secret_version.state}. A rotation writes {@code pending}, which becomes {@code current} after the test;
 * the old one stays {@code previous} for 7 days (roll back) and is then {@code destroyed} (crypto-shredded).
 */
public enum SecretVersionState {
  PENDING, CURRENT, PREVIOUS, DESTROYED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<SecretVersionState> {
    public Converter() {
      super(values());
    }
  }
}
