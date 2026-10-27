package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/**
 * {@code tool_call.phase}: {@code received} (with the decision, before execute: fail-closed), {@code resolved} (the
 * approval was decided) and {@code completed} (the result, before it goes to the agent).
 */
public enum CallPhase {
  RECEIVED, RESOLVED, COMPLETED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<CallPhase> {
    public Converter() {
      super(values());
    }
  }
}
