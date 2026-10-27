/**
 * Connections module (ARCHITECTURE.md §2.2). Connection CRUD (duplicate, edit), Test connection and test-all, test
 * confirmations (host key, TLS certificate), the health scheduler, connection metadata and "Used by".
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Connections", allowedDependencies = {
        "platform", "settings", "connectors", "secrets"})
package ai.msrv.gaterook.core.connections;

import org.springframework.modulith.ApplicationModule;
