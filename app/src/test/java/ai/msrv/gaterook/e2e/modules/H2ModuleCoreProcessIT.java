package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * H2 as the connector module a release downloads (docs/connector-delivery.md): the package of the {@code h2}
 * module, installed by the assembled core when the type's form is asked for, and run in a module host whose TCP
 * gate carries the H2 client's sockets. The core fills the module's TCP destination with the connection's
 * host and port; the gate refuses the server on this computer until the environment's policy allows its address
 * (the test says so, with the suggestion the policy API takes), and then an agent's query reaches the server. A
 * change waits for a person, who approves the statement as the connector itself wrote it down, and runs once.
 *
 * <p>The module's source lives in the modules repository: the test takes its package from a checkout of it
 * ({@link BuiltModule#modulesRepo}) and is skipped without one. The server is H2's own, started from the module's
 * package: it needs no test service.
 */
class H2ModuleCoreProcessIT {

  private static final String READER_PASSWORD = "cnry-h2-reader-4c2d";
  private static BuiltModule module;
  private static Path h2Jar;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    h2Jar = BuiltModule.packageOf("h2");
    assumeTrue(h2Jar != null, BuiltModule.howToBuild("h2"));
    module = BuiltModule.start(home, h2Jar);
  }

  @AfterAll
  static void stop() {
    if (module != null) {
      module.close();
    }
  }

  @Test
  void theModulesSocketsReachTheServerThroughTheTcpGateOnceThePolicyAllowsIt(@TempDir Path h2Dir) throws Exception {
    Path script = Files.writeString(h2Dir.resolve("init.sql"), """
        CREATE TABLE ORDERS (ID INT PRIMARY KEY, TOTAL DECIMAL(10, 2));
        INSERT INTO ORDERS VALUES (1, 10.50), (2, 99.00);
        CREATE USER READER PASSWORD '%s';
        GRANT SELECT, INSERT ON ORDERS TO READER;
        """.formatted(READER_PASSWORD));
    Process h2 = h2Server(h2Dir);
    try {
      int port = h2Port(h2);
      runScript("jdbc:h2:tcp://127.0.0.1:" + port + "/e2e", script);

      JsonNode card = module.api.card("h2");
      assertThat(card.path("kind").asString()).isEqualTo("built-in");
      assertThat(card.path("tools").asInt()).isEqualTo(4);
      assertThat(card.at("/module/installed").asBoolean()).isFalse();
      JsonNode form = module.api.get("/api/connector-types/h2/form");
      assertThat(form.at("/schema/properties/host").isObject()).as(form.toString()).isTrue();
      assertThat(module.api.module("h2").path("state").asString()).isEqualTo("ready");

      module.api.json(module.api.admin("POST", "/api/connections", """
          {"name":"orders-h2","type":"h2","env":"dev",
           "config":{"host":"127.0.0.1","port":%d,"database":"e2e","user":"READER"},
           "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(port, READER_PASSWORD)), 201);

      JsonNode refused = module.api.json(module.api.admin("POST", "/api/connections/orders-h2/test", null), 200);
      assertThat(refused.path("ok").asBoolean()).as(refused.toString()).isFalse();
      assertThat(refused.path("error").path("code").asString()).as(refused.toString())
          .isEqualTo("tcp.private-address");
      assertThat(refused.path("suggestedPolicyChange").path("cidr").asString()).isEqualTo("127.0.0.1/32");

      module.api.json(module.api.admin("POST", "/api/connections/orders-h2/policy-suggestion",
          "{\"kind\":\"allow-private\",\"cidr\":\"127.0.0.1/32\",\"reason\":\"the e2e H2 server\"}"), 200);
      JsonNode ok = module.api.json(module.api.admin("POST", "/api/connections/orders-h2/test", null), 200);
      assertThat(ok.path("ok").asBoolean()).as(ok.toString()).isTrue();
      assertThat(ok.path("serverVersion").asString()).startsWith("H2 2.");

      module.api.endpoint("orders", "orders-h2");
      // One of GateRook's own types: its reads run at once, its change waits for a person, and nobody else is named
      // as the one who declared them.
      assertThat(module.api.modes("orders", "orders-h2")).containsEntry("h2_query", "READ_ONLY")
          .containsEntry("h2_list_tables", "READ_ONLY").containsEntry("h2_describe_table", "READ_ONLY")
          .containsEntry("h2_execute", "APPROVAL").hasSize(4);
      assertThat(module.api.tool("orders", "orders-h2", "h2_query").path("declaredBy").isNull()).isTrue();

      JsonNode rows = module.api.call(2, "h2_query", "{\"connection\":\"orders-h2\",\"sql\":"
          + "\"SELECT ID, TOTAL FROM ORDERS ORDER BY ID\"}");
      assertThat(rows.at("/result/isError").asBoolean()).as(rows.toString()).isFalse();
      assertThat(rows.at("/result/content/0/text").asString()).as(rows.toString()).contains("10.50").contains("99.00");

      // A change waits for a person. What they approve is the connector's own exact action, the statement, and the
      // approved call runs that very statement once.
      String insert = "INSERT INTO ORDERS VALUES (3, 7.25)";
      JsonNode pending = module.api.call(3, "h2_execute", "{\"connection\":\"orders-h2\",\"sql\":\"" + insert
          + "\",\"note\":\"a third order\"}");
      String handle = pending.at("/result/structuredContent/handle").asString();
      assertThat(handle).as(pending.toString()).startsWith("ap_");
      JsonNode detail = module.api.get("/api/approvals/" + handle);
      assertThat(detail.at("/action/kind").asString()).as(detail.toString()).isEqualTo("sql");
      assertThat(detail.at("/action/text").asString()).isEqualTo(insert);
      assertThat(detail.toString()).as("GateRook's own connector: nobody else described it")
          .doesNotContain("Described by");
      JsonNode done = module.api.approve(4, handle);
      assertThat(done.at("/result/structuredContent/status").asString()).as(done.toString()).isEqualTo("completed");
      JsonNode count = module.api.call(5, "h2_query", "{\"connection\":\"orders-h2\",\"sql\":"
          + "\"SELECT TOTAL FROM ORDERS WHERE ID = 3\"}");
      assertThat(count.at("/result/content/0/text").asString()).as(count.toString()).contains("7.25");
      assertThat(module.core.coreLog()).doesNotContain(READER_PASSWORD);
    } finally {
      h2.descendants().forEach(ProcessHandle::destroyForcibly);
      h2.destroyForcibly();
    }
  }

  /** An H2 TCP server on this computer, from the module's own package, on a free port it prints. */
  private static Process h2Server(Path baseDir) throws IOException {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    return new ProcessBuilder(java.toString(), "-cp", h2Jar.toString(), "org.h2.tools.Server", "-tcp", "-tcpPort",
        "0", "-ifNotExists", "-baseDir", baseDir.toString()).redirectErrorStream(true).start();
  }

  /** The port the server says it listens on ("TCP server running at tcp://…:port"). */
  private static int h2Port(Process h2) throws IOException {
    java.io.BufferedReader out = new java.io.BufferedReader(new java.io.InputStreamReader(h2.getInputStream(),
        StandardCharsets.UTF_8));
    String line;
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < until && (line = out.readLine()) != null) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("tcp://[^\\s]*:(\\d+)").matcher(line);
      if (m.find()) {
        Thread.ofVirtual().start(() -> {
          try {
            out.transferTo(java.io.Writer.nullWriter());
          } catch (IOException ignored) {
            // The server ended.
          }
        });
        return Integer.parseInt(m.group(1));
      }
    }
    throw new AssertionError("The H2 server didn't say its port");
  }

  /** Runs {@code script} on {@code url} as the administrator, which creates the database (-ifNotExists). */
  private static void runScript(String url, Path script) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Process p = new ProcessBuilder(java.toString(), "-cp", h2Jar.toString(), "org.h2.tools.RunScript", "-url", url,
        "-user", "sa", "-password", "cnry-h2-admin-7e1a", "-script", script.toString()).redirectErrorStream(true)
        .start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor()).as(output).isZero();
  }
}
