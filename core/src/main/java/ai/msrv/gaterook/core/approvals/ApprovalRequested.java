package ai.msrv.gaterook.core.approvals;

import ai.msrv.gaterook.connector.api.Env;
import java.time.Instant;

/**
 * A new approval request waits for a person (published in the transaction that created it; listeners use
 * {@code @TransactionalEventListener(AFTER_COMMIT)}). No agent note, by type: what goes to channels can't carry what an
 * agent wrote (§9 "Approve the exact action").
 */
public record ApprovalRequested(String approvalId, String endpointSlug, String connectionName, Env env, String tool,
    String summary, String commentPolicy, Instant expiresAt) {}
