package ai.msrv.gaterook.core.notifications.internal.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The eight Bot API methods the gateway uses (report 13 §1): {@code getMe}, {@code getWebhookInfo},
 * {@code deleteWebhook}, {@code getUpdates}, {@code sendMessage}, {@code editMessageText},
 * {@code editMessageReplyMarkup}, {@code answerCallbackQuery}, as JSON POSTs on {@code java.net.http}. The bot token
 * is part of the URL ({@code /bot<token>/<method>}), so no URL of a request is ever logged or put into an exception.
 */
public final class TelegramApi {

  /** What the Bot API refused: {@code error_code}, {@code description}, {@code parameters.retry_after}. */
  public static final class TelegramException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int code;
    private final String description;
    private final long retryAfterSeconds;

    public TelegramException(int code, String description, long retryAfterSeconds) {
      super("Telegram " + code + ": " + description);
      this.code = code;
      this.description = description;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public int code() {
      return code;
    }

    public String description() {
      return description;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }

    /** "Bad Request: message is not modified": the edit is already there. */
    public boolean notModified() {
      return code == 400 && description != null && description.contains("message is not modified");
    }
  }

  private final HttpClient http;
  private final JsonMapper json;
  private final URI base;

  public TelegramApi(HttpClient http, JsonMapper json, URI base) {
    this.http = http;
    this.json = json;
    this.base = base;
  }

  /**
   * Calls {@code method}; returns its {@code result}.
   *
   * @param timeout how long the request may take ({@code getUpdates} holds it for its own timeout)
   */
  public JsonNode call(String method, ObjectNode args, String token, Duration timeout) throws TelegramException,
      IOException, InterruptedException {
    if (token == null || token.isBlank()) {
      throw new TelegramException(401, "Unauthorized: no bot token", 0);
    }
    URI uri = URI.create(base + "/bot" + token + "/" + method);
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
        .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(args == null
            ? JsonNodeFactory.instance.objectNode() : args), StandardCharsets.UTF_8)).build();
    HttpResponse<String> rs;
    try {
      rs = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // The JDK's message may name the URI, and with it the token.
      throw new IOException("Telegram unreachable: " + e.getClass().getSimpleName());
    }
    JsonNode body;
    try {
      body = json.readTree(rs.body());
    } catch (RuntimeException e) {
      throw new TelegramException(rs.statusCode(), "not a Bot API answer", 0);
    }
    if (!body.path("ok").asBoolean(false)) {
      throw new TelegramException(body.path("error_code").asInt(rs.statusCode()),
          body.path("description").asString("error"), body.at("/parameters/retry_after").asLong(0));
    }
    return body.path("result");
  }
}
