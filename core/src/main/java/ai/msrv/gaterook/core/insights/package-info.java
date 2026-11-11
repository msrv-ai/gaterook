/**
 * Insights module (ARCHITECTURE.md §2.2). Aggregates over tool_call, percentiles, the Unusual activity detectors, and the
 * tools agents call most on each endpoint, which its searched tool list shows ({@link PopularTools}, §5.7).
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Insights", allowedDependencies = {"platform", "audit", "endpoints", "connections"})
package ai.msrv.gaterook.core.insights;

import org.springframework.modulith.ApplicationModule;
