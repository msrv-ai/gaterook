/**
 * The plugin host process (A12, report 16 §9): {@code Main} (stdout reserved for GPP/1, {@code System.out} redirected
 * to stderr, exit on stdin EOF), {@code HostDispatcher}, {@code PluginRegistry}, {@code GatewayPluginManager} on
 * PF4J, {@code RemoteSecretResolver} (secrets lazily by call token), {@code EgressHttpClient},
 * {@code HostSlf4jProvider}, {@code SandboxExec} and {@code Sandbox} (the Linux first stage that confines the host
 * with Landlock through FFM before its JVM starts) and {@code Train} (AOT cache).
 * Implemented by step K4.
 */
package ai.msrv.gaterook.pluginhost;
