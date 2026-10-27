package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import java.util.List;

/**
 * SPI: what an environment's policy says before any connection, endpoint or tool narrows it. Implemented by the
 * {@code policy} module; declared here so that {@code connections} (tests and health checks) and the call contexts of
 * this module can use it: neither may depend on {@code policy}, which itself depends on this module.
 */
public interface EnvironmentPolicies {

  /** The environment's view of {@code manifest}'s policy section ({@code x-policy-section}), merged and with limits. */
  PolicyView view(Env env, ConnectorManifest manifest);

  /** The environment's limits (rows, lines, sizes, timeouts). */
  Limits limits(Env env);

  /**
   * Private address ranges the environment's HTTP egress section allows to {@code connectionName}
   * ({@code http.allowedPrivateRanges}: entries {@code {cidr, connections}}, an empty {@code connections} meaning
   * every connection of the environment), as CIDR text; everything private is refused otherwise.
   */
  List<String> allowedPrivateRanges(Env env, String connectionName);
}
