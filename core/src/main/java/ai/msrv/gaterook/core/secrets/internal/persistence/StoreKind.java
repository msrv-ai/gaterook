package ai.msrv.gaterook.core.secrets.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code external_store.kind}: {@code vault}, {@code gcp-sm}, {@code aws-sm}. */
public enum StoreKind {
  VAULT, GCP_SM, AWS_SM;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<StoreKind> {
    public Converter() {
      super(values());
    }
  }
}
