package ai.msrv.gaterook.e2e.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.ssh.MinaSecurity;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * SSH through the assembled core (step C4, ARCHITECTURE.md §10): the window's admin API sets up a connection — Test
 * connection stops at the unknown host key, "Trust" pins it — a {@code compose-ps} runbook, an endpoint and a token;
 * an agent then runs {@code ssh_run compose-ps} at once and {@code restart-api} after a person approved it. The host is
 * an SSH server in this JVM (MINA's {@code SshServer} running {@code /bin/sh -c}, with a {@code docker} stand-in on
 * its {@code PATH}).
 *
 * <p>On the JVM this runs {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the SSH
 * connector: MINA's key exchange, Ed25519, the pinned host key and an exec channel in the native image.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SshCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String USER = "deploy";
  private static final String PASSWORD = "ssh-e2e-" + Long.toHexString(System.nanoTime()) + "-Kq7";
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final String COMPOSE_PS = "NAME      IMAGE          STATUS\napp-api   app/api:1.42   Up 3 hours\n"
      + "app-db    postgres:16    Up 3 hours (healthy)\n";

  private static SshServer sshd;
  private static KeyPair hostKey;
  private static Path bin;
  private static final List<String> COMMANDS = new CopyOnWriteArrayList<>();
  private static final List<String> SIGNALS = new CopyOnWriteArrayList<>();
  private static CoreProcess core;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    MinaSecurity.install();
    bin = Files.createDirectories(Path.of("target", "ssh-e2e", "bin").toAbsolutePath());
    Path docker = bin.resolve("docker");
    Files.writeString(docker, """
        #!/bin/sh
        if [ "$1 $2" = "compose ps" ]; then printf '%s' '""" + COMPOSE_PS + """
        '; exit 0; fi
        if [ "$1 $2 $3" = "compose restart api" ]; then echo ' Container app-api  Restarting'; exit 0; fi
        echo "docker stand-in: $*" >&2; exit 2
        """);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));

    hostKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    sshd = SshServer.setUpDefaultServer();
    sshd.setHost("127.0.0.1");
    sshd.setPort(0);
    sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
    sshd.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
    sshd.setCommandFactory((channel, command) -> new Sh(command));
    sshd.start();

    core = CoreProcess.start(CoreProcess.newHome("ssh"), CoreProcess.freePort(), List.of()).bootstrap();
  }

  @AfterAll
  static void stop() throws IOException {
    if (core != null) {
      core.close();
    }
    if (sshd != null) {
      sshd.stop(true);
    }
  }

  @Test
  @Order(1)
  void aConnectionIsTrustedThenSavedWithItsHostKeyPinned() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    String draft = """
        {"type":"ssh","env":"dev","config":%s,"secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(config(), PASSWORD);

    JsonNode first = json(admin("POST", "/api/connections/test", draft), 200);
    assertThat(first.path("ok").asBoolean()).isFalse();
    assertThat(first.at("/needsConfirmation/kind").asString()).as(first.toString()).isEqualTo("host-key");
    assertThat(first.at("/needsConfirmation/data/fingerprint").asString())
        .isEqualTo(KeyUtils.getFingerPrint(hostKey.getPublic()));
    assertThat(COMMANDS).as("nothing ran before the key was trusted").isEmpty();

    JsonNode trusted = json(admin("POST", "/api/connections/test/confirm", "{\"confirmationId\":\""
        + first.path("confirmationId").asString() + "\",\"reason\":\"Fingerprint compared on the host\"}"), 200);
    JsonNode pinned = trusted.path("config");
    assertThat(pinned.at("/hostKeys/pinned/0").asString()).startsWith("ssh-ed25519 AAAA");

    String withKey = """
        {"type":"ssh","env":"dev","config":%s,"secrets":{"/credentials/password":{"source":"local","value":"%s"}}}"""
        .formatted(pinned, PASSWORD);
    JsonNode second = json(admin("POST", "/api/connections/test", withKey), 200);
    assertThat(second.path("ok").asBoolean()).as(second.toString()).isTrue();
    assertThat(second.path("serverVersion").asString()).startsWith("APACHE-SSHD-");
    assertThat(second.path("checks").toString()).contains("\"host key\"", "\"auth\"", "\"probe\"");
    // The test's one read-only probe: who the user is and which commands it has.
    assertThat(COMMANDS).singleElement().asString().contains("id -un").contains("command -v");
    COMMANDS.clear();

    json(admin("POST", "/api/connections", """
        {"name":"dev-vm","type":"ssh","env":"dev","config":%s,
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}}}""".formatted(pinned, PASSWORD)), 201);
  }

  @Test
  @Order(2)
  void runbooksAnEndpointAndATokenAreSetUp() throws Exception {
    JsonNode ps = json(admin("POST", "/api/templates", """
        {"kind":"ssh-runbook","connection":"dev-vm","name":"compose-ps","definition":{"name":"compose-ps",
         "description":"Containers of the app.","command":"docker compose ps","workdir":"%s",
         "readOnly":true,"readOnlyConfirmedFor":"docker compose ps"}}""".formatted(bin.getParent())), 201);
    assertThat(ps.path("compile").path("ok").asBoolean()).as(ps.toString()).isTrue();
    JsonNode restart = json(admin("POST", "/api/templates", """
        {"kind":"ssh-runbook","connection":"dev-vm","name":"restart-api","definition":{"name":"restart-api",
         "description":"Restart the API container.","command":"docker compose restart {service}",
         "params":[{"name":"service","type":"enum","values":["api"],"required":true}]}}"""), 201);
    assertThat(restart.path("compile").path("ok").asBoolean()).as(restart.toString()).isTrue();

    json(admin("POST", "/api/endpoints", "{\"slug\":\"ops\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/ops/connections/dev-vm", "{}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/ops/settings", null), 200);
    json(admin("PUT", "/api/endpoints/ops/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/ops/tokens", "{\"name\":\"ssh-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");
  }

  @Test
  @Order(3)
  void anAgentRunsComposePsAtOnce() throws Exception {
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ssh-e2e","version":"1"}}}""");
    // The runbooks reach ssh_run once the endpoint's pairs are reconciled (asynchronously, after the saves).
    JsonNode run = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      for (JsonNode t : mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        if (t.path("name").asString().equals("ssh_run")) {
          run = t;
        }
      }
      if (run != null && run.at("/inputSchema/properties/runbook/enum").toString().contains("restart-api")) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(run).isNotNull();
    assertThat(run.at("/inputSchema/properties/runbook/enum").toString())
        .isEqualTo("[\"compose-ps\",\"restart-api\"]");
    assertThat(run.path("description").asString()).contains("compose-ps() — read only")
        .contains("restart-api(service: api) — needs approval");

    JsonNode ps = call(3, "ssh_run", "{\"connection\":\"dev-vm\",\"runbook\":\"compose-ps\"}");

    assertThat(ps.at("/result/isError").asBoolean(true)).as(ps.toString()).isFalse();
    assertThat(ps.at("/result/structuredContent/exitCode").asInt()).isZero();
    assertThat(ps.at("/result/structuredContent/stdout").asString()).isEqualTo(COMPOSE_PS);
    assertThat(ps.at("/result/content/0/text").asString()).startsWith("compose-ps on dev-vm (dev) · exit 0 · ");
    assertThat(COMMANDS).containsExactly("cd '" + bin.getParent() + "' && docker compose ps");
  }

  @Test
  @Order(4)
  void aRunbookThatChangesStateRunsAfterAPersonApprovedItsExactLine() throws Exception {
    JsonNode request = call(4, "ssh_run", "{\"connection\":\"dev-vm\",\"runbook\":\"restart-api\","
        + "\"params\":{\"service\":\"api\"},\"note\":\"The API stopped answering\"}");
    JsonNode handle = request.at("/result/structuredContent");
    assertThat(handle.path("status").asString()).as(request.toString()).isEqualTo("pending");
    assertThat(COMMANDS).hasSize(1);

    // The approver reads the exact line the host gets, and where it runs.
    String id = handle.path("handle").asString();
    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    assertThat(detail.at("/action/text").asString()).as(detail.toString()).isEqualTo("docker compose restart 'api'");
    assertThat(detail.path("target").asString()).isEqualTo("dev-vm · deploy@127.0.0.1:" + sshd.getPort());
    assertThat(detail.path("agentNote").asString()).isEqualTo("The API stopped answering");
    json(admin("POST", "/api/approvals/" + id + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    JsonNode status = call(5, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");

    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");
    assertThat(status.at("/result/structuredContent/result/stdout").asString())
        .isEqualTo(" Container app-api  Restarting\n");
    assertThat(COMMANDS).last().isEqualTo("docker compose restart 'api'");
  }

  @Test
  @Order(5)
  void sshExecIsOffAndTheLogIsClean() throws Exception {
    JsonNode exec = call(6, "ssh_exec", "{\"connection\":\"dev-vm\",\"command\":\"id\"}");
    assertThat(exec.toString()).doesNotContain("uid=");
    assertThat(COMMANDS).hasSize(2);

    String log = core.coreLog();
    assertThat(log).doesNotContainPattern("Missing[A-Za-z]*Registration").doesNotContain("UnsupportedFeatureError");
    assertThat(log).doesNotContain(PASSWORD).doesNotContain(token);
  }

  /**
   * REMAINING §4 "long SSH commands": the deadline of an ssh_run call lets the command timeout run (30 s by default)
   * instead of the dev tool timeout of 10 s, so a 12 s runbook completes.
   */
  @Test
  @Order(6)
  void aRunbookLongerThanTheToolTimeoutCompletes() throws Exception {
    runbook("nap", "sleep 12; echo slept");
    long t0 = System.nanoTime();

    JsonNode nap = call(7, "ssh_run", "{\"connection\":\"dev-vm\",\"runbook\":\"nap\"}");

    assertThat(nap.at("/result/isError").asBoolean(true)).as(nap.toString()).isFalse();
    assertThat(nap.at("/result/structuredContent/exitCode").asInt()).isZero();
    assertThat(nap.at("/result/structuredContent/stdout").asString()).isEqualTo("slept\n");
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThan(12_000L);
    assertThat(SIGNALS).isEmpty();
  }

  /** ... and one past the command timeout of the policy (13 s here) gets TERM, then KILL two seconds later. */
  @Test
  @Order(7)
  void aRunbookPastTheCommandTimeoutGetsTermThenKill() throws Exception {
    JsonNode dev = json(admin("GET", "/api/policies/dev", null), 200);
    json(admin("PUT", "/api/policies/dev", "{\"sections\":{\"ssh\":{\"commandTimeoutSeconds\":13}},\"version\":"
        + dev.path("version").asLong() + "}"), 200);
    runbook("stubborn", "trap '' TERM; sleep 25");
    long t0 = System.nanoTime();

    JsonNode stubborn = call(8, "ssh_run", "{\"connection\":\"dev-vm\",\"runbook\":\"stubborn\"}");

    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(stubborn.at("/result/isError").asBoolean(false)).as(stubborn.toString()).isTrue();
    assertThat(stubborn.at("/result/structuredContent/timedOut").asBoolean()).isTrue();
    assertThat(stubborn.at("/result/content/0/text").asString()).contains("stopped at the timeout");
    long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (SIGNALS.size() < 2 && System.nanoTime() < until) {
      Thread.sleep(20);
    }
    assertThat(SIGNALS).containsExactly("TERM", "KILL");
    assertThat(ms).isBetween(12_000L, 16_500L);
  }

  /** Saves a read-only runbook on dev-vm and waits until the endpoint offers it. */
  private static void runbook(String name, String command) throws Exception {
    JsonNode saved = json(admin("POST", "/api/templates", """
        {"kind":"ssh-runbook","connection":"dev-vm","name":"%s","definition":{"name":"%s","command":"%s",
         "readOnly":true,"readOnlyConfirmedFor":"%s"}}""".formatted(name, name, command, command)), 201);
    assertThat(saved.path("compile").path("ok").asBoolean()).as(saved.toString()).isTrue();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      for (JsonNode t : mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        if (t.path("name").asString().equals("ssh_run")
            && t.at("/inputSchema/properties/runbook/enum").toString().contains("\"" + name + "\"")) {
          return;
        }
      }
      Thread.sleep(200);
    }
    throw new AssertionError("ssh_run never offered " + name);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static String config() {
    return "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + sshd.getPort() + ",\"username\":\"" + USER + "\"},"
        + "\"credentials\":{\"method\":\"password\"}}";
  }

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(30))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/ops")).timeout(Duration.ofSeconds(40))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** One exec request as sshd runs it: {@code /bin/sh -c}, no PTY, the stand-in {@code docker} first on PATH. */
  private static final class Sh implements Command, SignalListener {
    private final String command;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exit;
    private Process process;

    Sh(String command) {
      this.command = command;
    }

    @Override
    public void setInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
      this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
      this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
      this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
      COMMANDS.add(command);
      env.addSignalListener(this);
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
      pb.environment().put("PATH", bin + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
      pb.environment().put("LC_ALL", "C.UTF-8");
      process = pb.start();
      List<Thread> pumps = new ArrayList<>();
      pumps.add(Thread.ofVirtual().start(() -> copy(process.getInputStream(), out)));
      pumps.add(Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err)));
      Thread.ofVirtual().start(() -> {
        try {
          in.close(); // nothing reads stdin here
          process.getOutputStream().close();
          int code = process.waitFor();
          for (Thread t : pumps) {
            t.join();
          }
          exit.onExit(code);
        } catch (IOException | InterruptedException e) {
          exit.onExit(255, e.toString());
        }
      });
    }

    private static void copy(InputStream from, OutputStream to) {
      try {
        from.transferTo(to);
        to.flush();
      } catch (IOException ignored) {
        // The channel went away.
      }
    }

    @Override
    public void signal(Channel channel, Signal signal) {
      SIGNALS.add(signal.name());
      if (process == null) {
        return;
      }
      // sshd delivers it to the process group: the shell and what it started.
      if (signal == Signal.KILL) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      } else {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
      }
    }

    @Override
    public void destroy(ChannelSession channel) {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
