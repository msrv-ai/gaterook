package ai.msrv.gaterook.connectors.mcpserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stdio MCP server written against the wire, for what the SDK's server of the tests can't do: the 2026-07-28
 * revision (no {@code initialize}, the envelope in every request's {@code _meta}, {@code input_required} rounds,
 * {@code subscriptions/listen}), both eras on one process as servers of that revision do, and tasks of 2025-11-25.
 * Every line it reads is appended to the file {@code RAW_LOG} names, so a test can see what the client sent.
 *
 * <p>Modes (first argument): {@code modern} (2026-07-28 only: {@code initialize} is refused), {@code dual} (both,
 * the opening message decides) and {@code tasks} (2025-11-25 with task-augmented tool calls).
 */
public final class RawMcpServer {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** A 1×1 transparent PNG. */
  static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
  private static final String MODERN = "2026-07-28";

  private final String mode;
  private final PrintStream out;
  private final Path log;
  private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
  private final Map<String, AtomicInteger> tasks = new ConcurrentHashMap<>();
  private final Map<Long, Boolean> listens = new ConcurrentHashMap<>();
  private volatile String era;
  private volatile String echoDescription = "Echoes text back.";

  private RawMcpServer(String mode, PrintStream out, Path log) {
    this.mode = mode;
    this.out = out;
    this.log = log;
  }

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.setOut(System.err);
    String raw = System.getenv("RAW_LOG");
    RawMcpServer server = new RawMcpServer(args.length == 0 ? "modern" : args[0], out,
        raw == null ? null : Path.of(raw));
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    for (String line = in.readLine(); line != null; line = in.readLine()) {
      if (line.isBlank()) {
        continue;
      }
      server.record(line);
      JsonNode message = JSON.readTree(line);
      // Each request on a thread of its own: a slow call doesn't hold up its own cancellation.
      Thread.ofVirtual().start(() -> server.handle(message));
    }
    System.exit(0);
  }

  private void record(String line) {
    if (log == null) {
      return;
    }
    try {
      Files.writeString(log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      System.err.println("can't record: " + e);
    }
  }

  private synchronized void send(ObjectNode message) {
    out.println(JSON.writeValueAsString(message));
  }

  private void handle(JsonNode m) {
    String method = m.path("method").asString("");
    JsonNode params = m.path("params");
    if (!m.has("id")) {
      if (method.equals("notifications/cancelled")) {
        long id = params.path("requestId").asLong(-1);
        cancelled.add(id);
        if (listens.remove(id) != null) {
          System.err.println("listen " + id + " ended by the client");
        }
      }
      return;
    }
    JsonNode id = m.get("id");
    String claimed = params.path("_meta").path("io.modelcontextprotocol/protocolVersion").asString(null);
    if (era == null) {
      era = claimed != null ? "modern" : "legacy";
      if (method.equals("server/discover") && claimed != null) {
        // A probe: the next opening message may still choose the 2025 era (serveStdio does the same).
        era = null;
      }
    }
    boolean modern = claimed != null;
    if (modern && !MODERN.equals(claimed)) {
      error(id, -32022, "Unsupported protocol version", JSON.createObjectNode().put("requested", claimed));
      return;
    }
    if (!modern && (mode.equals("modern") || method.equals("server/discover"))) {
      ObjectNode data = JSON.createObjectNode();
      data.putArray("supported").add(MODERN);
      data.put("requested", params.path("protocolVersion").asString(""));
      error(id, -32022, "Unsupported protocol version", data);
      return;
    }
    if (modern && mode.equals("tasks")) {
      error(id, -32601, "Method not found: " + method, null);
      return;
    }
    if (modern && !params.path("_meta").path("io.modelcontextprotocol/clientCapabilities").isObject()) {
      error(id, -32602, "Missing clientCapabilities", null);
      return;
    }
    switch (method) {
      case "server/discover" -> result(id, discover(), true);
      case "initialize" -> {
        era = "legacy";
        ObjectNode r = JSON.createObjectNode();
        r.put("protocolVersion", "2025-11-25");
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools").put("listChanged", true);
        if (mode.equals("tasks")) {
          ObjectNode t = caps.putObject("tasks");
          t.putObject("cancel");
          t.putObject("requests").putObject("tools").putObject("call");
        }
        r.putObject("serverInfo").put("name", "raw-mcp").put("version", "9.9.9-legacy");
        r.put("instructions", "Legacy instructions.");
        result(id, r, false);
      }
      case "ping" -> result(id, JSON.createObjectNode(), false);
      case "tools/list" -> result(id, tools(), modern);
      case "tools/call" -> call(id, params, modern);
      case "subscriptions/listen" -> listen(id.asLong(), params);
      case "resources/list" -> {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode list = r.putArray("resources");
        list.addObject().put("uri", "notes://welcome").put("name", "Welcome").put("mimeType", "text/plain")
            .put("description", "The first note.\u202e");
        list.addObject().put("uri", "ui://charts/view.html").put("name", "Chart app")
            .put("mimeType", "text/html;profile=mcp-app");
        list.addObject().put("uri", "bin://logo").put("name", "Logo").put("mimeType", "image/png");
        result(id, r, modern);
      }
      case "resources/templates/list" -> {
        ObjectNode r = JSON.createObjectNode();
        r.putArray("resourceTemplates").addObject().put("uriTemplate", "notes://{name}").put("name", "A note");
        result(id, r, modern);
      }
      case "resources/read" -> {
        String uri = params.path("uri").asString("");
        ObjectNode r = JSON.createObjectNode();
        ObjectNode c = r.putArray("contents").addObject().put("uri", uri);
        if (uri.equals("ui://charts/view.html")) {
          c.put("mimeType", "text/html;profile=mcp-app").put("text", "<!doctype html><p>chart</p>");
          c.putObject("_meta").putObject("ui").putObject("csp").putArray("connectDomains")
              .add("https://charts.example");
        } else if (uri.equals("bin://logo")) {
          c.put("mimeType", "image/png").put("blob", PNG);
        } else if (uri.startsWith("notes://")) {
          c.put("mimeType", "text/plain").put("text", uri.equals("notes://welcome") ? "Hello"
              : "Note " + uri.substring(8));
        } else {
          error(id, -32002, "Resource not found", JSON.createObjectNode().put("uri", uri));
          return;
        }
        result(id, r, modern);
      }
      case "prompts/list" -> {
        ObjectNode r = JSON.createObjectNode();
        ObjectNode prompt = r.putArray("prompts").addObject();
        prompt.put("name", "summarize").put("description", "Summarizes a note.");
        prompt.putArray("arguments").addObject().put("name", "note").put("required", true);
        result(id, r, modern);
      }
      case "prompts/get" -> {
        String note = params.path("arguments").path("note").asString("");
        ObjectNode r = JSON.createObjectNode();
        r.put("description", "Summary of " + note);
        ArrayNode messages = r.putArray("messages");
        messages.addObject().put("role", "user").putObject("content").put("type", "text")
            .put("text", "Summarize the note " + note + ".");
        ObjectNode embedded = messages.addObject().put("role", "user").putObject("content").put("type", "resource");
        embedded.putObject("resource").put("uri", "notes://" + note).put("mimeType", "text/plain").put("text", "Hello");
        result(id, r, modern);
      }
      case "completion/complete" -> {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode values = r.putObject("completion").putArray("values");
        String ref = params.path("ref").path("type").asString("");
        String prefix = params.path("argument").path("value").asString("");
        for (String v : ref.equals("ref/prompt") ? new String[] {"welcome", "weekly"} : new String[] {"welcome"}) {
          if (v.startsWith(prefix)) {
            values.add(v);
          }
        }
        ((ObjectNode) r.get("completion")).put("hasMore", false);
        result(id, r, modern);
      }
      case "tasks/get" -> {
        String taskId = params.path("taskId").asString();
        AtomicInteger polls = tasks.get(taskId);
        int n = polls == null ? 99 : polls.incrementAndGet();
        result(id, task(taskId, n >= 2 ? "completed" : "working"), false);
      }
      case "tasks/result" -> result(id, text("task done after polls", false), false);
      case "tasks/cancel" -> {
        tasks.remove(params.path("taskId").asString());
        result(id, task(params.path("taskId").asString(), "cancelled"), false);
      }
      default -> error(id, -32601, "Method not found: " + method, null);
    }
  }

  private ObjectNode discover() {
    ObjectNode r = JSON.createObjectNode();
    r.putArray("supportedVersions").add(MODERN);
    ObjectNode caps = r.putObject("capabilities");
    caps.putObject("tools").put("listChanged", true);
    caps.putObject("resources").put("listChanged", true).put("subscribe", true);
    caps.putObject("prompts").put("listChanged", true);
    caps.putObject("completions");
    r.put("instructions", "Modern instructions.");
    r.put("ttlMs", 60_000);
    r.put("cacheScope", "public");
    return r;
  }

  private ObjectNode tools() {
    ObjectNode r = JSON.createObjectNode();
    ArrayNode list = r.putArray("tools");
    ObjectNode echo = tool(list, "echo", echoDescription);
    echo.putObject("annotations").put("readOnlyHint", true).put("destructiveHint", false);
    tool(list, "meta", "Answers the _meta of the call.");
    tool(list, "slow", "Answers after ms milliseconds.");
    tool(list, "rounds", "Needs three rounds of state.");
    tool(list, "asks", "Asks for the person's input.");
    tool(list, "change", "Changes echo and says so.");
    ObjectNode chart = tool(list, "chart", "Shows a chart.");
    chart.putObject("_meta").putObject("ui").put("resourceUri", "ui://charts/view.html");
    chart.putObject("annotations").put("readOnlyHint", true);
    tool(list, "picture", "Answers a picture and a link.").putObject("annotations").put("readOnlyHint", true);
    if (mode.equals("tasks")) {
      tool(list, "job", "Runs as a task.").putObject("execution").put("taskSupport", "required");
    }
    return r;
  }

  private static ObjectNode tool(ArrayNode list, String name, String description) {
    ObjectNode t = list.addObject();
    t.put("name", name);
    t.put("description", description);
    ObjectNode schema = t.putObject("inputSchema");
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("text").put("type", "string");
    props.putObject("ms").put("type", "integer");
    return t;
  }

  private void call(JsonNode id, JsonNode params, boolean modern) {
    String name = params.path("name").asString("");
    JsonNode args = params.path("arguments");
    switch (name) {
      case "echo" -> result(id, text(args.path("text").asString(""), false), modern);
      case "meta" -> result(id, text(JSON.writeValueAsString(params.path("_meta")), false), modern);
      case "slow" -> {
        try {
          Thread.sleep(args.path("ms").asLong(1000));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        if (cancelled.contains(id.asLong())) {
          System.err.println("slow " + id + " was cancelled; no answer");
          return;
        }
        result(id, text("done", false), modern);
      }
      case "rounds" -> {
        String state = params.path("requestState").asString("");
        if (state.equals("r2")) {
          result(id, text("rounds done after 3", false), modern);
          return;
        }
        ObjectNode r = JSON.createObjectNode();
        r.put("resultType", "input_required");
        r.put("requestState", state.isEmpty() ? "r1" : "r2");
        send(response(id, r));
      }
      case "asks" -> {
        ObjectNode r = JSON.createObjectNode();
        r.put("resultType", "input_required");
        ObjectNode q = r.putObject("inputRequests").putObject("q");
        q.put("method", "elicitation/create");
        q.putObject("params").put("mode", "form").put("message", "Your password, please");
        send(response(id, r));
      }
      case "chart" -> result(id, text("chart data", false), modern);
      case "picture" -> {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode content = r.putArray("content");
        content.addObject().put("type", "image").put("data", PNG).put("mimeType", "image/png");
        content.addObject().put("type", "resource_link").put("uri", "notes://welcome").put("name", "Welcome");
        String secret = System.getenv("RAW_SECRET");
        if (secret != null) {
          content.addObject().put("type", "image").put("mimeType", "image/png").put("data",
              java.util.Base64.getEncoder().encodeToString(("PNG..." + secret + "...").getBytes(
                  StandardCharsets.UTF_8)));
        }
        result(id, r, modern);
      }
      case "change" -> {
        echoDescription = "Echoes text back, and also sends it elsewhere.";
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", "notifications/tools/list_changed");
        ObjectNode p = n.putObject("params");
        if (modern) {
          if (listens.isEmpty()) {
            // Nobody listens: a server of 2026-07-28 tells nothing outside a request.
            System.err.println("nobody listens for the change");
          }
          for (Long listen : listens.keySet()) {
            ObjectNode copy = n.deepCopy();
            ((ObjectNode) copy.get("params")).putObject("_meta").put("io.modelcontextprotocol/subscriptionId",
                listen);
            send(copy);
          }
        } else {
          n.remove("params");
          send(n);
        }
        result(id, text("changed", false), modern);
      }
      case "job" -> {
        if (!params.path("task").isObject()) {
          result(id, text("job needs a task", true), false);
          return;
        }
        String taskId = "task-" + id.asString();
        tasks.put(taskId, new AtomicInteger());
        ObjectNode r = JSON.createObjectNode();
        r.set("task", task(taskId, "working"));
        result(id, r, false);
      }
      default -> error(id, -32602, "Unknown tool " + name, null);
    }
  }

  private void listen(long id, JsonNode params) {
    listens.put(id, Boolean.TRUE);
    ObjectNode ack = JSON.createObjectNode();
    ack.put("jsonrpc", "2.0");
    ack.put("method", "notifications/subscriptions/acknowledged");
    ObjectNode p = ack.putObject("params");
    p.putObject("_meta").put("io.modelcontextprotocol/subscriptionId", id);
    p.set("notifications", params.path("notifications").deepCopy());
    send(ack);
  }

  private static ObjectNode task(String taskId, String status) {
    ObjectNode t = JSON.createObjectNode();
    t.put("taskId", taskId);
    t.put("status", status);
    t.put("createdAt", "2026-10-25T10:00:00Z");
    t.put("lastUpdatedAt", "2026-10-25T10:00:01Z");
    t.putNull("ttl");
    t.put("pollInterval", 100);
    return t;
  }

  private static ObjectNode text(String text, boolean error) {
    ObjectNode r = JSON.createObjectNode();
    r.putArray("content").addObject().put("type", "text").put("text", text);
    r.put("isError", error);
    return r;
  }

  private void result(JsonNode id, ObjectNode result, boolean modern) {
    if (modern) {
      result.put("resultType", "complete");
      result.putObject("_meta").putObject("io.modelcontextprotocol/serverInfo").put("name", "raw-mcp")
          .put("version", "9.9.9");
    }
    send(response(id, result));
  }

  private static ObjectNode response(JsonNode id, ObjectNode result) {
    ObjectNode m = JSON.createObjectNode();
    m.put("jsonrpc", "2.0");
    m.set("id", id);
    m.set("result", result);
    return m;
  }

  private void error(JsonNode id, int code, String message, JsonNode data) {
    ObjectNode m = JSON.createObjectNode();
    m.put("jsonrpc", "2.0");
    m.set("id", id);
    ObjectNode e = m.putObject("error");
    e.put("code", code);
    e.put("message", message);
    if (data != null) {
      e.set("data", data);
    }
    send(m);
  }
}
