/**
 * Insights module (ARCHITECTURE.md §2.2). Aggregates over tool_call, percentiles and the Unusual activity detectors.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Insights", allowedDependencies = {"platform", "audit", "endpoints", "connections"})
package ai.msrv.gaterook.core.insights;

import org.springframework.modulith.ApplicationModule;
