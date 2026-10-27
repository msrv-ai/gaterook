package ai.msrv.gaterook.core.templates.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code template.status}; {@code invalid} after its Runs on connection was deleted or it stopped compiling. */
public enum TemplateStatus {
  ACTIVE, DISABLED, INVALID;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<TemplateStatus> {
    public Converter() {
      super(values());
    }
  }
}
