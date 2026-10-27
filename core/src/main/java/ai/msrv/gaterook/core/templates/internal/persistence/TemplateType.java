package ai.msrv.gaterook.core.templates.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code template.kind}: the {@code TemplateKind.kind()} that compiles the template. */
public enum TemplateType {
  SQL_QUERY, SSH_RUNBOOK, OPENAPI;

  /** The kind id as {@code TemplateKind.kind()} spells it ({@code sql-query}). */
  public String kindId() {
    return KebabCaseEnumConverter.idOf(this);
  }

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<TemplateType> {
    public Converter() {
      super(values());
    }
  }
}
