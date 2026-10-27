package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors of {@code gcp_describe} and {@code gcp_metrics}: {@code gc1.} and a handle the core seals with its HMAC
 * ({@code ExecContext.handles()}), so an agent can neither forge one nor move it to another connection or query. Inside
 * are the connection, a hash of the query and Google's page token (plus, for metrics, the interval of the first page:
 * a page token only continues the query it came from). Google's page tokens live 24 h; so does the cursor.
 */
final class Cursors {

  static final String PREFIX = "gc1.";
  static final Duration TTL = Duration.ofHours(24);

  private Cursors() {}

  static String seal(ExecContext ctx, String purpose, String connectionId, String hash, String pageToken,
      ObjectNode extra) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("c", connectionId);
    n.put("h", hash);
    n.put("t", pageToken);
    if (extra != null) {
      n.set("x", extra);
    }
    return PREFIX + ctx.handles().seal(purpose, n, TTL);
  }

  /** The cursor's contents: {@code t} the page token, {@code x} what the tool stored with it. */
  static JsonNode open(ExecContext ctx, String purpose, String cursor, String connectionId, String hash)
      throws ConnectorException {
    if (!cursor.startsWith(PREFIX)) {
      throw ConnectorException.badInput("This cursor doesn't come from " + ("gcp.metrics".equals(purpose)
          ? "gcp_metrics" : "gcp_describe") + ". Pass nextCursor of the previous answer, or leave cursor out.");
    }
    Optional<JsonNode> body = ctx.handles().open(purpose, cursor.substring(PREFIX.length()));
    if (body.isEmpty()) {
      throw ConnectorException.badInput("The cursor is invalid or older than 24 hours. Start again without cursor.");
    }
    JsonNode n = body.get();
    if (!connectionId.equals(n.path("c").asString(""))) {
      throw ConnectorException.badInput("The cursor belongs to another connection. Start again without cursor.");
    }
    if (!hash.equals(n.path("h").asString(""))) {
      throw ConnectorException.badInput("The cursor belongs to another query: repeat the same arguments with it, or "
          + "start again without cursor.");
    }
    return n;
  }
}
