package ai.msrv.gaterook.core.notifications;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An agent on one endpoint of a test core: {@code tools/call} over the 2025-11-25 transport with its bearer token, as
 * a client sends it. Only what the notification tests need: a mutation that waits for a person, and its status.
 */
final class AgentCalls {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final int port;
  private final String slug;
  private final String token;
  private final AtomicInteger ids = new AtomicInteger(1);

  AgentCalls(int port, String slug, String token) {
    this.port = port;
    this.slug = slug;
    this.token = token;
  }

  /** {@code sql_execute} on {@code connection}: the handle of the request it opened. */
  String execute(String connection, String sql) {
    JsonNode result = call("sql_execute", JsonNodeFactory.instance.objectNode().put("connection", connection)
        .put("sql", sql));
    String handle = result.path("structuredContent").path("handle").asString(null);
    if (handle == null) {
      throw new AssertionError("sql_execute opened no request: " + result);
    }
    return handle;
  }

  /** {@code approval_status} of {@code handle}. */
  JsonNode status(String handle) {
    return call("approval_status", JsonNodeFactory.instance.objectNode().put("handle", handle))
        .path("structuredContent");
  }

  private JsonNode call(String tool, ObjectNode arguments) {
    ObjectNode body = JsonNodeFactory.instance.objectNode().put("jsonrpc", "2.0").put("id", ids.getAndIncrement())
        .put("method", "tools/call");
    body.putObject("params").put("name", tool).set("arguments", arguments);
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/" + slug))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
        .header("MCP-Protocol-Version", "2025-11-25")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    try {
      HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200) {
        throw new AssertionError(tool + " answered " + r.statusCode() + ": " + r.body());
      }
      return JSON.readTree(r.body()).path("result");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
