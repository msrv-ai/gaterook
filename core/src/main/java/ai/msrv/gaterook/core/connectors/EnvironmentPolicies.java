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

  /**
   * A connection was renamed: entries of each environment's policy that name {@code from}
   * ({@code http.allowedPrivateRanges[].connections}) name {@code to} instead, so what they allow stays with the
   * connection and never passes to another one that takes the old name later. Joins the caller's transaction; each
   * environment it changes records a Setup change.
   *
   * @throws ai.msrv.gaterook.core.platform.ProblemException 409 when a policy that names {@code from} was changed
   *     outside GateRook: it can't be signed again before the person reviews it
   */
  void renameConnection(String from, String to, String reason);
}
