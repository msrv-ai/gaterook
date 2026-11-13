/**
 * Clients module (ARCHITECTURE.md §2.2). Desktop client detection and setup, JsonEntryEditor, TomlBlockEditor,
 * ConfigFileWriter, DiffService, McpbBuilder, BridgeShimInstaller, ClientDriftWatcher, client_link and {@code
 * /internal/bridge/tokens}.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Clients", allowedDependencies = {"platform", "settings", "endpoints"})
package com.gaterook.core.clients;

import org.springframework.modulith.ApplicationModule;
