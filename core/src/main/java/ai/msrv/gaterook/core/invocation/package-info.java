/**
 * Invocation module (ARCHITECTURE.md §2.2). ConnectorInvoker, ExecContextImpl, ToolGuard (circuit breaker, bulkhead,
 * rate limit per principal) and ResultLimiter. Never transactional: no connection or transaction is held across
 * resource I/O.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Invocation", allowedDependencies = {
        "platform", "connectors", "connections", "policy", "secrets", "audit"})
package ai.msrv.gaterook.core.invocation;

import org.springframework.modulith.ApplicationModule;
