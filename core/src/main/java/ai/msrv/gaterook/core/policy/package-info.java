/**
 * Policies module (ARCHITECTURE.md §2.2). Environment policies, merging where only stricter wins (x-stricter),
 * PolicyView, PolicyEngine.decide(PairView, …) with PairView as this module's own record, limits and the invariant
 * locks.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Policies", allowedDependencies = {"platform", "settings", "connectors"})
package ai.msrv.gaterook.core.policy;

import org.springframework.modulith.ApplicationModule;
