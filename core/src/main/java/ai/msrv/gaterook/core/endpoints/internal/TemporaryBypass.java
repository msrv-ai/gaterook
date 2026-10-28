package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/** Signed grants are limited to the reviewed definition, configuration and endpoint settings. */
public final class TemporaryBypass {
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
      return now.isBefore(until) ? until : null;
    } catch (DateTimeParseException | ClassCastException | NullPointerException ignored) {
      return null;
    }
  }

  private static boolean sameVersion(Object value, long version) {
    return Long.toString(version).equals(value);
  }
}
