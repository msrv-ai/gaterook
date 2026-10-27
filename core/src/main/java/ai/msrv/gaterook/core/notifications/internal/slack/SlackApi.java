package ai.msrv.gaterook.core.notifications.internal.slack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The nine Slack Web API methods the gateway uses (report 13 §7), on {@code java.net.http}: {@code auth.test},
 * {@code apps.connections.open} (app-level token), {@code chat.postMessage}, {@code chat.update},
 * {@code chat.postEphemeral}, {@code conversations.open}, {@code conversations.info}, {@code users.lookupByEmail},
 * {@code users.conversations}. Writes go as JSON, reads as a form, as the methods document. Slack answers a refusal
 * with HTTP 200 and {@code {"ok": false, "error": "invalid_auth"}}, and a rate limit with 429 and
 * {@code Retry-After}. The token is a header and never part of a message or an exception.
 */
public final class SlackApi {

  /** Methods that take a JSON body (the others a form). */
  private static final Set<String> JSON_METHODS = Set.of("chat.postMessage", "chat.update", "chat.postEphemeral",
      "conversations.open");

  /** What Slack refused, in its own words ({@code invalid_auth}, {@code not_in_channel}, {@code ratelimited}…). */
  public static final class SlackException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String error;
    private final String needed;
    private final long retryAfterSeconds;

    public SlackException(String error, String needed, long retryAfterSeconds) {
      super("Slack: " + error + (needed == null ? "" : " (needs " + needed + ")"));
      this.error = error;
      this.needed = needed;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public String error() {
      return error;
    }

    /** The scope a {@code missing_scope} names, or {@code null}. */
    public String needed() {
      return needed;
    }

    /** Seconds Slack asked to wait (429), else 0. */
    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }

    /** The token is wrong or gone: no retry helps. */
    public boolean authFailed() {
      return Set.of("invalid_auth", "not_authed", "account_inactive", "token_revoked", "token_expired",
          "not_allowed_token_type").contains(error);
    }
  }

  /** One answer: the body, and the scopes Slack says the token has ({@code x-oauth-scopes}), when it says. */
  public record Reply(JsonNode body, String scopes) {}

  private final HttpClient http;
  private final JsonMapper json;
  private final URI base;

  public SlackApi(HttpClient http, JsonMapper json, URI base) {
    this.http = http;
    this.json = json;
    this.base = base;
  }

  /**
   * Calls {@code method} with {@code token}.
   *
   * @throws SlackException Slack refused ({@code ok: false}, 429, another status)
   * @throws IOException    the network failed
   */
  public Reply call(String method, ObjectNode args, String token) throws SlackException, IOException,
      InterruptedException {
    if (token == null || token.isBlank()) {
      throw new SlackException("not_authed", null, 0);
    }
    HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(method)).timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token).header("Accept", "application/json");
    if (JSON_METHODS.contains(method)) {
      b.header("Content-Type", "application/json; charset=utf-8")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(args), StandardCharsets.UTF_8));
    } else {
      b.header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form(args), StandardCharsets.UTF_8));
    }
    HttpResponse<String> rs = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (rs.statusCode() == 429) {
      throw new SlackException("ratelimited", null, rs.headers().firstValueAsLong("Retry-After").orElse(5));
    }
    JsonNode body;
    try {
      body = json.readTree(rs.body());
    } catch (RuntimeException e) {
      throw new SlackException("http_" + rs.statusCode(), null, 0);
    }
    if (rs.statusCode() / 100 != 2 && !body.has("ok")) {
      throw new SlackException("http_" + rs.statusCode(), null, 0);
    }
    if (!body.path("ok").asBoolean(false)) {
      throw new SlackException(body.path("error").asString("unknown_error"), body.path("needed").asString(null), 0);
    }
    return new Reply(body, rs.headers().firstValue("x-oauth-scopes").orElse(null));
  }

  private static String form(ObjectNode args) {
    StringJoiner out = new StringJoiner("&");
    if (args == null) {
      return "";
    }
    for (Map.Entry<String, JsonNode> e : args.properties()) {
      JsonNode v = e.getValue();
      String text;
      if (v.isArray()) {
        StringJoiner list = new StringJoiner(",");
        v.forEach(x -> list.add(x.asString()));
        text = list.toString();
      } else {
        text = v.asString();
      }
      out.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(text,
          StandardCharsets.UTF_8));
    }
    return out.toString();
  }
}
