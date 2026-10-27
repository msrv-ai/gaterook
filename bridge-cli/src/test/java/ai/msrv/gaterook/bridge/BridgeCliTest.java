package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code gw-bridge headers} and {@code stdio} against a fake broker and a fake endpoint, in this JVM. */
class BridgeCliTest {

  private static final String GRANT = "gr_abcdefghijkl";

  @TempDir
  Path home;

  private record Run(int code, String out, String err) {}

  private Run run(Map<String, String> env, String stdin, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Main.run(args, env, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static String issued(int port, String slug, String token) {
    return "{\"v\":1,\"ok\":true,\"token\":\"" + token + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(900)
        + "\",\"url\":\"http://127.0.0.1:" + port + "/mcp/" + slug + "\",\"instance\":\"inst-1\"}";
  }

  @Test
  void headersPrintsOnlyTheAuthorizationHeader() throws IOException {
    try (FakeBroker broker = new FakeBroker(home, r -> issued(7465, "dev", "gwb_0123456789abcdef"))) {
      Run r = run(Map.of("GW_HOME", home.toString()), "", "headers", "--endpoint", "dev", "--grant", GRANT);
      assertThat(r.code()).isZero();
      assertThat(r.out()).isEqualTo("{\"Authorization\":\"Bearer gwb_0123456789abcdef\"}");
      Object request = broker.requests.getFirst();
      assertThat(Json.string(request, "op")).isEqualTo("token");
      assertThat(Json.string(request, "client")).isEqualTo("codex");
      assertThat(Json.string(request, "endpoint")).isEqualTo("dev");
      assertThat(Json.string(request, "grant")).isEqualTo(GRANT);
      assertThat(Json.get(request, "pid")).isInstanceOf(Long.class);
    }
  }

  @Test
  void headersExitCodesSayWhatIsWrongAndTheLogKeepsTheReasonWithoutSecrets() throws IOException {
    Map<String, String> env = Map.of("GW_HOME", home.toString());
    assertThat(run(env, "", "headers", "--endpoint", "dev", "--grant", GRANT).code()).as("no broker").isEqualTo(1);
    assertThat(run(env, "", "headers", "--endpoint", "Dev!", "--grant", GRANT).code()).as("bad slug").isEqualTo(3);
    assertThat(run(env, "", "headers", "--endpoint", "dev").code()).as("no grant").isEqualTo(3);
    assertThat(run(env, "", "headers", "--endpoint").code()).as("no value").isEqualTo(3);
    try (FakeBroker broker = new FakeBroker(home, r -> "{\"v\":1,\"ok\":false,\"error\":\"grant_revoked\","
        + "\"message\":\"Access for Codex to dev was revoked in GateRook. Connect again from the app.\"}")) {
      Run r = run(env, "", "headers", "--endpoint", "dev", "--grant", GRANT);
      assertThat(r.code()).isEqualTo(2);
      assertThat(r.out()).isEmpty();
      assertThat(r.err()).contains("was revoked");
    }
    try (FakeBroker broker = new FakeBroker(home, r -> "{\"v\":1,\"ok\":false,\"error\":\"grant_mismatch\","
        + "\"message\":\"The access gr_abcd… isn't for codex on prod.\"}")) {
      assertThat(run(env, "", "headers", "--endpoint", "prod", "--grant", GRANT).code()).isEqualTo(2);
    }
    try (FakeBroker broker = new FakeBroker(home, r -> "{\"v\":1,\"ok\":false,\"error\":\"core_unavailable\","
        + "\"message\":\"GateRook is not running.\"}")) {
      assertThat(run(env, "", "headers", "--endpoint", "dev", "--grant", GRANT).code()).isEqualTo(1);
    }
    Path log = home.resolve("logs").resolve("bridge.log");
    String text = Files.readString(log);
    assertThat(text).contains("grant_revoked", "grant_mismatch", "not running").contains("gr_abcd…")
        .doesNotContain(GRANT).doesNotContain("gwb_");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log))).isEqualTo("rw-------");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log.getParent()))).isEqualTo("rwx------");
  }

  @Test
  void theLogCutsOutAnythingShapedLikeAToken() {
    BridgeLog log = new BridgeLog(home);
    log.write("test", "saw gwb_aaaaaaaaaaaaaaaaaaaa and gwt_bbbbbbbbbbbb for " + GRANT);
    assertThat(log.file()).content().contains("‹token›").doesNotContain("gwb_aaa").doesNotContain("gwt_bbb")
        .contains("gr_abcd…");
  }

  @Test
  void stdioBridgesLinesToTheEndpointAndRotatesTheTokenOn401() throws Exception {
    AtomicInteger tokens = new AtomicInteger();
    List<String> auth = new CopyOnWriteArrayList<>();
    List<String> versions = new CopyOnWriteArrayList<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/mcp/dev", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String a = ex.getRequestHeaders().getFirst("Authorization");
      auth.add(a);
      versions.add(String.valueOf(ex.getRequestHeaders().getFirst("MCP-Protocol-Version")));
      ex.getResponseHeaders().add("X-Gateway-Bridge-Instance", "inst-1");
      if (!("Bearer gwb_t" + tokens.get()).equals(a)) {
        ex.sendResponseHeaders(401, -1);
        ex.close();
        return;
      }
      Object msg = Json.parse(body);
      if (!(msg instanceof Map<?, ?> m) || !m.containsKey("id")) {
        ex.sendResponseHeaders(202, -1);
        ex.close();
        return;
      }
      String method = Json.string(msg, "method");
      String result = "initialize".equals(method) ? "{\"protocolVersion\":\"2025-11-25\"}" : "{\"tools\":[]}";
      byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(Json.get(msg, "id")) + ",\"result\":" + result + "}")
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    http.start();
    int port = http.getAddress().getPort();
    try (FakeBroker broker = new FakeBroker(home, r -> issued(port, "dev", "gwb_t" + tokens.incrementAndGet()))) {
      // The first line is answered with token 1; then the endpoint forgets it (rotation), the bridge asks again.
      String stdin = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";
      Run first = run(Map.of("GW_HOME", home.toString()), stdin, "stdio", "--endpoint", "dev", "--grant", GRANT,
          "--client", "cursor");
      assertThat(first.code()).isZero();
      assertThat(first.out()).contains("\"protocolVersion\":\"2025-11-25\"");
      assertThat(Json.string(broker.requests.getFirst(), "client")).isEqualTo("cursor");

      StdioBridge bridge = new StdioBridge("dev", GRANT, "cursor", BrokerClient.fromEnvironment(Map.of("GW_HOME",
          home.toString())), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      StdioBridge capturing = new StdioBridge("dev", GRANT, "cursor", BrokerClient.fromEnvironment(Map.of("GW_HOME",
          home.toString())), new PrintStream(captured, true, StandardCharsets.UTF_8));
      capturing.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
      int before = tokens.get();
      tokens.incrementAndGet();
      capturing.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
      assertThat(tokens.get()).as("one new token after the 401").isEqualTo(before + 2);
      assertThat(captured.toString(StandardCharsets.UTF_8)).contains("\"id\":2,\"result\":{\"tools\":[]}");
      assertThat(versions.getLast()).isEqualTo("2025-11-25");
      capturing.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
      assertThat(captured.toString(StandardCharsets.UTF_8).lines().count()).as("no answer to a notification")
          .isEqualTo(2);
      bridge.handle("not json");
    } finally {
      http.stop(0);
    }
  }

  /** An endpoint that answers request {@code id} with {@code result(id)} as core run {@code run(id)}; counts by id. */
  private static HttpServer endpoint(Map<Object, AtomicInteger> seen, Function<Object, String> run,
      Function<Object, String> result) throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/mcp/dev", ex -> {
      Object msg = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      Object id = Json.get(msg, "id");
      seen.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
      byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(id) + ",\"result\":" + result.apply(id) + "}")
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("X-Gateway-Bridge-Instance", run.apply(id));
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    http.start();
    return http;
  }

  @Test
  void stdioFollowsTheCoreToAnotherPortAndIntoItsNextRunSendingNothingTwice() throws Exception {
    Map<Object, AtomicInteger> seen = new ConcurrentHashMap<>();
    HttpServer moved = endpoint(seen, id -> "inst-1", id -> "{\"port\":\"old\"}");
    // Request 3 reaches the core's next run, which has another id.
    HttpServer current = endpoint(seen, id -> Long.valueOf(3).equals(id) ? "run-2" : "inst-1",
        id -> Long.valueOf(3).equals(id) ? "{\"run\":2}" : "{\"tools\":[]}");
    int oldPort = moved.getAddress().getPort();
    int port = current.getAddress().getPort();
    List<String> answers = List.of(issued(oldPort, "dev", "gwb_a"), issued(port, "dev", "gwb_b"),
        issued(port, "dev", "gwb_c").replace("inst-1", "run-2"));
    AtomicInteger asked = new AtomicInteger();
    try (FakeBroker broker = new FakeBroker(home, r -> answers.get(Math.min(asked.getAndIncrement(), 2)))) {
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      StdioBridge bridge = new StdioBridge("dev", GRANT, "cursor", BrokerClient.fromEnvironment(Map.of("GW_HOME",
          home.toString())), new PrintStream(captured, true, StandardCharsets.UTF_8));
      bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
      // The core moves to another port: the kept token's port refuses, the broker names the current URL, once.
      moved.stop(0);
      bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
      // The core's next run accepts the token but answers with a new run id, which the broker's next token carries.
      bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
      assertThat(captured.toString(StandardCharsets.UTF_8).lines().toList()).containsExactly(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"port\":\"old\"}}",
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}",
          "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"run\":2}}");
      assertThat(seen.get(2L).get()).isEqualTo(1);
      assertThat(seen.get(3L).get()).as("not sent again").isEqualTo(1);
      assertThat(asked.get()).isEqualTo(3);
    } finally {
      moved.stop(0);
      current.stop(0);
    }
  }

  @Test
  void stdioCallsThatMeetA401TogetherShareOneNewToken() throws Exception {
    AtomicInteger issuedCount = new AtomicInteger();
    AtomicReference<String> accepted = new AtomicReference<>("Bearer gwb_t1");
    Map<Object, AtomicInteger> seen = new ConcurrentHashMap<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    http.createContext("/mcp/dev", ex -> {
      Object id = Json.get(Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)), "id");
      ex.getResponseHeaders().add("X-Gateway-Bridge-Instance", "inst-1");
      if (!accepted.get().equals(ex.getRequestHeaders().getFirst("Authorization"))) {
        ex.sendResponseHeaders(401, -1);
        ex.close();
        return;
      }
      seen.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
      byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(id) + ",\"result\":{}}").getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    http.start();
    int port = http.getAddress().getPort();
    try (FakeBroker broker = new FakeBroker(home, r -> issued(port, "dev", "gwb_t" + issuedCount.incrementAndGet()))) {
      StdioBridge bridge = new StdioBridge("dev", GRANT, "cursor", BrokerClient.fromEnvironment(Map.of("GW_HOME",
          home.toString())), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
      bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/list\"}");
      // The token expires: five calls at once meet a 401, and the first new token serves them all.
      accepted.set("Bearer gwb_t2");
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (long i = 1; i <= 5; i++) {
          String line = "{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"tools/list\"}";
          pool.submit(() -> bridge.handle(line));
        }
      }
      assertThat(broker.requests).hasSize(2);
      assertThat(seen.keySet()).containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 4L, 5L);
    } finally {
      http.stop(0);
    }
  }

  @Test
  void stdioAnswersErrorsWithTheRequestIdAndRefusesForeignUrls() throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
    BrokerClient none = BrokerClient.fromEnvironment(Map.of("GW_HOME", home.toString()));
    new StdioBridge("dev", GRANT, "cursor", none, out).handle("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"x\"}");
    assertThat(captured.toString(StandardCharsets.UTF_8)).contains("\"id\":\"a\"")
        .contains(StdioBridge.NOT_RUNNING);
    captured.reset();
    try (FakeBroker broker = new FakeBroker(home, r -> "{\"v\":1,\"ok\":true,\"token\":\"gwb_x\",\"expiresAt\":\""
        + Instant.now().plusSeconds(900) + "\",\"url\":\"http://evil.example:80/mcp/dev\"}")) {
      new StdioBridge("dev", GRANT, "cursor", BrokerClient.fromEnvironment(Map.of("GW_HOME", home.toString())), out)
          .handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"x\"}");
      assertThat(captured.toString(StandardCharsets.UTF_8)).contains("\"id\":3").contains("doesn't accept");
    }
    assertThat(StdioBridge.headerName("sql_query")).isEqualTo("sql_query");
    assertThat(StdioBridge.headerName("café")).startsWith("=?base64?").endsWith("?=");
    assertThat(StdioBridge.sseData("data: a\ndata: b\n\ndata: c\n")).containsExactly("a\nb", "c");
  }

  @Test
  void jsonRoundTrips() {
    Object v = Json.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\",true,null],\"b\":{}}");
    assertThat(Json.write(v)).isEqualTo("{\"a\":[1,2.5,\"x\\né\",true,null],\"b\":{}}");
    assertThatThrownBy(() -> Json.parse("{\"a\":1} x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parse("[".repeat(100))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownCommandsAndVersion() {
    assertThat(run(Map.of(), "", "steal").code()).isEqualTo(3);
    assertThat(run(Map.of(), "").code()).isEqualTo(3);
    Run v = run(Map.of(), "", "--version");
    assertThat(v.code()).isZero();
    assertThat(v.out().strip()).isEqualTo(Main.VERSION);
  }
}
