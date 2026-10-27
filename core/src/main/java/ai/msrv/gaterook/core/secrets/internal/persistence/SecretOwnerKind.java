package ai.msrv.gaterook.core.secrets.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

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
