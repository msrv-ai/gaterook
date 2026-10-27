package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code tool_call.approval_delivery}: how the outcome of an approval reached the agent (A3). */
public enum ApprovalDeliveryMode {
  MRTR, HANDLE, TASK;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<ApprovalDeliveryMode> {
    public Converter() {
      super(values());
    }
  }
}
