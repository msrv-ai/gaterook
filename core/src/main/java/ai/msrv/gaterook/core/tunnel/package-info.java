/**
 * Tunnel module (ARCHITECTURE.md §2.2). TunnelProvider (cloudflared quick or named, ngrok), TunnelBinaries,
 * PublicListenerConfig (7466), PublicIngressFilter, AnthropicRanges and the self-test; writes
 * platform.PublicHostname.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Tunnel", allowedDependencies = {"platform", "settings", "secrets", "endpoints"})
package ai.msrv.gaterook.core.tunnel;

import org.springframework.modulith.ApplicationModule;
