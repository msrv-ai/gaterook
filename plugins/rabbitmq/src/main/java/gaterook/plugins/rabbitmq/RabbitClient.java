package gaterook.plugins.rabbitmq;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The management API calls of one tool call: through the host's guarded client ({@code ctx.egress()}: only the
 * connection's host on port 15672, no redirects, no address the policy closes), JSON both ways, Basic credentials.
 *
 * <p>The password is asked of the core only when the first request goes out; the Basic value derived from it is
 * reported with {@code ctx.taint}, so an echo of it is masked like the password itself.
 */
final class RabbitClient {

  /** What one request answered; {@code json} is {@code null} for an empty or non-JSON body. */
  record Response(int status, JsonNode json, String text) {

    boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  /** Answers are read up to this size; a larger one is cut and refused. */
  static final long MAX_BYTES = 8L * 1024 * 1024;

  private final RabbitConfig config;
  private final SecretResolver secrets;
  private final ExecContext ctx;
  private String authorization;

  RabbitClient(RabbitConfig config, SecretResolver secrets, ExecContext ctx) {
    this.config = config;
    this.secrets = secrets;
    this.ctx = ctx;
  }

  static RabbitClient of(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    return new RabbitClient(RabbitConfig.of(cfg), secrets, ctx);
  }

  RabbitConfig config() {
    return config;
  }

  Response get(String pathAndQuery) throws ConnectorException {
    return send("GET", pathAndQuery, null);
  }

  /**
   * A POST that changes the broker. A failure after it may have reached RabbitMQ says so ({@code OUTCOME_UNKNOWN}):
   * the core never sends it again on its own.
   */
  Response post(String path, JsonNode body) throws ConnectorException {
    try {
      return send("POST", path, Manifests.JSON.writeValueAsBytes(body));
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE || e.kind() == ConnectorException.Kind.TIMEOUT) {
        throw ConnectorException.outcomeUnknown("The request to " + config.host() + " broke off: RabbitMQ may have "
            + "taken the message. Check the queue before you try again.", e);
      }
      throw e;
    }
  }

  private Response send(String method, String pathAndQuery, byte[] body) throws ConnectorException {
    URI uri = config.uri(pathAndQuery);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Accept", List.of("application/json"));
    headers.put("Authorization", List.of(authorization()));
    if (body != null) {
      headers.put("Content-Type", List.of("application/json"));
    }
    ExecContext.EgressResponse r = ctx.egress().send(new ExecContext.EgressRequest(method, uri, headers, body,
        timeout()), MAX_BYTES);
    if (r.truncated()) {
      throw ConnectorException.resourceError("RabbitMQ's answer to " + method + " " + path(pathAndQuery)
          + " is larger than 8 MB.", null);
    }
    String text = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
    JsonNode json = null;
    if (!text.isBlank()) {
      try {
        json = Manifests.JSON.readTree(text);
      } catch (JacksonException e) {
        // A proxy's HTML page: the status says what happened.
      }
    }
    return new Response(r.status(), json, text);
  }

  /** What remains of the call, at most the connection's timeout, and a little left over to answer in. */
  private Duration timeout() {
    Duration left = ctx.remaining().minusMillis(200);
    Duration configured = Duration.ofMillis(config.timeoutMs());
    if (left.isNegative() || left.isZero()) {
      return Duration.ofMillis(100);
    }
    return left.compareTo(configured) < 0 ? left : configured;
  }

  private String authorization() throws ConnectorException {
    if (authorization != null) {
      return authorization;
    }
    try (SecretResolver.Secret password = secrets.secret(RabbitConfig.PASSWORD)) {
      authorization = "Basic " + basic(config.user(), password.chars());
      // Derived from the password: the core masks an echo of it like the password itself.
      ctx.taint(authorization.substring("Basic ".length()));
      return authorization;
    }
  }

  /** Base64 of {@code user:password} without a {@code String} of the password on the way. */
  private static String basic(String user, char[] password) {
    char[] joined = new char[user.length() + 1 + password.length];
    user.getChars(0, user.length(), joined, 0);
    joined[user.length()] = ':';
    System.arraycopy(password, 0, joined, user.length() + 1, password.length);
    ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(joined));
    byte[] bytes = new byte[utf8.remaining()];
    utf8.get(bytes);
    try {
      return Base64.getEncoder().encodeToString(bytes);
    } finally {
      Arrays.fill(joined, '\0');
      Arrays.fill(bytes, (byte) 0);
      if (utf8.hasArray()) {
        Arrays.fill(utf8.array(), (byte) 0);
      }
    }
  }

  private static String path(String pathAndQuery) {
    int q = pathAndQuery.indexOf('?');
    return q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
  }
}
