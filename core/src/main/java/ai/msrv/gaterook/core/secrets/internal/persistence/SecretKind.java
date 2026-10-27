package ai.msrv.gaterook.core.secrets.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/**
 * {@code secret.kind}: a value encrypted here ({@code local}), a reference into an external store
 * ({@code reference}) or credentials the store issues per lease ({@code dynamic}, Vault).
 */
public enum SecretKind {
  LOCAL, REFERENCE, DYNAMIC;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<SecretKind> {
    public Converter() {
      super(values());
    }
  }
}
