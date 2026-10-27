/**
 * Endpoints module (ARCHITECTURE.md §2.2). Endpoints, endpoint_connection, endpoint_tool (modes, Read only
 * confirmations), api_token with TokenAuthenticator (an Authenticator), grants, EndpointSnapshot (builds PairView),
 * ToolCatalog, RiskAnalyzer and split.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Endpoints", allowedDependencies = {
        "platform", "settings", "connectors", "connections", "templates", "policy"})
package ai.msrv.gaterook.core.endpoints;

import org.springframework.modulith.ApplicationModule;
