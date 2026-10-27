package ai.msrv.gaterook.testfixtures;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A whole core in this JVM with the fake connectors, on its own home under {@code target/}. */
public final class TestCore implements AutoCloseable {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  private final ConfigurableApplicationContext context;
  private final Path home;

  private TestCore(ConfigurableApplicationContext context, Path home) {
    this.context = context;
    this.home = home;
  }

  public static TestCore start(String name, String... extraArgs) {
    return reopen(TestGateway.newHome(name), extraArgs);
  }

  /** A core on an existing home: what the next start of the same installation sees. */
  public static TestCore reopen(Path home, String... extraArgs) {
    List<String> args = new ArrayList<>(List.of("--gateway.home=" + home, "--gateway.dev=true",
        "--gateway.dev.launch-secret=" + TestGateway.LAUNCH_SECRET, "--server.port=0",
        "--gateway.connections.health-interval=0s"));
    // Clients are looked for in a home of the test's own (Overview reads the Clients cards): never this user's.
    if (java.util.Arrays.stream(extraArgs).noneMatch(a -> a.startsWith("--gateway.clients.user-home="))) {
      args.add("--gateway.clients.user-home=" + home.resolve("user-home"));
    }
    args.addAll(List.of(extraArgs));
    // The launcher's contexts, with every answer of the by-type cache checked against Spring's own.
    ConfigurableApplicationContext context = new SpringApplicationBuilder(GatewayApplication.class)
        .sources(FakeConnectors.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run(args.toArray(String[]::new));
    return new TestCore(context, home);
  }

  public <T> T bean(Class<T> type) {
    return context.getBean(type);
  }

  public ConfigurableApplicationContext context() {
    return context;
  }

  public Path home() {
    return home;
  }

  public JdbcTemplate jdbc() {
    return new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
  }

  /** A connection of a fake type with a password; {@code host} chooses how its test behaves. */
  public ConnectionService.Summary connection(String name, String type, Env env, String host) {
    return bean(ConnectionService.class).create(name, type, env, config(host), Map.of("/credentials/password",
        SecretInput.local(("pw-" + name + "-0123").toCharArray())), "test");
  }

  public static ObjectNode config(String host) {
    ObjectNode c = JSON.createObjectNode();
    c.putObject("connection").put("host", host).put("port", 5432);
    c.putObject("credentials").put("user", "app");
    return c;
  }

  public static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  /** Waits up to 10 s for {@code condition} (async listeners). */
  public static void await(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  /** A pending approval request for the history tests (the approvals module writes these from M1.5 on). */
  public String pendingApproval(String endpointId, String slug, String connectionId, String connection, String tool) {
    return pendingApproval(endpointId, slug, connectionId, connection, tool, "tok_test");
  }

  /** A pending request of {@code principalId} (a named token's or a grant's id). */
  public String pendingApproval(String endpointId, String slug, String connectionId, String connection, String tool,
      String principalId) {
    String id = ai.msrv.gaterook.core.platform.Ids.next("ap_");
    long now = Instant.now().toEpochMilli();
    jdbc().update("insert into approval (id, call_id, endpoint_id, endpoint_slug, connection_id, connection_name, "
        + "tool, tool_key, env, principal_kind, principal_id, call_canonical, digest, action, action_sha256, rule_id, "
        + "comment_policy, status, expires_at, created_at, version) values (?, ?, ?, ?, ?, ?, ?, ?, 'dev', "
        + "'LOCAL_TOKEN', ?, '{}', 'd', '{}', 'a', 'dev.sql.write-approval', 'optional', 'pending', ?, ?, 0)",
        id, ai.msrv.gaterook.core.platform.Ids.next("tc_"), endpointId, slug, connectionId, connection, tool, tool,
        principalId, now + 300_000, now);
    return id;
  }

  @Override
  public void close() {
    context.close();
  }
}
