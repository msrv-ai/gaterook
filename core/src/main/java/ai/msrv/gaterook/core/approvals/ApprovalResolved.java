package ai.msrv.gaterook.core.approvals;

/**
 * An approval request changed status for good or started running (published in the transaction of the change):
 * {@code running} after Approve, then {@code completed} or {@code failed}; {@code rejected}, {@code expired},
 * {@code cancelled}. Channels update their messages on it.
 *
 * @param decidedVia who decided ({@code app}, {@code slack}, {@code core}, {@code timeout}, …)
 */
public record ApprovalResolved(String approvalId, String status, String decidedVia) {}
