/**
 * Admin API module (ARCHITECTURE.md §2.2). The REST API of the UI ({@code /api/**}) in subpackages {@code
 * admin.<area>}, DTO records with @RegisterReflectionForBinding on their controller, {@code /api/events} and
 * ApiExceptionHandler. Uses only the public API of the other modules.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Admin API", allowedDependencies = {
        "platform", "settings", "secrets", "audit", "connectors", "connections", "templates", "mcpservers",
        "policy", "endpoints", "invocation", "approvals", "mcp", "playground", "clients", "tunnel", "oauth",
        "notifications", "insights"})
package ai.msrv.gaterook.core.admin;

import org.springframework.modulith.ApplicationModule;
