package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.db.SqlitePools;
import ai.msrv.gaterook.core.platform.web.LocalGuardFilter;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The platform as one running core (report 14 §8, ARCHITECTURE.md M1.2): the local listener's guard (421, 403, 401,
 * 404, 415 and what passes), the pragmas of both SQLite pools, read-only routing of transactions, the audit triggers,
 * 400 parallel writes next to readers, and the event stream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIT {

  @DynamicPropertySource
  static void gateway(DynamicPropertyRegistry registry) {
    TestGateway.register(registry, "platform");
    registry.add("gateway.events.heartbeat", () -> "300ms");
  }

  @LocalServerPort
  int port;

  @Autowired
  SqlitePools pools;

  JdbcTemplate jdbc;

  /** The core has no JdbcTemplate bean (JPA only, decision 3): the test makes its own over the primary pool. */
  @Autowired
  void jdbc(javax.sql.DataSource dataSource) {
    jdbc = new JdbcTemplate(dataSource);
  }

  @Autowired
  PlatformTransactionManager transactions;

  @Autowired
  SettingStore settings;

  @Autowired
  EventBus events;

  @Autowired
  Bootstrap bootstrap;

  @Autowired
  org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor;

  /** The scope of TransactionAdvisorScope is on the advisor the context uses, not just possible (M1.7). */
  @Test
  void theTransactionAdvisorLooksOnlyAtGatewayClassesThatDeclareTransactions() {
    org.springframework.aop.ClassFilter filter = transactionAdvisor.getPointcut().getClassFilter();
    assertThat(filter.matches(SettingStore.class)).isTrue();
    assertThat(filter.matches(EventBus.class)).as("a gateway class without @Transactional").isFalse();
    assertThat(filter.matches(JdbcTemplate.class)).as("a framework class").isFalse();
  }

  private TestHttp http() {
    return new TestHttp(port, TestGateway.launchSecret());
  }

  // ── The local listener ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aForeignHostIsMisdirected() {
    TestHttp http = http();

    assertThat(http.raw("GET", "/api/events", Map.of("Host", "evil.example:" + port)).status()).isEqualTo(421);
    assertThat(http.raw("POST", "/mcp/dev", Map.of("Host", "abc.trycloudflare.com")).status()).isEqualTo(421);
    assertThat(http.raw("GET", "/api/events", Map.of("Host", "127.0.0.1:1")).status()).isEqualTo(421);
    assertThat(http.raw("GET", "/api/events", Map.of("Host", "localhost:" + port)).status())
        .as("localhost passes the host check, then needs a signature").isEqualTo(401);
  }

  @Test
  void aForeignOriginIsForbiddenWithAJsonRpcErrorOnMcp() {
    TestHttp http = http();

    TestHttp.Raw mcp = http.raw("POST", "/mcp/dev", Map.of("Origin", "https://evil.example"));
    assertThat(mcp.status()).isEqualTo(403);
    assertThat(mcp.headers().get("content-type")).startsWith("application/json");
    assertThat(mcp.body()).isEqualTo("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Origin not allowed\"}}");
    assertThat(http.raw("GET", "/api/events", Map.of("Origin", "null")).status()).isEqualTo(403);
    assertThat(mcp.headers()).doesNotContainKey("access-control-allow-origin");
  }

  @Test
  void theAdminApiNeedsAFreshSingleUseSignature() {
    TestHttp http = http();

    assertThat(http.send(http.request("/api/events").GET().build()).statusCode()).isEqualTo(401);
    assertThat(http.send(http.request("/internal/bridge/tokens").POST(HttpRequest.BodyPublishers.noBody()).build())
        .statusCode()).isEqualTo(401);
    String stale = TestGateway.launchSecret().sign("GET", "/api/x", System.currentTimeMillis() - 60_000,
        TestHttp.nonce());
    assertThat(http.send(http.request("/api/x").header(LaunchSecret.HEADER, stale).GET().build()).statusCode())
        .isEqualTo(401);
    String forOtherPath = http.sign("GET", "/api/y");
    assertThat(http.send(http.request("/api/x").header(LaunchSecret.HEADER, forOtherPath).GET().build())
        .statusCode()).isEqualTo(401);

    String once = http.sign("GET", "/api/nothing-here?a=1");
    HttpResponse<String> first = http.send(http.request("/api/nothing-here?a=1").header(LaunchSecret.HEADER, once)
        .GET().build());
    HttpResponse<String> replay = http.send(http.request("/api/nothing-here?a=1").header(LaunchSecret.HEADER, once)
        .GET().build());
    assertThat(first.statusCode()).as("signed: past the guard, no such handler").isEqualTo(404);
    assertThat(first.headers().firstValue(LocalGuardFilter.INSTANCE_HEADER)).contains(bootstrap.instanceNonce());
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(first.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(replay.statusCode()).as("a nonce is single-use").isEqualTo(401);
    assertThat(replay.headers().firstValue(LocalGuardFilter.INSTANCE_HEADER))
        .as("the instance nonce only for authenticated callers").isEmpty();
  }

  @Test
  void browsersAndNonJsonBodiesAreRefused() {
    TestHttp http = http();

    HttpResponse<String> crossSite = http.send(http.request("/api/x").header("Sec-Fetch-Site", "cross-site")
        .header(LaunchSecret.HEADER, http.sign("GET", "/api/x")).GET().build());
    assertThat(crossSite.statusCode()).isEqualTo(403);
    HttpResponse<String> form = http.send(http.request("/api/x").header("Content-Type", "text/plain")
        .header(LaunchSecret.HEADER, http.sign("POST", "/api/x")).POST(HttpRequest.BodyPublishers.ofString("a=b"))
        .build());
    assertThat(form.statusCode()).isEqualTo(415);
    HttpResponse<String> json = http.send(http.request("/api/x").header("Content-Type", "application/json")
        .header(LaunchSecret.HEADER, http.sign("POST", "/api/x")).POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build());
    assertThat(json.statusCode()).as("JSON passes the guard").isNotIn(401, 403, 415, 421);
    HttpResponse<String> dev = http.send(http.request("/api/x").header("Origin", "http://localhost:4200")
        .header(LaunchSecret.HEADER, http.sign("GET", "/api/x")).GET().build());
    assertThat(dev.statusCode()).as("ng serve's origin in a dev run").isEqualTo(404);
  }

  @Test
  void everythingOutsideTheAllowlistIsNotFound() {
    TestHttp http = http();

    for (String target : List.of("/", "/error", "/actuator", "/actuator/health", "/index.html", "/api",
        "/api//events", "/api/../api/events", "/api/%2e%2e/events", "/mcp/dev/", "/mcp/dev;x=1",
        "/approvals/ap_1", "/.well-known/openid-configuration")) {
      TestHttp.Raw response = http.raw("GET", target, Map.of(LaunchSecret.HEADER, http.sign("GET", target)));
      assertThat(response.status()).as(target).isEqualTo(404);
      assertThat(response.body()).as(target).isEmpty();
    }
  }

  @Test
  void requestsTomcatRejectsItselfRevealNoServerDetails() {
    TestHttp http = http();

    // Bad escapes are refused by Tomcat before any filter runs: its error page must not name it or its version.
    for (String target : List.of("/api/%zz", "/mcp/%")) {
      TestHttp.Raw response = http.raw("GET", target, Map.of());
      assertThat(response.status()).as(target).isEqualTo(400);
      assertThat(response.body()).as(target).doesNotContainIgnoringCase("tomcat").doesNotContain("Exception");
      assertThat(response.headers()).as(target).doesNotContainKey("server");
    }
  }

  // ── The database ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void everyPragmaIsSetOnBothPools() throws SQLException {
    assertThat(pragmas(pools.writer())).containsAllEntriesOf(Map.of("journal_mode", "wal", "synchronous", "2",
        "busy_timeout", "5000", "foreign_keys", "1", "secure_delete", "1", "query_only", "0"));
    DataSource reader = pools.reader().orElseThrow();
    // Every connection of the reader pool, not just the first one Hikari opened.
    List<Connection> held = new ArrayList<>();
    try {
      for (int i = 0; i < SqlitePools.MAX_READER_POOL_SIZE; i++) {
        Connection connection = reader.getConnection();
        held.add(connection);
        assertThat(pragmas(connection)).containsAllEntriesOf(Map.of("journal_mode", "wal", "synchronous", "2",
            "busy_timeout", "5000", "foreign_keys", "1", "secure_delete", "1", "query_only", "1"));
      }
    } finally {
      for (Connection connection : held) {
        connection.close();
      }
    }
  }

  @Test
  void readOnlyTransactionsRunOnTheReaderAndCannotWrite() {
    TransactionTemplate readOnly = new TransactionTemplate(transactions);
    readOnly.setReadOnly(true);
    TransactionTemplate write = new TransactionTemplate(transactions);

    Integer inReadOnly = readOnly.execute(s -> jdbc.queryForObject("PRAGMA query_only", Integer.class));
    Integer inWrite = write.execute(s -> jdbc.queryForObject("PRAGMA query_only", Integer.class));
    assertThat(inReadOnly).isEqualTo(1);
    assertThat(inWrite).isZero();
    assertThatThrownBy(() -> readOnly.executeWithoutResult(s -> jdbc.update(
        "insert into setting (key, value, version, updated_at) values ('x.readonly', '1', 0, 0)")))
        .hasRootCauseInstanceOf(SQLiteException.class)
        .rootCause().satisfies(e -> assertThat(((SQLiteException) e).getResultCode())
            .isEqualTo(SQLiteErrorCode.SQLITE_READONLY));
    assertThat(settings.find("x.readonly")).isEmpty();
  }

  @Test
  void theAuditIsAppendOnlyAndKeptAYear() throws SQLException {
    long now = Instant.now().toEpochMilli();
    long old = Instant.now().minus(Duration.ofDays(400)).toEpochMilli();
    try (Connection connection = pools.writer().getConnection(); Statement sql = connection.createStatement()) {
      sql.executeUpdate("insert into tool_call (call_id, phase, created_at, hour_bucket, endpoint_slug, tool_name, "
          + "decision, prev_sha256, row_sha256) values ('tc_new', 'received', " + now + ", 1, 'dev', 'sql_query', "
          + "'allowed', 'p', 'r')");
      sql.executeUpdate("insert into tool_call (call_id, phase, created_at, hour_bucket, endpoint_slug, tool_name, "
          + "decision, prev_sha256, row_sha256) values ('tc_old', 'received', " + old + ", 1, 'dev', 'sql_query', "
          + "'allowed', 'p', 'r')");
      sql.executeUpdate("insert into setup_change (created_at, what, subject, by_actor, prev_sha256, row_sha256) "
          + "values (" + now + ", 'mode', 'sql_execute on dev-db', 'you', 'p', 'r')");

      assertTrigger(() -> sql.executeUpdate("update tool_call set decision = 'denied' where call_id = 'tc_new'"),
          "audit is append-only");
      assertTrigger(() -> sql.executeUpdate("update tool_call set decision = 'denied' where call_id = 'tc_old'"),
          "audit is append-only");
      assertTrigger(() -> sql.executeUpdate("delete from tool_call where call_id = 'tc_new'"),
          "audit is kept at least 1 year");
      assertTrigger(() -> sql.executeUpdate("update setup_change set reason = 'x'"), "audit is append-only");
      assertTrigger(() -> sql.executeUpdate("delete from setup_change"), "audit is kept at least 1 year");
      assertThat(sql.executeUpdate("delete from tool_call where call_id = 'tc_old'"))
          .as("retention may delete rows older than a year").isEqualTo(1);
    }
  }

  @Test
  void fourHundredParallelWritesNextToReadersAllSucceed() throws Exception {
    AtomicInteger readsDone = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    long started = System.nanoTime();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 400; i++) {
        String key = "load.write-" + i;
        futures.add(threads.submit(() -> {
          // Read, then write in one transaction: the pattern DEFERRED transactions fail with SQLITE_BUSY_SNAPSHOT.
          new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.queryForObject("select count(*) from setting", Integer.class);
            settings.put(key, JsonNodeFactory.instance.objectNode().put("i", key), null);
          });
        }));
      }
      for (int i = 0; i < 100; i++) {
        futures.add(threads.submit(() -> {
          TransactionTemplate readOnly = new TransactionTemplate(transactions);
          readOnly.setReadOnly(true);
          readOnly.executeWithoutResult(s -> jdbc.queryForObject(
              "with recursive n(x) as (select 1 union all select x + 1 from n where x < 20000) select count(*) from n",
              Integer.class));
          readsDone.incrementAndGet();
        }));
      }
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    }
    assertThat(jdbc.queryForObject("select count(*) from setting where key like 'load.write-%'", Integer.class))
        .isEqualTo(400);
    assertThat(readsDone).hasValue(100);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
  }

  // ── Events ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theEventStreamDeliversEventsHeartbeatsAndReplays() throws Exception {
    TestHttp http = http();
    LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    HttpRequest request = http.request("/api/events").header(LaunchSecret.HEADER, http.sign("GET", "/api/events"))
        .timeout(Duration.ofMinutes(1)).GET().build();
    HttpResponse<Stream<String>> response = http.client().send(request, HttpResponse.BodyHandlers.ofLines());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
        type -> assertThat(type).startsWith("text/event-stream"));
    Thread reader = Thread.ofVirtual().start(() -> response.body().forEach(lines::add));
    try {
      assertThat(lines.poll(5, TimeUnit.SECONDS)).isEqualTo(": stream open");
      long id = events.publish("approval.requested", JsonNodeFactory.instance.objectNode().put("id", "ap_x")).id();
      List<String> frame = nextFrame(lines);
      assertThat(frame).containsExactly("id: " + id, "event: approval.requested", "data: {\"id\":\"ap_x\"}");
      assertThat(nextFrame(lines)).as("heartbeat").containsExactly(": heartbeat");
    } finally {
      reader.interrupt();
      response.body().close();
    }

    long before = events.lastId();
    events.publish("connection.health", JsonNodeFactory.instance.objectNode().put("n", 1));
    HttpRequest resume = http.request("/api/events").header(LaunchSecret.HEADER, http.sign("GET", "/api/events"))
        .header("Last-Event-ID", Long.toString(before)).timeout(Duration.ofMinutes(1)).GET().build();
    HttpResponse<Stream<String>> resumed = http.client().send(resume, HttpResponse.BodyHandlers.ofLines());
    LinkedBlockingQueue<String> more = new LinkedBlockingQueue<>();
    Thread second = Thread.ofVirtual().start(() -> resumed.body().forEach(more::add));
    try {
      List<String> replayed = nextFrame(more);
      while (replayed.getFirst().startsWith(":") || replayed.getFirst().startsWith("retry")) {
        replayed = nextFrame(more);
      }
      assertThat(replayed).containsExactly("id: " + (before + 1), "event: connection.health", "data: {\"n\":1}");
    } finally {
      second.interrupt();
      resumed.body().close();
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

  private static List<String> nextFrame(LinkedBlockingQueue<String> lines) throws InterruptedException {
    List<String> frame = new ArrayList<>();
    while (true) {
      String line = lines.poll(10, TimeUnit.SECONDS);
      assertThat(line).as("a line of the stream within 10 s").isNotNull();
      if (line.isEmpty()) {
        if (!frame.isEmpty()) {
          return frame;
        }
        continue;
      }
      if (line.startsWith("retry:")) {
        continue;
      }
      frame.add(line);
    }
  }

  private static Map<String, String> pragmas(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return pragmas(connection);
    }
  }

  private static Map<String, String> pragmas(Connection connection) throws SQLException {
    Map<String, String> values = new java.util.HashMap<>();
    try (Statement sql = connection.createStatement()) {
      for (String pragma : List.of("journal_mode", "synchronous", "busy_timeout", "foreign_keys", "secure_delete",
          "query_only")) {
        try (var rows = sql.executeQuery("PRAGMA " + pragma)) {
          rows.next();
          values.put(pragma, rows.getString(1));
        }
      }
    }
    return values;
  }

  private interface SqlAction {
    void run() throws SQLException;
  }

  private static void assertTrigger(SqlAction action, String message) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLiteException.class, e -> {
      assertThat(e.getResultCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER);
      assertThat(e.getMessage()).contains(message);
    });
  }
}
