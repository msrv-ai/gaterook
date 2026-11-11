package ai.msrv.gaterook.connectors.s3;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors of the S3 listings: {@code s31.} and a handle the core seals ({@code ExecContext.handles()}), so an agent
 * can neither forge one nor move it to another connection, bucket or prefix. Inside are the connection, a hash of the
 * request without its page, and where the listing goes on: S3's continuation token and the last name shown.
 */
final class Cursors {

  static final String PREFIX = "s31.";
  static final Duration TTL = Duration.ofHours(24);

  private Cursors() {}

  /** {@code s3.<tool>} with dashes: the core's sealed handles take lower-case letters, digits, dots and dashes. */
  static String purpose(String tool) {
    return "s3." + tool.replace('_', '-');
  }

  static String seal(ExecContext ctx, String tool, String connectionId, String hash, JsonNode position) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("c", connectionId);
    n.put("h", hash);
    n.set("p", position);
    return PREFIX + ctx.handles().seal(purpose(tool), n, TTL);
  }

  /** Where the page of {@code cursor} starts, checked against the connection and the request. */
  static JsonNode open(ExecContext ctx, String tool, String cursor, String connectionId, String hash)
      throws ConnectorException {
    if (!cursor.startsWith(PREFIX)) {
      throw ConnectorException.badInput("This cursor doesn't come from " + tool + ". Pass nextCursor of the "
          + "previous answer, or leave cursor out.");
    }
    Optional<JsonNode> body = ctx.handles().open(purpose(tool), cursor.substring(PREFIX.length()));
    if (body.isEmpty()) {
      throw ConnectorException.badInput("The cursor is invalid or older than 24 hours. Start again without cursor.");
    }
    JsonNode n = body.get();
    if (!connectionId.equals(n.path("c").asString(null)) || !hash.equals(n.path("h").asString(null))) {
      throw ConnectorException.badInput("This cursor belongs to another connection or request. Send the request "
          + "unchanged with its cursor, or leave cursor out.");
    }
    return n.path("p");
  }

  /** A short hash of what a listing asks, its page left out. */
  static String hash(JsonNode request) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.toString()
          .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
