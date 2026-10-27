/**
 * Approvals module (ARCHITECTURE.md §2.2). approval and approval_event (insert-only feed), requestOrReuse (dedup),
 * decide, timeouts, ApprovalExecutor (re-checks the policy and runs the stored call through invocation), results
 * kept 60 min, the events ApprovalRequested and ApprovalResolved, ActionInspector.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Approvals", allowedDependencies = {
        "platform", "settings", "connectors", "connections", "endpoints", "policy", "invocation", "audit"})
package ai.msrv.gaterook.core.approvals;

import org.springframework.modulith.ApplicationModule;
