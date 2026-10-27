/**
 * Templates module (ARCHITECTURE.md §2.2). TemplateService: storage, append-only versions, compilation through
 * TemplateKind against the Runs on connection, import from the catalog.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Templates", allowedDependencies = {"platform", "connectors", "connections"})
package ai.msrv.gaterook.core.templates;

import org.springframework.modulith.ApplicationModule;
