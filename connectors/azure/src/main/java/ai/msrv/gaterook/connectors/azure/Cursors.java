package ai.msrv.gaterook.connectors.azure;

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
 * Cursors of {@code azure_describe} and {@code azure_monitor}: {@code az1.} and a handle the core seals with its HMAC
 * ({@code ExecContext.handles()}), so an agent can neither forge one nor move it to another connection or query.
 * Inside are the connection, a hash of the query and Azure's continuation (a Resource Graph {@code $skipToken}, the
 * {@code $skipToken} of a {@code nextLink}). Purposes match the core's {@code [a-z][a-z0-9.-]{0,47}}.
 */
final class Cursors {

  static final String PREFIX = "az1.";
  static final Duration TTL = Duration.ofHours(12);

  private Cursors() {}

  static String seal(ExecContext ctx, String purpose, String connectionId, String hash, String token) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("c", connectionId);
    n.put("h", hash);
    n.put("t", token);
    return PREFIX + ctx.handles().seal(purpose, n, TTL);
  }

  /** Azure's continuation inside {@code cursor}, checked against the connection and the query. */
  static String open(ExecContext ctx, String purpose, String tool, String cursor, String connectionId, String hash)
      throws ConnectorException {
    if (!cursor.startsWith(PREFIX)) {
      throw ConnectorException.badInput("This cursor doesn't come from " + tool + ". Pass nextCursor of the "
          + "previous answer, or leave cursor out.");
    }
    Optional<JsonNode> body = ctx.handles().open(purpose, cursor.substring(PREFIX.length()));
    if (body.isEmpty()) {
      throw ConnectorException.badInput("The cursor is invalid or older than 12 hours. Start again without cursor.");
    }
    JsonNode n = body.get();
    if (!connectionId.equals(n.path("c").asString(""))) {
      throw ConnectorException.badInput("The cursor belongs to another connection. Start again without cursor.");
    }
    if (!hash.equals(n.path("h").asString(""))) {
      throw ConnectorException.badInput("The cursor belongs to another query: repeat the same arguments with it, or "
          + "start again without cursor.");
    }
    return n.path("t").asString("");
  }

  /** A short SHA-256 of the parts that make a query the same query. */
  static String hash(String... parts) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      for (String p : parts) {
        d.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
        d.update((byte) 0);
      }
      return HexFormat.of().formatHex(d.digest(), 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
