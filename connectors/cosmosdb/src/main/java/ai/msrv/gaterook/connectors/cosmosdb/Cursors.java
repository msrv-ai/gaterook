package ai.msrv.gaterook.connectors.cosmosdb;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors of the Cosmos DB reads: {@code cdb1.} and a handle the core seals ({@code ExecContext.handles()}), so an
 * agent can neither forge one nor move it to another connection or request. Inside are the connection, a hash of the
 * request without its page, and Cosmos DB's continuation token ({@code x-ms-continuation}), which is opaque and only
 * ever goes back to the request it came from.
 */
final class Cursors {

  static final String PREFIX = "cdb1.";
  static final Duration TTL = Duration.ofHours(24);
  /** The longest cursor the tools' input schemas take. */
  static final int MAX_LENGTH = 16_384;
  /** A continuation token goes back into a header: printable ASCII only. */
  private static final Pattern TOKEN = Pattern.compile("[\\x20-\\x7E]{1,12000}");

  private Cursors() {}

  /**
   * The handle's purpose for a tool's cursors: {@code cosmosdb.<tool>} with dashes, as the core's sealed handles take
   * lower-case letters, digits, dots and dashes only.
   */
  static String purpose(String tool) {
    return "cosmosdb." + tool.replace('_', '-');
  }

  /** The sealed cursor, or {@code null} when the token can't go into one (too long, or not ASCII). */
  static String seal(ExecContext ctx, String tool, String connectionId, String hash, String continuation) {
    if (!TOKEN.matcher(continuation).matches()) {
      return null;
    }
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("c", connectionId);
    n.put("h", hash);
    n.put("p", continuation);
    String sealed = PREFIX + ctx.handles().seal(purpose(tool), n, TTL);
    return sealed.length() > MAX_LENGTH ? null : sealed;
  }

  /** The continuation token of {@code cursor}, checked against the connection and the request. */
  static String open(ExecContext ctx, String tool, String cursor, String connectionId, String hash)
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
    String token = n.path("p").asString("");
    if (!TOKEN.matcher(token).matches()) {
      throw ConnectorException.badInput("The cursor is invalid. Start again without cursor.");
    }
    return token;
  }

  /** A short hash of what a request asks, its page left out. */
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
