package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.sshd.certificate.OpenSshCertificateBuilder;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector against a real SSH server in this JVM (MINA's {@code SshServer} running {@code /bin/sh -c}): host key
 * trust, the three sign-in methods, a bastion, runbooks, the limits, the deadline and the approval path.
 */
class SshConnectorIT {

  private static final String STRING_PARAM = """
      [{"name":"text","type":"string","pattern":"(?s).*","maxLength":256,"allowLeadingDash":true,"required":true}]""";

  private final List<AutoCloseable> closeLater = new ArrayList<>();
  private SshConnector connector;
  private TestSshd sshd;

  @BeforeEach
  void setUp() throws Exception {
    connector = new SshConnector();
    sshd = server(false);
  }

  @AfterEach
  void tearDown() throws Exception {
    connector.close();
    for (AutoCloseable c : closeLater) {
      c.close();
    }
  }

  private TestSshd server(boolean forwarding) throws Exception {
    TestSshd s = TestSshd.start(forwarding);
    closeLater.add(s);
    return s;
  }

  private ConnectionConfig pinned(long version) {
    return TestConfigs.connection("c1", "dev-vm", Env.DEV, version,
        TestConfigs.password(sshd.port(), sshd.hostKeyLine()));
  }

  private static TestSecrets password() {
    return new TestSecrets().with("/credentials/password", TestSshd.PASSWORD);
  }

  private void offer(ConnectionConfig cfg, ObjectNode... runbooks) {
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(cfg), TestConfigs.contributions(cfg.id(), runbooks));
  }

  private static ToolCall run(String runbook, String params) {
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("runbook", runbook);
    if (params != null) {
      args.set("params", TestConfigs.json(params));
    }
    return new ToolCall("tc_1", SshTools.RUN, "dev-vm", args, null);
  }

  private static ToolCall exec(String command) {
    return new ToolCall("tc_2", SshTools.EXEC, "dev-vm", JsonNodeFactory.instance.objectNode().put("command", command),
        null);
  }

  private static JsonNode out(ToolResult r) {
    return r.structuredContent();
  }

  // ---------------------------------------------------------------------------------------------- host key trust

  @Test
  void aNewHostKeyIsTrustedOnlyAfterThePersonConfirmsItsFingerprint() throws Exception {
    ObjectNode config = TestConfigs.password(sshd.port(), null);
    TestSecrets secrets = password();

    TestResult first = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config), secrets,
        TestCtx.read());

    assertThat(first.ok()).isFalse();
    assertThat(first.needsConfirmation()).isNotNull();
    TestResult.Confirmation c = first.needsConfirmation();
    assertThat(c.kind()).isEqualTo("host-key");
    assertThat(c.text()).startsWith("First connection to 127.0.0.1:" + sshd.port() + ". Compare the fingerprint");
    assertThat(c.data().path("fingerprint").asString()).isEqualTo(KeyUtils.getFingerPrint(sshd.hostKey.getPublic()));
    assertThat(c.data().path("keyType").asString()).isEqualTo("ssh-ed25519");
    // Nothing left this computer: no secret resolved, no password offered.
    assertThat(secrets.resolved).hasValue(0);
    assertThat(sshd.passwordAttempts).hasValue(0);

    // "Trust" = the core merges the patch into the config (a Setup change) and tests again.
    config.set("hostKeys", c.configPatch().path("hostKeys"));
    TestResult second = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 2, config), secrets,
        TestCtx.read());

    assertThat(second.error()).isNull();
    assertThat(second.ok()).isTrue();
    assertThat(config.at("/hostKeys/pinned/0").asString()).isEqualTo(sshd.hostKeyLine());
    assertThat(second.serverVersion()).startsWith("APACHE-SSHD-");
    assertThat(second.identity()).contains("@127.0.0.1 · uid ");
    assertThat(second.checks()).extracting(TestResult.Check::name).containsExactly("host key", "auth", "probe");
    assertThat(second.metadataUpdate().path("serverVersion").asString()).isEqualTo(second.serverVersion());
    assertThat(second.metadataUpdate().path("hostKey").path("fingerprint").asString())
        .isEqualTo(c.data().path("fingerprint").asString());
    assertThat(second.permissions()).isNotNull();
  }

  @Test
  void aChangedHostKeyFailsClosedBeforeAnyCredential() throws Exception {
    String other = HostKeys.line(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256).getPublic());
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, TestConfigs.password(sshd.port(), other));
    offer(cfg, TestConfigs.runbook("uptime", "uptime", true, null));
    TestSecrets secrets = password();

    TestResult t = connector.test(cfg, secrets, TestCtx.read());

    assertThat(t.ok()).isFalse();
    assertThat(t.needsConfirmation()).isNull();
    assertThat(t.error().code()).isEqualTo("ssh.host-key-changed");
    assertThat(t.error().message()).contains("changed. GateRook sent nothing to it");
    assertThat(t.details().path("fingerprint").asString()).isEqualTo(KeyUtils.getFingerPrint(sshd.hostKey.getPublic()));
    assertThatThrownBy(() -> connector.execute(run("uptime", null), cfg, secrets, TestCtx.read()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(secrets.resolved).hasValue(0);
    assertThat(sshd.passwordAttempts).hasValue(0);
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void knownHostsLinesTrustAndRevoke() throws Exception {
    String entry = "[127.0.0.1]:" + sshd.port() + " " + sshd.hostKeyLine();
    ObjectNode known = TestConfigs.password(sshd.port(), null);
    known.putObject("hostKeys").put("knownHosts", "# pasted\n" + entry + "\n");
    ObjectNode revoked = TestConfigs.password(sshd.port(), null);
    revoked.putObject("hostKeys").put("knownHosts", entry + "\n@revoked " + entry + "\n");

    TestResult ok = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, known), password(),
        TestCtx.read());
    TestResult no = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 2, revoked), password(),
        TestCtx.read());

    assertThat(ok.ok()).isTrue();
    assertThat(ok.checks().get(0).detail()).endsWith(", known_hosts");
    assertThat(no.error().code()).isEqualTo("ssh.host-key-revoked");
  }

  // ---------------------------------------------------------------------------------------------- runbooks

  @Test
  void aReadOnlyRunbookRunsAndAnExitCodeIsData() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("compose-ps", "printf 'NAME STATUS\\napi  Up\\n'; echo warn >&2; exit 3", true,
        null));

    ToolResult r = connector.execute(run("compose-ps", null), cfg, password(), TestCtx.read());

    assertThat(r.isError()).isFalse();
    assertThat(out(r).path("exitCode").asInt()).isEqualTo(3);
    assertThat(out(r).path("stdout").asString()).isEqualTo("NAME STATUS\napi  Up\n");
    assertThat(out(r).path("stderr").asString()).isEqualTo("warn\n");
    assertThat(out(r).path("truncated").asBoolean()).isFalse();
    assertThat(r.content().get(0).text()).startsWith("compose-ps on dev-vm (dev) · exit 3 · ")
        .contains("NAME STATUS\napi  Up\nstderr:\nwarn\n");
    assertThat(sshd.commands).containsExactly("printf 'NAME STATUS\\napi  Up\\n'; echo warn >&2; exit 3");
  }

  @Test
  void aHostileStringParameterArrivesAsOneWord() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("echo-it", "printf '%s' {text}", true, STRING_PARAM));
    String hostile = "'; touch /tmp/gw-ssh-pwned; echo $(id) `id` \"$HOME\" *";

    ToolResult r = connector.execute(run("echo-it", JsonNodeFactory.instance.objectNode().put("text", hostile)
        .toString()), cfg, password(), TestCtx.read());

    assertThat(out(r).path("stdout").asString()).isEqualTo(hostile);
    assertThat(out(r).path("exitCode").asInt()).isZero();
    assertThat(java.nio.file.Path.of("/tmp/gw-ssh-pwned")).doesNotExist();
  }

  @Test
  void parallelReadsShareOnePooledSession() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("nap", "sleep 0.3; echo done", true, null));
    TestConfigs.allowParallel(connector, cfg, 8);

    List<ToolResult> results = parallel(8, () -> connector.execute(run("nap", null), cfg, password(),
        TestCtx.read()));

    assertThat(results).allSatisfy(r -> assertThat(out(r).path("stdout").asString()).isEqualTo("done\n"));
    assertThat(sshd.sessions).hasValue(1);
  }

  /** Parallel calls per connection (policy, prod 2): a call past the limit waits briefly, then is refused. */
  @Test
  void callsPastTheParallelLimitOfThePolicyAreRefused() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("nap", "sleep 1; echo done", true, null));
    TestConfigs.allowParallel(connector, cfg, 2);

    List<Object> outcomes = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ToolResult>> calls = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        calls.add(pool.submit(() -> connector.execute(run("nap", null), cfg, password(), TestCtx.read())));
      }
      for (Future<ToolResult> f : calls) {
        try {
          outcomes.add(f.get());
        } catch (java.util.concurrent.ExecutionException e) {
          outcomes.add(e.getCause());
        }
      }
    }

    assertThat(outcomes).filteredOn(o -> o instanceof ToolResult).hasSize(2);
    assertThat(outcomes).filteredOn(o -> o instanceof ConnectorException).singleElement()
        .satisfies(e -> assertThat(((ConnectorException) e).getMessage())
            .isEqualTo("dev-vm is busy: 2 calls are running (Parallel calls per connection in Policies). Try again "
                + "in a moment."));
  }

  /** A pooled session signed in for an earlier call: a later call's taint still gets the password. */
  @Test
  void aCallOnAPooledSessionStillTaintsThePassword() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("uptime", "echo up", true, null));
    connector.execute(run("uptime", null), cfg, password(), TestCtx.read());
    TestCtx second = TestCtx.read();

    connector.execute(run("uptime", null), cfg, new TestSecrets(), second);

    assertThat(sshd.sessions).hasValue(1);
    assertThat(second.tainted()).contains(TestSshd.PASSWORD);
  }

  @Test
  void aRefusedChannelIsRetriedOnceOnANewSession() throws Exception {
    sshd.maxChannelsPerSession = 2;
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("nap", "sleep 1; echo done", true, null));

    List<ToolResult> results = parallel(3, () -> connector.execute(run("nap", null), cfg, password(),
        TestCtx.read()));

    assertThat(results).allSatisfy(r -> assertThat(out(r).path("stdout").asString()).isEqualTo("done\n"));
    assertThat(sshd.refusedChannels).hasValue(1);
    assertThat(sshd.sessions).hasValue(2);
  }

  @Test
  void aChangedConnectionClosesItsPooledSessions() throws Exception {
    ConnectionConfig v1 = pinned(1);
    ConnectionConfig v2 = pinned(2);
    offer(v1, TestConfigs.runbook("uptime", "echo up", true, null));

    connector.execute(run("uptime", null), v1, password(), TestCtx.read());
    connector.execute(run("uptime", null), v1, password(), TestCtx.read());
    connector.onConnectionChanged("c1");
    connector.execute(run("uptime", null), v2, password(), TestCtx.read());

    assertThat(sshd.sessions).hasValue(2);
  }

  // ---------------------------------------------------------------------------------------------- limits

  @Test
  void outputIsCutAtItsLimitAndTheCommandStopped() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("flood", "yes 0123456789 | head -c 5000000", true, null));
    Limits limits = TestCtx.limits(Duration.ofSeconds(30), Duration.ofSeconds(10), 64 * 1024, 64 * 1024);

    ToolResult r = connector.execute(run("flood", null), cfg, password(), TestCtx.of(limits));

    assertThat(r.isError()).isFalse();
    assertThat(out(r).path("truncated").asBoolean()).isTrue();
    assertThat(out(r).path("truncatedReason").asString()).isEqualTo("output_limit");
    assertThat(out(r).path("stdout").asString()).hasSize(64 * 1024);
    assertThat(out(r).path("stdoutBytes").asLong()).isGreaterThan(64 * 1024);
    assertThat(r.content().get(0).text()).contains("Output cut at 64 KB of ≥ ");
    assertThat(sshd.signals).contains("TERM");
  }

  @Test
  void aCommandPastItsDeadlineGetsTermOnTheHost() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("hang", "sleep 30", true, null));
    Limits limits = TestCtx.limits(Duration.ofSeconds(2), Duration.ofSeconds(10), 256 * 1024, 64 * 1024);
    long t0 = System.nanoTime();

    ToolResult r = connector.execute(run("hang", null), cfg, password(), TestCtx.of(limits));

    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r.isError()).isTrue();
    assertThat(out(r).path("timedOut").asBoolean()).isTrue();
    assertThat(out(r).path("truncatedReason").asString()).isEqualTo("timeout");
    assertThat(r.content().get(0).text()).contains("stopped at the timeout");
    assertThat(sshd.signals).containsExactly("TERM");
    assertThat(ms).isBetween(2_000L, 4_500L);
    awaitEnded(sshd);
  }

  @Test
  void aCommandThatIgnoresTermIsKilledTwoSecondsLater() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("stubborn", "trap '' TERM; sleep 30", true, null));
    Limits limits = TestCtx.limits(Duration.ofSeconds(1), Duration.ofSeconds(10), 256 * 1024, 64 * 1024);
    long t0 = System.nanoTime();

    ToolResult r = connector.execute(run("stubborn", null), cfg, password(), TestCtx.of(limits));

    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(out(r).path("timedOut").asBoolean()).isTrue();
    // KILL is the last packet before the close: the host may read it just after the call returned.
    long until = System.nanoTime() + 2_000_000_000L;
    while (sshd.signals.size() < 2 && System.nanoTime() < until) {
      Thread.sleep(20);
    }
    assertThat(sshd.signals).containsExactly("TERM", "KILL");
    assertThat(ms).isBetween(3_000L, 6_000L);
    awaitEnded(sshd);
  }

  @Test
  void theCallsDeadlineBoundsTheCommandTimeout() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("hang", "sleep 30", true, null));
    // 30 s allowed by the policy, but this call has 5 s: TERM must reach the host before its deadline.
    Limits limits = TestCtx.limits(Duration.ofSeconds(30), Duration.ofSeconds(5), 256 * 1024, 64 * 1024);
    TestCtx ctx = TestCtx.of(limits);

    ToolResult r = connector.execute(run("hang", null), cfg, password(), ctx);

    assertThat(out(r).path("timedOut").asBoolean()).isTrue();
    assertThat(sshd.signals).containsExactly("TERM");
    assertThat(Instant.now()).isBefore(ctx.deadline());
  }

  /** REMAINING §4: a run longer than the tool timeout completes, because a command tool's call outlasts it. */
  @Test
  void aRunLongerThanTheToolTimeoutCompletesWithinItsCommandTimeout() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("nap", "sleep 3; echo slept", true, null));
    Limits limits = TestCtx.limits(Duration.ofSeconds(8), Duration.ofSeconds(2), 256 * 1024, 64 * 1024);
    long t0 = System.nanoTime();

    ToolResult r = connector.execute(run("nap", null), cfg, password(), TestCtx.command(limits));

    assertThat(r.isError()).as(r.content().get(0).text()).isFalse();
    assertThat(out(r).path("exitCode").asInt()).isZero();
    assertThat(out(r).path("stdout").asString()).isEqualTo("slept\n");
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThan(2_000L);
    assertThat(sshd.signals).isEmpty();
  }

  /** ... and one past its command timeout gets TERM, then KILL, before that longer deadline. */
  @Test
  void aRunPastItsCommandTimeoutGetsTermThenKillBeforeTheCallsDeadline() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("stubborn", "trap '' TERM; sleep 30", true, null));
    Limits limits = TestCtx.limits(Duration.ofSeconds(4), Duration.ofSeconds(1), 256 * 1024, 64 * 1024);
    TestCtx ctx = TestCtx.command(limits);
    long t0 = System.nanoTime();

    ToolResult r = connector.execute(run("stubborn", null), cfg, password(), ctx);

    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(out(r).path("timedOut").asBoolean()).isTrue();
    long until = System.nanoTime() + 2_000_000_000L;
    while (sshd.signals.size() < 2 && System.nanoTime() < until) {
      Thread.sleep(20);
    }
    assertThat(sshd.signals).containsExactly("TERM", "KILL");
    assertThat(ms).isBetween(5_000L, 6_500L);
    assertThat(Instant.now()).isBefore(ctx.deadline().plusMillis(500));
    awaitEnded(sshd);
  }

  @Test
  void cancellingTheCallStopsTheCommand() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("hang", "sleep 30", true, null));
    TestCtx ctx = TestCtx.read();
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    closeLater.add(pool::shutdownNow);

    Future<ToolResult> call = pool.submit(() -> connector.execute(run("hang", null), cfg, password(), ctx));
    while (sshd.commands.isEmpty()) {
      Thread.sleep(20);
    }
    Thread.sleep(200);
    ctx.cancel();
    ToolResult r = call.get();

    assertThat(r.isError()).isTrue();
    assertThat(r.content().get(0).text()).contains("· cancelled ·");
    assertThat(sshd.signals).containsExactly("TERM");
    awaitEnded(sshd);
  }

  /** A cancelled call whose command ignores TERM: KILL follows two seconds later, as at the deadline. */
  @Test
  void aCancelledCommandThatIgnoresTermIsKilledTwoSecondsLater() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("stubborn", "trap '' TERM; sleep 30", true, null));
    TestCtx ctx = TestCtx.read();
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    closeLater.add(pool::shutdownNow);

    Future<ToolResult> call = pool.submit(() -> connector.execute(run("stubborn", null), cfg, password(), ctx));
    while (sshd.commands.isEmpty()) {
      Thread.sleep(20);
    }
    Thread.sleep(200);
    long t0 = System.nanoTime();
    ctx.cancel();
    ToolResult r = call.get();

    long until = System.nanoTime() + 2_000_000_000L;
    while (sshd.signals.size() < 2 && System.nanoTime() < until) {
      Thread.sleep(20);
    }
    assertThat(r.content().get(0).text()).contains("· cancelled ·");
    assertThat(sshd.signals).containsExactly("TERM", "KILL");
    assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(1_900L, 4_500L);
    awaitEnded(sshd);
  }

  // ---------------------------------------------------------------------------------------------- approvals

  @Test
  void anApprovedChangeRunsTheStoredLineOnASessionOfItsOwn() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("uptime", "echo up", true, null),
        TestConfigs.runbook("restart", "echo restarted {service}", false,
            "[{\"name\":\"service\",\"type\":\"enum\",\"values\":[\"api\"],\"required\":true}]"));
    connector.execute(run("uptime", null), cfg, password(), TestCtx.read());
    ToolCall call = run("restart", "{\"service\":\"api\"}");
    ActionPreview preview = connector.describeAction(call, cfg);

    ToolResult r = connector.execute(call, cfg, password(),
        TestCtx.approved(TestCtx.devLimits(), "ap_7f3k", preview.payload()));
    connector.execute(run("uptime", null), cfg, password(), TestCtx.read());

    assertThat(out(r).path("stdout").asString()).isEqualTo("restarted api\n");
    assertThat(sshd.commands).containsExactly("echo up", "echo restarted 'api'", "echo up");
    assertThat(preview.text()).isEqualTo("echo restarted 'api'");
    // The read's pooled session, the approval's own one; the second read reused the pool.
    assertThat(sshd.sessions).hasValue(2);
  }

  @Test
  void sshExecRunsTheApprovedTextByteForByte() throws Exception {
    ConnectionConfig cfg = pinned(1);
    ToolCall call = exec("printf '%s|' \"$((6*7))\" 'a b'\necho second line");
    ActionPreview preview = connector.describeAction(call, cfg);

    ToolResult r = connector.execute(call, cfg, password(),
        TestCtx.approved(TestCtx.devLimits(), "ap_1", preview.payload()));

    assertThat(out(r).path("stdout").asString()).isEqualTo("42|a b|second line\n");
    assertThat(sshd.commands).containsExactly(preview.text());
    assertThat(preview.warnings()).contains("Several lines: every line runs");
  }

  // ---------------------------------------------------------------------------------------------- sign-in

  @Test
  void aWrongPasswordIsTheHostsAnswer() throws Exception {
    ConnectionConfig cfg = pinned(1);
    offer(cfg, TestConfigs.runbook("uptime", "echo up", true, null));
    SecretResolver wrong = new TestSecrets().with("/credentials/password", "nope");

    assertThatThrownBy(() -> connector.execute(run("uptime", null), cfg, wrong, TestCtx.read()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR))
        .hasMessage("deploy@127.0.0.1:" + sshd.port() + " rejected the password.");
  }

  @Test
  void signsInWithAPrivateKeyAndWithAKeyAndItsCertificate() throws Exception {
    KeyPair user = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    ByteArrayOutputStream pem = new ByteArrayOutputStream();
    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(user, "deploy", null, pem);
    ObjectNode keyConfig = TestConfigs.with(TestConfigs.password(sshd.port(), sshd.hostKeyLine()),
        c -> c.putObject("credentials").put("method", "privateKey"));
    TestSecrets secrets = new TestSecrets().with("/credentials/privateKey", pem.toString(StandardCharsets.US_ASCII));
    ConnectionConfig plain = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, keyConfig);
    offer(plain, TestConfigs.runbook("whoami", "echo me", true, null));

    sshd.authorizedKeys.add(user.getPublic());
    ToolResult byKey = connector.execute(run("whoami", null), plain, secrets, TestCtx.read());
    sshd.authorizedKeys.clear();

    KeyPair ca = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    sshd.userCa = ca.getPublic();
    OpenSshCertificate cert = OpenSshCertificateBuilder.userCertificate().publicKey(user.getPublic()).serial(42)
        .id("deploy-laptop").principals(List.of(TestSshd.USER)).validAfter(Instant.now().minusSeconds(60))
        .validBefore(Instant.now().plusSeconds(3600)).sign(ca, "ssh-ed25519");
    ConnectionConfig withCert = TestConfigs.connection("c1", "dev-vm", Env.DEV, 2, TestConfigs.with(keyConfig,
        c -> ((ObjectNode) c.get("credentials")).put("certificate", PublicKeyEntry.toString(cert))));
    offer(withCert, TestConfigs.runbook("whoami", "echo me", true, null));
    ToolResult byCert = connector.execute(run("whoami", null), withCert, secrets, TestCtx.read());

    assertThat(out(byKey).path("stdout").asString()).isEqualTo("me\n");
    assertThat(out(byCert).path("stdout").asString()).isEqualTo("me\n");
    assertThat(sshd.certificates).extracting(OpenSshCertificate::getId).containsExactly("deploy-laptop");
  }

  @Test
  void theVaultCaSignsAFreshCertificateNamedAfterTheCall() throws Exception {
    KeyPair ca = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    sshd.userCa = ca.getPublic();
    ObjectNode config = TestConfigs.with(TestConfigs.password(sshd.port(), sshd.hostKeyLine()), c -> {
      ObjectNode creds = c.putObject("credentials").put("method", "vaultSshCa");
      creds.putObject("vault").put("store", "vault-dev").put("ttl", "2m");
    });
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config);
    offer(cfg, TestConfigs.runbook("restart", "echo restarted", false, null));
    TestSecrets secrets = new TestSecrets().ca(ca);
    ToolCall call = run("restart", null);

    connector.execute(call, cfg, secrets, TestCtx.approved(TestCtx.devLimits(), "ap_7f3k",
        connector.describeAction(call, cfg).payload()));
    TestResult tested = connector.test(cfg, secrets, TestCtx.read());

    assertThat(secrets.signRequests).hasSize(2);
    SecretResolver.SshCertRequest req = secrets.signRequests.get(0);
    assertThat(req.principal()).isEqualTo(TestSshd.USER);
    assertThat(req.ttl()).isEqualTo(Duration.ofMinutes(2));
    assertThat(req.keyId()).isEqualTo("gw:dev-vm:ap_7f3k");
    assertThat(req.extensions()).isEmpty();
    assertThat(sshd.certificates).extracting(OpenSshCertificate::getId).containsExactly("gw:dev-vm:ap_7f3k",
        "gw:dev-vm:tc_test");
    // Every certificate carries a fresh key: the private half never leaves this process.
    assertThat(KeyUtils.compareKeys(sshd.certificates.get(0).getCertPubKey(),
        sshd.certificates.get(1).getCertPubKey())).isFalse();
    assertThat(tested.ok()).isTrue();
    assertThat(tested.details().at("/auth/method").asString()).isEqualTo("vaultSshCa");
    assertThat(tested.details().at("/auth/serial").asString()).isNotBlank();
  }

  // ---------------------------------------------------------------------------------------------- bastion

  @Test
  void runsThroughABastionWhoseCertificateMayForward() throws Exception {
    TestSshd bastion = server(true);
    KeyPair ca = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    sshd.userCa = ca.getPublic();
    bastion.userCa = ca.getPublic();
    ObjectNode config = TestConfigs.with(TestConfigs.password(sshd.port(), sshd.hostKeyLine()), c -> {
      c.putObject("credentials").put("method", "vaultSshCa").putObject("vault").put("store", "vault-dev");
      c.putObject("jump").put("host", "127.0.0.1").put("port", bastion.port())
          .putObject("hostKeys").putArray("pinned").add(bastion.hostKeyLine());
    });
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config);
    offer(cfg, TestConfigs.runbook("uptime", "echo up", true, null));
    TestSecrets secrets = new TestSecrets().ca(ca);

    ToolResult r = connector.execute(run("uptime", null), cfg, secrets, TestCtx.read());
    TestResult tested = connector.test(cfg, secrets, TestCtx.read());

    assertThat(out(r).path("stdout").asString()).isEqualTo("up\n");
    assertThat(bastion.commands).isEmpty();
    assertThat(sshd.commands).first().isEqualTo("echo up");
    assertThat(secrets.signRequests.get(0).extensions()).containsOnlyKeys("permit-port-forwarding");
    assertThat(secrets.signRequests.get(1).extensions()).isEmpty();
    assertThat(tested.ok()).isTrue();
    assertThat(tested.checks()).extracting(TestResult.Check::name)
        .containsExactly("bastion host key", "bastion auth", "host key", "auth", "probe");
    assertThat(connector.describeAction(run("uptime", null), cfg).target())
        .isEqualTo("dev-vm · deploy@127.0.0.1:" + sshd.port() + " via 127.0.0.1:" + bastion.port());
  }

  @Test
  void aBastionThatRefusesForwardingSaysSo() throws Exception {
    TestSshd bastion = server(false);
    ObjectNode config = TestConfigs.with(TestConfigs.password(sshd.port(), sshd.hostKeyLine()),
        c -> c.putObject("jump").put("host", "127.0.0.1").put("port", bastion.port())
            .putObject("hostKeys").putArray("pinned").add(bastion.hostKeyLine()));
    ConnectionConfig cfg = TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config);
    offer(cfg, TestConfigs.runbook("uptime", "echo up", true, null));

    assertThatThrownBy(() -> connector.execute(run("uptime", null), cfg, password(), TestCtx.read()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR))
        .hasMessageContaining("refused port forwarding");
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void anUnknownBastionKeyIsOfferedForTrustFirst() throws Exception {
    TestSshd bastion = server(true);
    ObjectNode config = TestConfigs.with(TestConfigs.password(sshd.port(), sshd.hostKeyLine()),
        c -> c.putObject("jump").put("host", "127.0.0.1").put("port", bastion.port()));

    TestResult t = connector.test(TestConfigs.connection("c1", "dev-vm", Env.DEV, 1, config), password(),
        TestCtx.read());

    assertThat(t.needsConfirmation()).isNotNull();
    assertThat(t.needsConfirmation().data().path("bastion").asBoolean()).isTrue();
    assertThat(t.needsConfirmation().configPatch().at("/jump/hostKeys/pinned/0").asString())
        .isEqualTo(bastion.hostKeyLine());
  }

  // ---------------------------------------------------------------------------------------------- health

  @Test
  void healthReadsTheBannerAndAClosedPortIsUnreachable() {
    HealthResult up = connector.health(pinned(1), password(), TestCtx.read());
    ConnectionConfig closed = TestConfigs.connection("c2", "gone", Env.DEV, 1, TestConfigs.password(1, null));
    HealthResult down = connector.health(closed, password(), TestCtx.read());
    TestResult tested = connector.test(closed, password(), TestCtx.read());

    assertThat(up.state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(down.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(down.message()).isEqualTo("Connection refused on port 1 of 127.0.0.1. Is sshd running?");
    assertThat(tested.error().code()).isEqualTo("ssh.unreachable");
  }

  // ---------------------------------------------------------------------------------------------- helpers

  private List<ToolResult> parallel(int n, Callable<ToolResult> call) throws Exception {
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ToolResult>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(pool.submit(call));
      }
      List<ToolResult> out = new ArrayList<>();
      for (Future<ToolResult> f : futures) {
        out.add(f.get());
      }
      return out;
    }
  }

  private static void awaitEnded(TestSshd server) throws InterruptedException {
    long until = System.nanoTime() + 3_000_000_000L;
    while (!server.allEnded() && System.nanoTime() < until) {
      Thread.sleep(20);
    }
    assertThat(server.allEnded()).as("every process on the host ended").isTrue();
  }
}
