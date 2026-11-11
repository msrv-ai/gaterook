package ai.msrv.gaterook.connectors.athena;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Athena and STS on this computer. {@code StartQueryExecution} picks the script whose statement prefix matches; each
 * {@code GetQueryExecution} moves the query through the script's states; {@code GetQueryResults} pages its rows by
 * {@code MaxResults} and {@code NextToken} (a query's answer starts with the column labels, as Athena's does);
 * {@code StopQueryExecution} is recorded. A reused {@code ClientRequestToken} returns the same query, as Athena does.
 * STS answers AssumeRole and GetCallerIdentity. The test endpoint {@code aws.athena} points here.
 */
final class FakeAthena implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /**
   * How one statement runs.
   *
   * @param states     what each poll answers, the last one repeated; with {@code endless}, always RUNNING
   * @param rows       data rows of the answer as cell values ({@code null}: NULL)
   * @param failure    the message of a FAILED query, or {@code null}
   */
  record Script(List<String> states, boolean endless, String statementType, String substatementType, long scanned,
      List<String[]> columns, List<String[]> rows, Long updateCount, String failure, int category) {

    /** A query that succeeds after one RUNNING poll with {@code columns} ({@code name:type}) and {@code rows}. */
    static Script select(List<String> columns, String[]... rows) {
      List<String[]> cols = new ArrayList<>();
      for (String c : columns) {
        cols.add(c.split(":", 2));
      }
      return new Script(List.of("RUNNING", "SUCCEEDED"), false, "DML", "SELECT", 10_485_760L, cols, List.of(rows),
          null, null, 0);
    }

    Script statement(String type, String sub) {
      return new Script(states, endless, type, sub, scanned, columns, rows, updateCount, failure, category);
    }

    Script updated(long count) {
      return new Script(states, endless, statementType, substatementType, scanned, columns, rows, count, failure,
          category);
    }

    static Script running() {
      return new Script(List.of("RUNNING"), true, "DML", "SELECT", 0, List.of(), List.of(), null, null, 0);
    }

    static Script failed(String message, int category) {
      return new Script(List.of("RUNNING", "FAILED"), false, "DML", "SELECT", 0, List.of(), List.of(), null, message,
          category);
    }

    /** The answer of {@code EXPLAIN (TYPE IO, FORMAT JSON)}: one varchar cell with the plan. */
    static Script plan(List<String> inputs, String output) {
      ObjectNode plan = F.objectNode();
      ArrayNode infos = plan.putArray("inputTableColumnInfos");
      for (String t : inputs) {
        infos.addObject().set("table", table(t));
      }
      if (output != null) {
        plan.set("outputTable", table(output));
      }
      return select(List.of("Query Plan:varchar"), new String[] {plan.toPrettyString()});
    }

    private static ObjectNode table(String dotted) {
      String[] p = dotted.split("\\.");
      ObjectNode t = F.objectNode().put("catalog", p[0]);
      t.putObject("schemaTable").put("schema", p[1]).put("table", p[2]);
      return t;
    }
  }

  record Query(String id, String sql, String token, JsonNode request, Script script, AtomicInteger polls) {}

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  final List<Query> started = new CopyOnWriteArrayList<>();
  final List<String> stops = new CopyOnWriteArrayList<>();
  private final Map<String, Function<String, Script>> scripts = new ConcurrentHashMap<>();
  private final Map<String, Query> queries = new ConcurrentHashMap<>();
  private final Map<String, String> tokens = new ConcurrentHashMap<>();
  private final Map<String, Function<JsonNode, MockResponse>> operations = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final MockWebServer server = new MockWebServer();
  final CloudCredentials cloud;

  FakeAthena() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String op = operation(request);
        Function<JsonNode, MockResponse> custom = operations.get(op);
        if (custom != null) {
          return custom.apply(body(request));
        }
        return switch (op) {
          case "AssumeRole" -> xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<AssumeRoleResult><Credentials><AccessKeyId>ASIATESTSESSION00001</AccessKeyId><SecretAccessKey>"
              + "session-secret</SecretAccessKey><SessionToken>session-token</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROATEST:gaterook</AssumedRoleId><Arn>"
              + "arn:aws:sts::412398765012:assumed-role/gw-athena/gaterook</Arn></AssumedRoleUser>"
              + "</AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</AssumeRoleResponse>");
          case "GetCallerIdentity" -> xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/"
              + "2011-06-15/\"><GetCallerIdentityResult><Arn>" + (String.valueOf(request.getHeaders()
              .get("Authorization")).contains("Credential=ASIA")
              ? "arn:aws:sts::412398765012:assumed-role/gw-athena/gaterook" : "arn:aws:iam::412398765012:user/reader")
              + "</Arn><UserId>AIDATEST</UserId><Account>412398765012</Account></GetCallerIdentityResult>"
              + "<ResponseMetadata><RequestId>2</RequestId></ResponseMetadata></GetCallerIdentityResponse>");
          case "GetWorkGroup" -> json(200, workgroup(Long.valueOf(10_737_418_240L), true,
              "s3://acme-athena-results/gaterook/", false));
          case "StartQueryExecution" -> start(body(request));
          case "GetQueryExecution" -> execution(body(request).path("QueryExecutionId").asString());
          case "GetQueryResults" -> results(body(request));
          case "StopQueryExecution" -> {
            stops.add(body(request).path("QueryExecutionId").asString());
            yield json(200, "{}");
          }
          default -> json(400, "{\"__type\": \"InvalidRequestException\", \"Message\": \"no fake for " + op + "\"}");
        };
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getPort());
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null, Map.of("aws.athena", base)),
        Clock.systemUTC());
  }

  /** A GetWorkGroup answer. */
  static String workgroup(Long cutoff, boolean enforced, String output, boolean managed) {
    ObjectNode wg = F.objectNode();
    ObjectNode w = wg.putObject("WorkGroup").put("Name", "primary").put("State", "ENABLED");
    ObjectNode c = w.putObject("Configuration").put("EnforceWorkGroupConfiguration", enforced);
    if (cutoff != null) {
      c.put("BytesScannedCutoffPerQuery", cutoff);
    }
    if (output != null) {
      c.putObject("ResultConfiguration").put("OutputLocation", output);
    }
    c.putObject("ManagedQueryResultsConfiguration").put("Enabled", managed);
    c.putObject("EngineVersion").put("SelectedEngineVersion", "AUTO").put("EffectiveEngineVersion",
        "Athena engine version 3");
    return wg.toString();
  }

  /** Answers every statement that starts with {@code prefix} (the longest prefix wins). */
  FakeAthena on(String prefix, Script script) {
    scripts.put(prefix, sql -> script);
    return this;
  }

  /** Answers one operation ({@code ListDatabases}) with {@code answer}. */
  FakeAthena operation(String op, Function<JsonNode, MockResponse> answer) {
    operations.put(op, answer);
    return this;
  }

  FakeAthena operation(String op, String body) {
    return operation(op, b -> json(200, body));
  }

  private MockResponse start(JsonNode body) {
    String token = body.path("ClientRequestToken").asString("");
    String reused = tokens.get(token);
    if (reused != null) {
      return json(200, "{\"QueryExecutionId\": \"" + reused + "\"}");
    }
    String sql = body.path("QueryString").asString("");
    String best = null;
    for (String prefix : scripts.keySet()) {
      if (sql.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
        best = prefix;
      }
    }
    Script script = best == null ? Script.failed("no fake for " + sql, 2) : scripts.get(best).apply(sql);
    String id = "q-" + String.format("%04d", ids.incrementAndGet()) + "-7f3a";
    Query q = new Query(id, sql, token, body, script, new AtomicInteger());
    queries.put(id, q);
    tokens.put(token, id);
    started.add(q);
    return json(200, "{\"QueryExecutionId\": \"" + id + "\"}");
  }

  private MockResponse execution(String id) {
    Query q = queries.get(id);
    if (q == null) {
      return json(400, "{\"__type\": \"InvalidRequestException\", \"Message\": \"QUERY_NOT_FOUND\"}");
    }
    Script s = q.script();
    int poll = q.polls().getAndIncrement();
    String state = stops.contains(id) ? "CANCELLED" : s.endless() ? "RUNNING"
        : s.states().get(Math.min(poll, s.states().size() - 1));
    ObjectNode out = F.objectNode();
    ObjectNode e = out.putObject("QueryExecution").put("QueryExecutionId", id).put("Query", q.sql())
        .put("StatementType", s.statementType()).put("SubstatementType", s.substatementType());
    ObjectNode status = e.putObject("Status").put("State", state);
    if (state.equals("FAILED")) {
      status.put("StateChangeReason", s.failure());
      status.putObject("AthenaError").put("ErrorCategory", s.category()).put("ErrorType", 1301)
          .put("Retryable", false).put("ErrorMessage", s.failure());
    }
    e.putObject("Statistics").put("DataScannedInBytes", s.scanned()).put("EngineExecutionTimeInMillis", 840);
    e.put("WorkGroup", q.request().path("WorkGroup").asString(""));
    return json(200, out.toString());
  }

  private MockResponse results(JsonNode body) {
    Query q = queries.get(body.path("QueryExecutionId").asString());
    if (q == null) {
      return json(400, "{\"__type\": \"InvalidRequestException\", \"Message\": \"QUERY_NOT_FOUND\"}");
    }
    Script s = q.script();
    List<String[]> all = new ArrayList<>();
    if (s.statementType().equals("DML") && !s.columns().isEmpty()) {
      String[] labels = new String[s.columns().size()];
      for (int i = 0; i < labels.length; i++) {
        labels[i] = s.columns().get(i)[0];
      }
      all.add(labels);
    }
    all.addAll(s.rows());
    int start = body.path("NextToken").isString() ? Integer.parseInt(body.get("NextToken").asString().substring(4))
        : 0;
    int max = body.path("MaxResults").asInt(1000);
    ObjectNode out = F.objectNode();
    ObjectNode rs = out.putObject("ResultSet");
    ArrayNode info = rs.putObject("ResultSetMetadata").putArray("ColumnInfo");
    for (String[] c : s.columns()) {
      info.addObject().put("CatalogName", "hive").put("Name", c[0]).put("Label", c[0]).put("Type", c[1])
          .put("Nullable", "UNKNOWN").put("CaseSensitive", false);
    }
    ArrayNode rows = rs.putArray("Rows");
    for (int i = start; i < Math.min(all.size(), start + max); i++) {
      ArrayNode data = rows.addObject().putArray("Data");
      for (String v : all.get(i)) {
        ObjectNode cell = data.addObject();
        if (v != null) {
          cell.put("VarCharValue", v);
        }
      }
    }
    if (start + max < all.size()) {
      out.put("NextToken", "tok-" + (start + max));
    }
    if (s.updateCount() != null) {
      out.put("UpdateCount", s.updateCount());
    }
    return json(200, out.toString());
  }

  static String operation(RecordedRequest r) {
    String target = r.getHeaders().get("X-Amz-Target");
    if (target != null) {
      return target.substring(target.indexOf('.') + 1);
    }
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    int at = body.indexOf("Action=");
    if (at < 0) {
      return r.getUrl().encodedPath();
    }
    int end = body.indexOf('&', at);
    return body.substring(at + 7, end < 0 ? body.length() : end);
  }

  /** The requests of one operation, in order. */
  List<RecordedRequest> to(String operation) {
    return requests.stream().filter(r -> operation(r).equals(operation)).toList();
  }

  /** The session policy of the AssumeRole requests, in order. */
  List<String> policies() {
    List<String> out = new ArrayList<>();
    for (RecordedRequest r : to("AssumeRole")) {
      for (String pair : r.getBody().utf8().split("&")) {
        if (pair.startsWith("Policy=")) {
          out.add(URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8));
        }
      }
    }
    return out;
  }

  static JsonNode body(RecordedRequest r) {
    String text = r.getBody() == null ? "" : r.getBody().utf8();
    return text.startsWith("{") ? JSON.readTree(text) : F.objectNode();
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", AthenaApi.CONTENT_TYPE).body(body).build();
  }

  private static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }

  @Override
  public void close() {
    cloud.close();
    server.close();
  }
}
