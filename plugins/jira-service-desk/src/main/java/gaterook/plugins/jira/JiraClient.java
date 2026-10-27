package gaterook.plugins.jira;

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
 * The REST calls of one tool call (Jira Service Management Cloud and Data Center): through the host's guarded client
 * ({@code ctx.egress()}: only the connection's own host, no redirects, no metadata or private addresses the policy
 * didn't allow), JSON both ways, the call's remaining budget as the timeout.
 *
 * <p>Credentials: Cloud signs with Basic {@code email:apiToken}, Data Center with the personal access token as a
 * Bearer. The token is asked of the core only when the first request goes out and lives in a {@code String} only as
 * long as the header needs it (an HTTP header can't be zeroed); the Basic value derived from it is reported to the core
 * with {@code ctx.taint}, so an echo of it is masked like the token itself.
 */
final class JiraClient {

  /** What one request answered. {@code json} is {@code null} for an empty or non-JSON body. */
  record Response(int status, JsonNode json, String text) {

    boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  /** Jira's answers are read up to this size; a larger one is cut and refused. */
  static final long MAX_BYTES = 4L * 1024 * 1024;

  private final JiraConfig config;
  private final SecretResolver secrets;
  private final ExecContext ctx;
  private String authorization;

  JiraClient(JiraConfig config, SecretResolver secrets, ExecContext ctx) {
    this.config = config;
    this.secrets = secrets;
    this.ctx = ctx;
  }

  static JiraClient of(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    return new JiraClient(JiraConfig.of(cfg), secrets, ctx);
  }

  JiraConfig config() {
    return config;
  }

  Response get(String pathAndQuery) throws ConnectorException {
    return send("GET", pathAndQuery, null);
  }

  /**
   * A POST that changes data. A failure after it may have reached Jira says so ({@code OUTCOME_UNKNOWN}): the core
   * never sends it again on its own.
   */
  Response post(String path, JsonNode body) throws ConnectorException {
    try {
      return send("POST", path, Manifests.JSON.writeValueAsBytes(body));
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE || e.kind() == ConnectorException.Kind.TIMEOUT) {
        throw ConnectorException.outcomeUnknown("The request to " + config.host() + " broke off: Jira may have done "
            + "it. Check before you try again.", e);
      }
      throw e;
    }
  }

  private Response send(String method, String pathAndQuery, byte[] body) throws ConnectorException {
    URI uri = config.uri(pathAndQuery);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Accept", List.of("application/json"));
    headers.put("Authorization", List.of(authorization()));
    // Several Service Management resources (queues, SLAs) are still marked experimental in Cloud.
    headers.put("X-ExperimentalApi", List.of("opt-in"));
    if (body != null) {
      headers.put("Content-Type", List.of("application/json"));
    }
    ExecContext.EgressResponse r = ctx.egress().send(new ExecContext.EgressRequest(method, uri, headers, body,
        timeout()), MAX_BYTES);
    if (r.truncated()) {
      throw ConnectorException.resourceError("Jira's answer to " + method + " " + path(pathAndQuery)
          + " is larger than 4 MB.", null);
    }
    String text = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
    JsonNode json = null;
    if (!text.isBlank()) {
      try {
        json = Manifests.JSON.readTree(text);
      } catch (JacksonException e) {
        // An HTML error page of a proxy, a plain-text 404: the status says what happened.
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
    try (SecretResolver.Secret token = secrets.secret(JiraConfig.TOKEN)) {
      if (config.cloud()) {
        if (config.email() == null) {
          throw ConnectorException.badInput("Jira Cloud signs in with the email of the token's account: add it to "
              + "the connection.");
        }
        authorization = "Basic " + basic(config.email(), token.chars());
        // Derived from the token: the core masks an echo of it like the token itself.
        ctx.taint(authorization.substring("Basic ".length()));
      } else {
        authorization = "Bearer " + token.unsafeString();
      }
      return authorization;
    }
  }

  /** Base64 of {@code email:token} without a {@code String} of the token on the way. */
  private static String basic(String email, char[] token) {
    char[] joined = new char[email.length() + 1 + token.length];
    email.getChars(0, email.length(), joined, 0);
    joined[email.length()] = ':';
    System.arraycopy(token, 0, joined, email.length() + 1, token.length);
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
