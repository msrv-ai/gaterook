package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors of {@code aws_describe} and {@code aws_metrics}: {@code ac1.} and a handle the core seals with its HMAC
 * ({@code ExecContext.handles()}), so an agent can neither forge one nor move it to another connection or query.
 * Inside are the connection, a hash of the query and where the next page starts: AWS's token of the page and how
 * many of its items the agent already got ({@code ListMetrics} has no page size, so a page may hold more than the
 * agent asked for), plus, for metrics, the interval of the first page. AWS's tokens are short-lived; a day is plenty.
 */
final class Cursors {

  static final String PREFIX = "ac1.";
  static final Duration TTL = Duration.ofHours(24);

  /** Where a listing goes on: the token of a page ({@code null}: the first) and the items of it already shown. */
  record Position(String token, int skip, int pageSize) {}

  private Cursors() {}

  static String seal(ExecContext ctx, String purpose, String connectionId, String hash, Position at,
      ObjectNode extra) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("c", connectionId);
    n.put("h", hash);
    if (at.token() != null) {
      n.put("t", at.token());
    }
    if (at.skip() > 0) {
      n.put("s", at.skip());
    }
    if (at.pageSize() > 0) {
      n.put("m", at.pageSize());
    }
    if (extra != null) {
      n.set("x", extra);
    }
    return PREFIX + ctx.handles().seal(purpose, n, TTL);
  }

  /** The cursor's contents, checked against the connection and the query. */
  static JsonNode open(ExecContext ctx, String purpose, String cursor, String connectionId, String hash)
      throws ConnectorException {
    String tool = "aws.metrics".equals(purpose) ? "aws_metrics" : "aws_describe";
    if (!cursor.startsWith(PREFIX)) {
      throw ConnectorException.badInput("This cursor doesn't come from " + tool + ". Pass nextCursor of the "
          + "previous answer, or leave cursor out.");
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

  static Position position(JsonNode opened) {
    return new Position(opened.path("t").isString() ? opened.path("t").asString() : null,
        Math.max(0, opened.path("s").asInt(0)), Math.max(0, opened.path("m").asInt(0)));
  }
}
