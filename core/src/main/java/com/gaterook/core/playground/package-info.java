/**
 * Playground module (ARCHITECTURE.md §2.2). The playground principal, calls through McpDispatcher in the chosen
 * protocol revision, history.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Playground", allowedDependencies = {"platform", "mcp", "endpoints", "audit"})
package com.gaterook.core.playground;

import org.springframework.modulith.ApplicationModule;
