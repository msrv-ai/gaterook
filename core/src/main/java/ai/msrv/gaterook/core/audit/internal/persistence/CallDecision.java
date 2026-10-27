package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code tool_call.decision}, the Decision facet of Activity. */
public enum CallDecision {
  ALLOWED, APPROVED, PENDING, DENIED, REJECTED, EXPIRED, ERROR;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<CallDecision> {
    public Converter() {
      super(values());
    }
  }
}
