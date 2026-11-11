package ai.msrv.gaterook.connectors.snowflake;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.GZIPOutputStream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fake of Snowflake's SQL API on MockWebServer. Every request must sign in as the fake expects: a key-pair JWT whose
 * RS256 signature the test's public key verifies, whose {@code iss} and {@code sub} name the account, the user and the
 * key's fingerprint, and that lives at most an hour; or the expected programmatic access token. A request that
 * doesn't is answered 401 and its problem recorded. Statements are answered by the first matching handler.
 */
final class FakeSnowflake implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** One key pair for all tests: generating RSA keys is slow. */
  static final KeyPair KEYS = generate();

  final MockWebServer server = new MockWebServer();
  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  final List<String> problems = new CopyOnWriteArrayList<>();
  private final List<Map.Entry<Predicate<String>, Function<JsonNode, MockResponse>>> statements =
      new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> handles = new ConcurrentHashMap<>();
  private volatile String subject = "MYORG-MYACCOUNT.GW_READER";
  private volatile String token;

  FakeSnowflake() {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String problem = signIn(request);
        if (problem != null) {
          problems.add(problem);
          return json(401, "{\"code\": \"390144\", \"message\": \"JWT token is invalid. [" + problem + "]\"}");
        }
        String path = request.getUrl().encodedPath();
        if (request.getMethod().equals("POST") && path.equals(SnowflakeApi.STATEMENTS)) {
          JsonNode body = JSON.readTree(request.getBody().utf8());
          String sql = body.path("statement").asString("");
          for (Map.Entry<Predicate<String>, Function<JsonNode, MockResponse>> e : statements) {
            if (e.getKey().test(sql)) {
              return e.getValue().apply(body);
            }
          }
          return failure(422, "001003", "42000", "SQL compilation error: no fake for " + sql);
        }
        if (path.startsWith(SnowflakeApi.STATEMENTS + "/")) {
          String handle = path.substring(SnowflakeApi.STATEMENTS.length() + 1).split("/")[0];
          Function<RecordedRequest, MockResponse> h = handles.get(handle);
          if (h != null) {
            return h.apply(request);
          }
        }
        return new MockResponse.Builder().code(404).build();
      }
    });
    try {
      server.start(InetAddress.getByName("127.0.0.1"), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The test key's private half as PKCS#8 PEM, as a person pastes it. */
  static String privateKeyPem() {
    return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }

  /** {@code SHA256:…} of the test key, as {@code DESCRIBE USER} shows RSA_PUBLIC_KEY_FP. */
  static String fingerprint() {
    try {
      return "SHA256:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
          .digest(KEYS.getPublic().getEncoded()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  URI base() {
    return URI.create("http://127.0.0.1:" + server.getPort());
  }

  /** Sign-ins must name this {@code ACCOUNT.USER}. */
  FakeSnowflake subject(String accountDotUser) {
    this.subject = accountDotUser;
    return this;
  }

  /** Sign-ins use this programmatic access token instead of a JWT. */
  FakeSnowflake token(String token) {
    this.token = token;
    return this;
  }

  /** Answers statements that start with {@code prefix} (case ignored); the latest handler that matches wins. */
  FakeSnowflake on(String prefix, Function<JsonNode, MockResponse> answer) {
    return onStatement(sql -> sql.regionMatches(true, 0, prefix, 0, prefix.length()), answer);
  }

  FakeSnowflake onStatement(Predicate<String> sql, Function<JsonNode, MockResponse> answer) {
    statements.addFirst(Map.entry(sql, answer));
    return this;
  }

  /** Answers {@code GET} and {@code POST} requests to {@code /api/v2/statements/<handle>…}. */
  FakeSnowflake onHandle(String handle, Function<RecordedRequest, MockResponse> answer) {
    handles.put(handle, answer);
    return this;
  }

  /** The bodies of the statements sent, in order. */
  List<JsonNode> statementsSent() {
    List<JsonNode> out = new ArrayList<>();
    for (RecordedRequest r : requests) {
      if (r.getMethod().equals("POST") && r.getUrl().encodedPath().equals(SnowflakeApi.STATEMENTS)) {
        out.add(JSON.readTree(r.getBody().utf8()));
      }
    }
    return out;
  }

  List<String> sqlSent() {
    return statementsSent().stream().map(b -> b.path("statement").asString()).toList();
  }

  /** Why a request doesn't sign in as expected, or {@code null}. */
  private String signIn(RecordedRequest r) {
    String agent = r.getHeaders().get("User-Agent");
    if (agent == null || !agent.startsWith("GateRook/")) {
      return "User-Agent " + agent;
    }
    if (!"application/json".equals(r.getHeaders().get("Accept"))) {
      return "Accept " + r.getHeaders().get("Accept");
    }
    String auth = r.getHeaders().get("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      return "no bearer token";
    }
    String bearer = auth.substring(7);
    String type = r.getHeaders().get("X-Snowflake-Authorization-Token-Type");
    if (token != null) {
      if (!"PROGRAMMATIC_ACCESS_TOKEN".equals(type)) {
        return "token type " + type;
      }
      return token.equals(bearer) ? null : "wrong token";
    }
    if (!"KEYPAIR_JWT".equals(type)) {
      return "token type " + type;
    }
    return jwtProblem(bearer, subject);
  }

  /** Why {@code jwt} isn't a key-pair JWT of the test key for {@code subject}, or {@code null}. */
  static String jwtProblem(String jwt, String subject) {
    String[] parts = jwt.split("\\.");
    if (parts.length != 3) {
      return "not a JWT";
    }
    try {
      JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
      if (!"RS256".equals(header.path("alg").asString()) || !"JWT".equals(header.path("typ").asString())) {
        return "header " + header;
      }
      Signature rs256 = Signature.getInstance("SHA256withRSA");
      rs256.initVerify(KEYS.getPublic());
      rs256.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!rs256.verify(Base64.getUrlDecoder().decode(parts[2]))) {
        return "bad signature";
      }
      JsonNode claims = claims(jwt);
      if (!(subject + "." + fingerprint()).equals(claims.path("iss").asString())) {
        return "iss " + claims.path("iss").asString();
      }
      if (!subject.equals(claims.path("sub").asString())) {
        return "sub " + claims.path("sub").asString();
      }
      long now = Instant.now().getEpochSecond();
      long iat = claims.path("iat").asLong();
      long exp = claims.path("exp").asLong();
      if (iat > now + 60 || exp <= now || exp - iat > 3_600) {
        return "lifetime iat=" + iat + " exp=" + exp;
      }
      return null;
    } catch (Exception e) {
      return "unreadable JWT: " + e;
    }
  }

  static JsonNode claims(String jwt) {
    return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }

  // ── Answers ─────────────────────────────────────────────────────────────────────────────────────────────────

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  static MockResponse failure(int status, String code, String sqlState, String message) {
    ObjectNode o = F.objectNode().put("code", code).put("sqlState", sqlState).put("message", message)
        .put("statementHandle", "01b2c3d4-0000-0000-0000-00000000dead");
    return json(status, o.toString());
  }

  /** A column of a result: name and type, with FIXED's scale where it matters. */
  record Col(String name, String type, int scale) {
    static Col of(String name, String type) {
      return new Col(name, type, 0);
    }
  }

  /** A ResultSet of one partition. */
  static MockResponse result(List<Col> columns, List<List<String>> rows) {
    return json(200, resultSet(columns, rows, List.of(rows.size()), "01b2c3d4-0000-0000-0000-000000000001")
        .toString());
  }

  /** A ResultSet whose first partition holds {@code rows}; {@code partitions} are the row counts of all of them. */
  static ObjectNode resultSet(List<Col> columns, List<List<String>> rows, List<Integer> partitions, String handle) {
    ObjectNode out = F.objectNode();
    ObjectNode meta = out.putObject("resultSetMetaData");
    meta.put("numRows", partitions.stream().mapToInt(Integer::intValue).sum());
    meta.put("format", "jsonv2");
    ArrayNode rowType = meta.putArray("rowType");
    for (Col c : columns) {
      rowType.addObject().put("name", c.name()).put("type", c.type()).put("length", c.type().equals("TEXT")
          ? 16_777_216 : 0).put("precision", c.type().equals("FIXED") ? 38 : 0).put("scale", c.scale())
          .put("nullable", true).put("database", "ANALYTICS").put("schema", "PUBLIC").put("table", "ORDERS");
    }
    ArrayNode info = meta.putArray("partitionInfo");
    partitions.forEach(n -> info.addObject().put("rowCount", n).put("uncompressedSize", 100 * n));
    out.set("data", data(rows));
    out.put("code", "090001");
    out.put("statementStatusUrl", SnowflakeApi.STATEMENTS + "/" + handle);
    out.put("sqlState", "00000");
    out.put("statementHandle", handle);
    out.put("message", "Statement executed successfully.");
    out.put("createdOn", 1_731_300_000_000L);
    return out;
  }

  static ArrayNode data(List<List<String>> rows) {
    ArrayNode data = F.arrayNode();
    for (List<String> r : rows) {
      ArrayNode row = data.addArray();
      for (String v : r) {
        if (v == null) {
          row.addNull();
        } else {
          row.add(v);
        }
      }
    }
    return data;
  }

  /** The answer of {@code EXPLAIN USING JSON}: one TEXT cell holding the plan. */
  static MockResponse plan(String json) {
    return result(List.of(Col.of("content", "TEXT")), List.of(java.util.Collections.singletonList(json)));
  }

  /** A plan of a TableScan of each table under a Result. */
  static String scanPlan(String... tables) {
    ObjectNode root = F.objectNode();
    root.putObject("GlobalStats").put("partitionsTotal", 1).put("partitionsAssigned", 1).put("bytesAssigned", 1024);
    ArrayNode step = root.putArray("Operations").addArray();
    step.addObject().put("id", 0).put("operation", "Result").putArray("expressions").add("ID");
    int id = 1;
    for (String t : tables) {
      ObjectNode op = step.addObject().put("id", id++).put("operation", "TableScan");
      op.putArray("parentOperators").add(0);
      op.putArray("objects").add(t);
      op.putArray("expressions").add("ID");
    }
    return root.toString();
  }

  /** {@code body} gzip-compressed with {@code Content-Encoding: gzip}, as later partitions come. */
  static MockResponse gzip(String body) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
      out.write(body.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .addHeader("Content-Encoding", "gzip").body(new Buffer().write(bytes.toByteArray())).build();
  }

  /** A statement Snowflake still runs: 202 with its handle. */
  static MockResponse running(String handle) {
    return json(202, "{\"code\": \"333334\", \"message\": \"Asynchronous execution in progress.\", "
        + "\"statementHandle\": \"" + handle + "\", \"statementStatusUrl\": \"" + SnowflakeApi.STATEMENTS + "/" + handle
        + "\"}");
  }

  @Override
  public void close() {
    server.close();
  }
}
