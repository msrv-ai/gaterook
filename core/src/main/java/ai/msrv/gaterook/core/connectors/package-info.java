/**
 * Connectors module (ARCHITECTURE.md §2.2). ConnectorRegistry (built-in Connector beans plus every ConnectorSource),
 * table connector_type and the ConnectorSource and ConnectorProcessStatus SPIs. Subpackage plugins: installation,
 * the Ed25519 catalog, PluginSupervisor, HostLauncher, AotCacheManager, RemoteConnector and SecretCallbackRegistry,
 * a ConnectorSource.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Connectors", allowedDependencies = {"platform", "settings"})
package ai.msrv.gaterook.core.connectors;

import org.springframework.modulith.ApplicationModule;
