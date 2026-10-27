/**
 * OAuth module (ARCHITECTURE.md §2.2). The tunnel and local authorization servers, CimdResolver, DCR,
 * ConsentService, the {@code oauth_*} tables and OAuthAuthenticator (an Authenticator).
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "OAuth", allowedDependencies = {"platform", "settings", "endpoints", "tunnel"})
package ai.msrv.gaterook.core.oauth;

import org.springframework.modulith.ApplicationModule;
