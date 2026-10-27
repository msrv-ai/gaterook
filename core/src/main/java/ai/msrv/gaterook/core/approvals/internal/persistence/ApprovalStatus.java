package ai.msrv.gaterook.core.approvals.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/**
 * {@code approval.status}. {@code pending} → {@code running} (approved, the stored call executes) →
 * {@code completed}|{@code failed}; {@code pending} → {@code rejected}|{@code expired}|{@code cancelled}. Every
 * transition is a versioned compare-and-set ({@link ApprovalRepository#transition}).
 */
public enum ApprovalStatus {
  PENDING, RUNNING, COMPLETED, REJECTED, EXPIRED, FAILED, CANCELLED;

  public boolean isFinal() {
    return this != PENDING && this != RUNNING;
  }

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<ApprovalStatus> {
    public Converter() {
      super(values());
    }
  }
}
