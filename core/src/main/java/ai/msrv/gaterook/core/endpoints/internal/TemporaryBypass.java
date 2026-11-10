package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * A temporary bypass: a signed grant that lets one tool of a connection run without approval until a deadline. It
 * applies only while the tool has the definition it was granted for ({@link ToolPairs#definitionHash}), what declared
 * the tool has the version it had, and the connection and the endpoint have the versions they had. That binding is
 * the grant's own and the whole rule, for an external MCP server's tool too: the row's reviewed definition binds Read
 * only and Bypass, not a grant, so a person may grant one to a tool held in Approval whose row records an earlier
 * definition or none (ARCHITECTURE.md §7.5). The snapshot applies a grant by this rule. A reconcile drops the grant of
 * a server's tool once its definition is another, and any grant of a tool whose mode it lowers, since the endpoint it
 * then saves has a new version.
 */
public final class TemporaryBypass {

  /** The longest temporary bypass the admin API grants: a deadline further away was not set by it. */
  public static final Duration MAX = Duration.ofHours(24);

  private TemporaryBypass() {}

  public static Instant activeUntil(EndpointToolEntity row, EndpointEntity endpoint, ConnectionConfig connection,
      ToolPairs.Offered offered, boolean readOnly, Instant now) {
    if (row == null || row.getTemporaryBypass() == null || readOnly || endpoint.getEnv() == Env.PROD
        || connection.env() == Env.PROD || row.getMode() == Mode.OFF || row.getMode() == Mode.BYPASS) {
      return null;
    }
    Map<String, Object> grant = row.getTemporaryBypass();
    if (!ToolPairs.definitionHash(offered.definition()).equals(grant.get("definitionHash"))
        || !Objects.equals(offered.provenanceVersion(), grant.get("provenanceVersion"))
        || !sameVersion(grant.get("connectionVersion"), connection.version())
        || !sameVersion(grant.get("endpointVersion"), endpoint.getVersion())) {
      return null;
    }
    try {
      Instant until = Instant.parse((String) grant.get("until"));
      return now.isBefore(until) && !until.isAfter(now.plus(MAX)) ? until : null;
    } catch (DateTimeParseException | ClassCastException | NullPointerException ignored) {
      return null;
    }
  }

  private static boolean sameVersion(Object value, long version) {
    return Long.toString(version).equals(value);
  }
}
