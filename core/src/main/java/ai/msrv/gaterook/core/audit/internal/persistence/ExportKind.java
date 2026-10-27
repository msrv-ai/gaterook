package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code audit_export.kind}: syslog over TLS to a SIEM, an S3 bucket or a BigQuery table. */
public enum ExportKind {
  SIEM, S3, BIGQUERY;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<ExportKind> {
    public Converter() {
      super(values());
    }
  }
}
