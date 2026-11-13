package com.gaterook.core.approvals.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code approval.comment_policy}, from {@code policy.sections.approvals.comment}; prod defaults to required. */
public enum CommentPolicy {
  OPTIONAL, RECOMMENDED, REQUIRED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<CommentPolicy> {
    public Converter() {
      super(values());
    }
  }
}
