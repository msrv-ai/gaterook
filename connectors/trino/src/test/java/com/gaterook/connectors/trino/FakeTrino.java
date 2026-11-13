package com.gaterook.connectors.trino;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Trino coordinator on this computer: {@code POST /v1/statement} answers with the first page of the script whose
 * statement prefix matches, each {@code nextUri} with the next page, {@code DELETE} records a cancel. A script that
 * never ends models a query that outlives its call. {@code GET /v1/info} answers like Trino 484.
 */
final class FakeTrino implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** The pages of one statement's answer: QueryResults without {@code id} and {@code nextUri}, which the fake adds. */
  record Script(List<ObjectNode> pages, boolean endless) {}

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  final List<String> statements = new CopyOnWriteArrayList<>();
  final List<String> cancels = new CopyOnWriteArrayList<>();
  private final Map<String, Function<String, Script>> scripts = new ConcurrentHashMap<>();
  private final Map<String, Script> running = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final MockWebServer server = new MockWebServer();
  /** Overrides the {@code nextUri} host (a coordinator that names another server). */
  volatile String nextBase;
  /** The version {@code GET /v1/info} names: Trino's release, or a Presto one such as {@code 0.289}. */
  volatile String version = "484";

  FakeTrino() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        String method = request.getMethod();
        if (method.equals("GET") && path.equals("/v1/info")) {
          return json(200, "{\"nodeVersion\":{\"version\":\"" + version + "\"},\"environment\":\"test\","
              + "\"coordinator\":true,\"starting\":false,\"uptime\":\"1.00m\"}");
        }
        if (method.equals("POST") && path.equals("/v1/statement")) {
          String sql = request.getBody().utf8();
          statements.add(sql);
          String best = null;
          for (String prefix : scripts.keySet()) {
            if (sql.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
              best = prefix;
            }
          }
          if (best == null) {
            return page("q" + ids.incrementAndGet(), error("SYNTAX_ERROR", "USER_ERROR", "no fake for " + sql), 0,
                false);
          }
          Script script = scripts.get(best).apply(sql);
          String id = "20261111_000000_" + String.format("%05d", ids.incrementAndGet()) + "_fake";
          running.put(id, script);
          return page(id, script.pages().isEmpty() ? F.objectNode() : script.pages().getFirst(), 0,
              script.endless() || script.pages().size() > 1);
        }
        if (path.startsWith("/v1/statement/executing/")) {
          String[] parts = path.split("/");
          String id = parts[4];
          int index = Integer.parseInt(parts[6]);
          if (method.equals("DELETE")) {
            cancels.add(id);
            return new MockResponse.Builder().code(204).build();
          }
          Script script = running.get(id);
          if (script == null) {
            return new MockResponse.Builder().code(404).body("unknown query").build();
          }
          ObjectNode body = index < script.pages().size() ? script.pages().get(index) : F.objectNode();
          boolean more = script.endless() || index + 1 < script.pages().size();
          MockResponse page = page(id, body, index, more);
          // A running query's poll waits a little, as Trino's does.
          return script.endless() ? page.newBuilder().headersDelay(20, TimeUnit.MILLISECONDS).build() : page;
        }
        return new MockResponse.Builder().code(404).body("no fake for " + method + " " + path).build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** The coordinator's URL, as a connection names it. */
  String url() {
    return "http://127.0.0.1:" + server.getPort();
  }

  int port() {
    return server.getPort();
  }

  /** Answers every statement that starts with {@code prefix} (the longest prefix wins). */
  FakeTrino on(String prefix, Function<String, Script> script) {
    scripts.put(prefix, script);
    return this;
  }

  FakeTrino on(String prefix, ObjectNode... pages) {
    return on(prefix, sql -> new Script(List.of(pages), false));
  }

  /** A query that never finishes: every page asks to come back. */
  FakeTrino endless(String prefix, ObjectNode... first) {
    return on(prefix, sql -> new Script(List.of(first), true));
  }

  /** The requests whose {@code METHOD /path} starts with {@code prefix}. */
  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(prefix))
        .toList();
  }

  private MockResponse page(String id, ObjectNode body, int index, boolean more) {
    ObjectNode out = body.deepCopy();
    out.put("id", id);
    out.put("infoUri", url() + "/ui#/queries/" + id);
    if (more) {
      String base = nextBase != null ? nextBase : url();
      out.put("nextUri", base + "/v1/statement/executing/" + id + "/yslug" + index + "/" + (index + 1));
    }
    if (!out.has("stats")) {
      out.putObject("stats").put("state", more ? "RUNNING" : "FINISHED").put("processedRows", 25)
          .put("processedBytes", 2_048);
    }
    out.putArray("warnings");
    return json(200, out.toString());
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  /** A page with columns ({@code name type}, as Trino writes the type) and rows. */
  static ObjectNode rows(String columns, String... rows) {
    ObjectNode page = F.objectNode();
    page.set("columns", JSON.readTree(columns));
    ArrayNode data = page.putArray("data");
    for (String row : rows) {
      data.add(JSON.readTree(row));
    }
    return page;
  }

  /** A column of a simple type: its name, type and {@code typeSignature}. */
  static String column(String name, String type) {
    return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"typeSignature\":{\"rawType\":\"" + type
        + "\",\"arguments\":[]}}";
  }

  /** The answer of {@code EXPLAIN (TYPE IO, FORMAT JSON)}: one varchar cell holding the plan. */
  static ObjectNode plan(List<String> inputs, String output) {
    ObjectNode plan = F.objectNode();
    ArrayNode infos = plan.putArray("inputTableColumnInfos");
    for (String t : inputs) {
      infos.addObject().set("table", table(t));
    }
    if (output != null) {
      plan.set("outputTable", table(output));
    }
    plan.putObject("estimate").put("outputRowCount", "NaN");
    return text(plan.toPrettyString());
  }

  /** One varchar cell: the plan of a data definition statement, or any single text. */
  static ObjectNode text(String value) {
    ObjectNode page = rows("[" + column("Query Plan", "varchar") + "]");
    ((ArrayNode) page.get("data")).addArray().add(value);
    return page;
  }

  private static ObjectNode table(String dotted) {
    String[] p = dotted.split("\\.");
    ObjectNode t = F.objectNode().put("catalog", p[0]);
    t.putObject("schemaTable").put("schema", p[1]).put("table", p[2]);
    return t;
  }

  /** A failed query. */
  static ObjectNode error(String name, String type, String message) {
    ObjectNode page = F.objectNode();
    page.putObject("error").put("message", message).put("errorCode", 1).put("errorName", name)
        .put("errorType", type).putObject("failureInfo").put("type", "io.trino.spi.TrinoException")
        .putArray("stack").add("io.trino.secret.Internal.stack(Internal.java:1)");
    page.putObject("stats").put("state", "FAILED");
    return page;
  }

  /** A change's answer: Trino's update type and count. */
  static ObjectNode update(String type, Long count) {
    ObjectNode page = F.objectNode().put("updateType", type);
    if (count != null) {
      page.put("updateCount", count);
      page.set("columns", JSON.readTree("[" + column("rows", "bigint") + "]"));
      page.putArray("data").addArray().add(count);
    }
    return page;
  }

  static List<String> list(String... items) {
    return new ArrayList<>(List.of(items));
  }

  @Override
  public void close() {
    server.close();
  }
}
