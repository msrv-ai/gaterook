/**
 * The plugin subsystem of the connectors module (ARCHITECTURE.md §7.5): installation and the Ed25519 catalog, the
 * {@code PluginSupervisor} and {@code HostLauncher}, {@code RemoteConnector}, {@code SecretCallbackRegistry} and
 * {@code AotCacheManager}. Internal to {@code connectors}; the admin API drives it through the module's public
 * {@code ai.msrv.gaterook.core.connectors.Plugins} facade, and the registry reaches it through the
 * {@code ConnectorSource}/{@code ConnectorProcessStatus} SPIs.
 */
package ai.msrv.gaterook.core.connectors.plugins;
