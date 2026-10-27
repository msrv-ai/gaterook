package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.Env;
import java.util.List;
import java.util.Optional;

/**
 * SPI: where a connection is used. Implemented by {@code endpoints}, which depends on this module, so that "Used by",
 * the Secrets screen and edits of a connection's environment can know the endpoints without a cycle.
 */
public interface ConnectionUsage {

  /** One tool × endpoint pair of a connection, as "Used by" lists it. */
  record UsedBy(String tool, String endpoint, String mode) {}

  /** The pairs of {@code connectionId} on every endpoint, ordered by endpoint then tool. */
  List<UsedBy> usedBy(String connectionId);

  /** The slugs of the endpoints that wire the connection in. */
  List<String> endpoints(String connectionId);

  /**
   * Why the connection can't move to {@code env} (a prod connection only on prod endpoints, and the reverse), or
   * empty when it can.
   */
  Optional<String> envChangeProblem(String connectionId, Env env);
}
