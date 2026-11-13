package com.gaterook.core.secrets.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code secret.owner_kind}: whose config the secret field belongs to. */
public enum SecretOwnerKind {
  CONNECTION, STORE, MCP_SERVER, NOTIFICATION, TUNNEL, AUDIT_EXPORT;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<SecretOwnerKind> {
    public Converter() {
      super(values());
    }
  }
}
