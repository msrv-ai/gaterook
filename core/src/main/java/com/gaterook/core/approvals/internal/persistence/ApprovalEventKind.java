package com.gaterook.core.approvals.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code approval_event.kind}: one entry of the request's timeline. */
public enum ApprovalEventKind {
  REQUESTED, NOTIFIED, DELIVERED, DECIDED, RUNNING, COMPLETED, FAILED, EXPIRED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<ApprovalEventKind> {
    public Converter() {
      super(values());
    }
  }
}
