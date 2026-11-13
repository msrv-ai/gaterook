package com.gaterook.core.secrets.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code external_store.kind}: {@code vault}, {@code gcp-sm}, {@code aws-sm}, {@code azure-kv}. */
public enum StoreKind {
  VAULT, GCP_SM, AWS_SM, AZURE_KV;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<StoreKind> {
    public Converter() {
      super(values());
    }
  }
}
