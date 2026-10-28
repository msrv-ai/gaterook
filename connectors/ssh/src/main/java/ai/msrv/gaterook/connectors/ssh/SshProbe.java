package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection (report 06 §5): TCP, key exchange and the host key, the banner, the sign-in, one read-only probe.
 * Nothing is saved. A host key nobody trusted yet stops the test before any credential is resolved: the person
 * compares the fingerprint, "Trust" pins the key (a Setup change) and the test runs again. A pinned key that changed
 * fails with no way to trust it from here.
 *
 * <p>The health check is lighter: TCP and the banner, no key exchange, no sign-in, no Vault signature.
 */
final class SshProbe {

  /** The probe: what the account may do, read only, at most a few seconds. */
  private static final String PROBE = "printf 'os=%s\\nuser=%s\\nuid=%s\\ngroups=%s\\nshell=%s\\n' \"$(uname -srm)\" "
      + "\"$(id -un)\" \"$(id -u)\" \"$(id -Gn)\" \"${SHELL:-}\"";
  private static final String SUDO = "if sudo -n true 2>/dev/null; then echo sudo=nopasswd; elif command -v sudo "
      + ">/dev/null 2>&1; then echo sudo=password; else echo sudo=none; fi";
  private static final Set<String> POSIX_SHELLS = Set.of("sh", "bash", "dash", "zsh", "ksh", "mksh", "ash", "");

  private final SshSessions sessions;
  private final Runbooks runbooks;

  SshProbe(SshSessions sessions, Runbooks runbooks) {
    this.sessions = sessions;
    this.runbooks = runbooks;
  }

  TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    List<TestResult.Check> checks = new ArrayList<>();
    SshSettings s;
    try {
      s = SshSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("ssh.config", e.getMessage(), null);
    }
    long deadline = System.nanoTime() + ctx.remaining().toNanos();
    HostKeys.Check jumpCheck = null;
    HostKeys.Check hostCheck = null;
    ClientSession bastion = null;
    ClientSession session = null;
    String keyId = "gw:" + cfg.name() + ":" + ctx.callId();
    try {
      hostCheck = HostKeys.check(s.target(), s.hostKeys());
      long t0 = System.nanoTime();
      if (s.jump() != null) {
        jumpCheck = HostKeys.check(s.jump().target(), s.jump().hostKeys());
        bastion = sessions.connect(s, s.jump().target(), s.jump().target().host(), s.jump().target().port(),
            jumpCheck, left(deadline));
        checks.add(check("bastion host key", t0, true, keyLabel(jumpCheck)));
        long ta = System.nanoTime();
        SshLogins.Login jl = SshLogins.login(s.jump().credentials(), s.jump().target(), secrets, keyId, true, ctx);
        sessions.authenticate(bastion, jl, left(deadline), s.jump().target(), jumpCheck);
        checks.add(check("bastion auth", ta, true, s.jump().target().label()));
        t0 = System.nanoTime();
        session = sessions.throughBastion(bastion, s, hostCheck, left(deadline));
      } else {
        session = sessions.connect(s, s.target(), s.target().host(), s.target().port(), hostCheck, left(deadline));
      }
      long latencyMs = (System.nanoTime() - t0) / 1_000_000;
      checks.add(check("host key", t0, true, keyLabel(hostCheck)));
      String banner = session.getServerVersion();

      long ta = System.nanoTime();
      SshLogins.Login login = SshLogins.login(s.credentials(), s.target(), secrets, keyId, false, ctx);
      sessions.authenticate(session, login, left(deadline), s.target(), hostCheck);
      checks.add(check("auth", ta, true, SshLogins.Login.describe(s)));

      long tp = System.nanoTime();
      Map<String, List<String>> facts = probe(session, s, cfg, deadline, ctx);
      checks.add(check("probe", tp, true, null));
      return result(cfg, s, banner, latencyMs, login, facts, checks, hostCheck);
    } catch (ConnectorException e) {
      HostKeys.Check failed = jumpCheck != null && jumpCheck.verdict() != HostKeys.Verdict.PINNED
          && jumpCheck.verdict() != HostKeys.Verdict.KNOWN_HOSTS ? jumpCheck : hostCheck;
      SshSettings.Target where = failed == jumpCheck && s.jump() != null ? s.jump().target() : s.target();
      if (failed != null && failed.verdict() == HostKeys.Verdict.UNKNOWN && failed.seen() != null) {
        return TestResult.confirm(trust(failed, where, failed == jumpCheck), checks);
      }
      if (failed != null && (failed.verdict() == HostKeys.Verdict.CHANGED
          || failed.verdict() == HostKeys.Verdict.REVOKED) && failed.seen() != null) {
        return changed(failed, where, e, checks);
      }
      return TestResult.failure(code(e), e.getMessage(), technical(e)).withChecks(checks);
    } finally {
      if (session != null) {
        session.close(true);
      }
      if (bastion != null) {
        bastion.close(true);
      }
    }
  }

  /** TCP and the server's identification line; the bastion's when there is one. */
  HealthResult health(ConnectionConfig cfg, ExecContext ctx) {
    SshSettings s;
    try {
      s = SshSettings.of(cfg);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
    SshSettings.Target t = s.jump() == null ? s.target() : s.jump().target();
    int timeout = (int) Math.max(500, Math.min(s.advanced().connectTimeout().toMillis(), ctx.remaining().toMillis()));
    long t0 = System.nanoTime();
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(t.host(), t.port()), timeout);
      socket.setSoTimeout(timeout);
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      for (int i = 0; i < 20; i++) {
        String line = in.readLine();
        if (line == null) {
          break;
        }
        if (line.startsWith("SSH-")) {
          return HealthResult.healthy((System.nanoTime() - t0) / 1_000_000);
        }
      }
      return HealthResult.unreachable(t.address() + " answered, but not as an SSH server.");
    } catch (IOException e) {
      return HealthResult.unreachable(SshErrors.connect(t, e).getMessage());
    }
  }

  private Map<String, List<String>> probe(ClientSession session, SshSettings s, ConnectionConfig cfg, long deadline,
      ExecContext ctx) throws ConnectorException {
    StringBuilder script = new StringBuilder(PROBE);
    if (s.advanced().probeSudo()) {
      script.append("; ").append(SUDO);
    }
    Set<String> commands = new LinkedHashSet<>(List.of("timeout", "docker", "systemctl"));
    for (Runbooks.Entry e : runbooks.of(cfg.id())) {
      String first = firstWord(e.runbook().command());
      if (first != null) {
        commands.add(first);
      }
    }
    script.append("; for c in");
    commands.forEach(c -> script.append(' ').append(ShellQuote.quote(c)));
    script.append("; do command -v \"$c\" >/dev/null 2>&1 && echo \"has=$c\"; done; true");
    Duration budget = Duration.ofMillis(Math.min(5_000, Math.max(500, left(deadline).toMillis() - 500)));
    SshExec.Result r;
    try {
      r = SshExec.run(session, script.toString(), budget, 64 * 1024, 16 * 1024, ctx);
    } catch (SshExec.ChannelRefused e) {
      throw ConnectorException.resourceError("The host refused to run a command for this user.", e);
    }
    if (r.brokenOff() || r.exitStatus() == null) {
      throw ConnectorException.resourceError(s.target().label() + " signed in but didn't run the probe. A forced "
          + "command or a non-POSIX shell can't run runbooks.", null);
    }
    Map<String, List<String>> facts = new LinkedHashMap<>();
    for (String line : OutputSanitizer.decode(r.stdout()).split("\n")) {
      int eq = line.indexOf('=');
      if (eq > 0) {
        facts.computeIfAbsent(line.substring(0, eq), k -> new ArrayList<>()).add(line.substring(eq + 1).strip());
      }
    }
    return facts;
  }

  private TestResult result(ConnectionConfig cfg, SshSettings s, String banner, long latencyMs,
      SshLogins.Login login, Map<String, List<String>> facts, List<TestResult.Check> checks, HostKeys.Check host) {
    String user = one(facts, "user");
    String uid = one(facts, "uid");
    String sudo = one(facts, "sudo");
    String shellPath = one(facts, "shell");
    String shell = shellPath == null ? "" : shellPath.substring(shellPath.lastIndexOf('/') + 1);
    List<String> groups = one(facts, "groups") == null ? List.of() : List.of(one(facts, "groups").split("\\s+"));
    List<String> has = facts.getOrDefault("has", List.of());
    boolean root = "0".equals(uid);
    boolean docker = groups.contains("docker");
    boolean nopasswd = "nopasswd".equals(sudo);
    List<String> admin = new ArrayList<>();
    if (root) {
      admin.add("root");
    }
    if (nopasswd) {
      admin.add("sudo without a password");
    }
    if (docker) {
      admin.add("docker group");
    }
    TestResult.Level level = !admin.isEmpty() ? TestResult.Level.ADMIN : TestResult.Level.WRITE;
    String who = user == null ? s.target().username() : user;
    TestResult.Permissions permissions = new TestResult.Permissions(level, "Runs commands as " + who,
        "Whatever " + who + " may change on the host", List.copyOf(admin), null);
    String version = serverVersion(banner);
    TestResult r = TestResult.success(latencyMs, version, who + "@" + s.target().host() + (uid == null ? ""
        : " · uid " + uid), permissions).withChecks(checks);
    boolean prod = cfg.env() == Env.PROD;
    if (root) {
      r = r.withHint(new TestResult.Hint(TestResult.Severity.DANGER, "ssh.root", null, "Connected as root. Use a "
          + "dedicated user and give it sudo only for the runbook commands."));
    }
    if (nopasswd) {
      r = r.withHint(new TestResult.Hint(prod ? TestResult.Severity.WARNING : TestResult.Severity.INFO,
          "ssh.sudo-nopasswd", null, who + " can run any command as root without a password. For prod, allow only "
              + "the runbook commands in sudoers."));
    }
    if (docker) {
      r = r.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "ssh.docker-group", null, who + " is in the "
          + "docker group: it controls every container, which is close to root."));
    }
    if (!POSIX_SHELLS.contains(shell)) {
      r = r.withHint(new TestResult.Hint(TestResult.Severity.ERROR, "ssh.shell", null, "Login shell " + shell
          + " isn't POSIX: runbooks can't be quoted safely."));
    }
    for (Runbooks.Entry e : runbooks.of(cfg.id())) {
      Runbook rb = e.runbook();
      String first = firstWord(rb.command());
      if ("sudo".equals(first) && "password".equals(sudo)) {
        r = r.withHint(new TestResult.Hint(TestResult.Severity.ERROR, "ssh.sudo-password", null, rb.name()
            + " uses sudo, but " + who + " needs a password for it. Add a NOPASSWD rule for this exact command."));
      } else if (first != null && !"sudo".equals(first) && !has.contains(first) && !builtin(first)) {
        r = r.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "ssh.command-missing", null, first + " isn't "
            + "on PATH for non-interactive sessions. Use the full path in " + rb.name() + "."));
      }
    }
    if (prod && s.credentials().method() == SshSettings.Method.PASSWORD) {
      r = r.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "ssh.password-on-prod", "/credentials/method",
          "Use the Vault SSH CA or a key for prod."));
    }
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("serverVersion", version);
    metadata.put("banner", banner);
    if (one(facts, "os") != null) {
      metadata.put("os", one(facts, "os"));
    }
    metadata.put("user", who);
    metadata.put("shell", shell);
    metadata.put("sudo", sudo == null ? "unknown" : sudo);
    ArrayNode hasNode = metadata.putArray("has");
    has.forEach(hasNode::add);
    if (host.seen() != null) {
      metadata.putObject("hostKey").put("type", KeyUtils.getKeyType(host.seen()))
          .put("fingerprint", HostKeys.fingerprint(host.seen()));
    }
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    ObjectNode auth = details.putObject("auth");
    auth.put("method", login.method().id);
    if (login.serial() != null) {
      auth.put("serial", login.serial());
      auth.put("signMs", login.signMs());
    }
    if (login.notAfter() != null) {
      auth.put("validUntil", login.notAfter().toString());
    }
    ArrayNode groupsNode = details.putObject("permissions").putArray("groups");
    groups.forEach(groupsNode::add);
    return r.withMetadata(metadata).withDetails(details);
  }

  /** "First connection to …": the person compares the fingerprint, then the key is pinned by the patch. */
  static TestResult.Confirmation trust(HostKeys.Check check, SshSettings.Target where, boolean bastion) {
    PublicKey key = check.seen();
    String type = KeyUtils.getKeyType(key);
    String file = type.contains("ed25519") ? "ed25519" : type.contains("ecdsa") ? "ecdsa" : "rsa";
    String text = "First connection to " + (bastion ? "the bastion " : "") + where.address() + ". Compare the "
        + "fingerprint with ssh-keygen -lf /etc/ssh/ssh_host_" + file + "_key.pub on the host, then trust it.";
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("kind", "host-key");
    data.put("host", where.host());
    data.put("port", where.port());
    data.put("bastion", bastion);
    data.put("keyType", type);
    data.put("fingerprint", HostKeys.fingerprint(key));
    data.put("publicKey", HostKeys.line(key));
    return new TestResult.Confirmation("host-key", text, data, patch(check, key));
  }

  /** A JSON Merge Patch adding the key to the pinned list (arrays are replaced whole, so the old keys stay in it). */
  static ObjectNode patch(HostKeys.Check check, PublicKey key) {
    ObjectNode patch = JsonNodeFactory.instance.objectNode();
    ObjectNode at = patch;
    for (String part : check.pointer.substring(1).split("/")) {
      at = at.putObject(part);
    }
    ArrayNode pinned = at.putArray("pinned");
    check.pinnedLines.forEach(pinned::add);
    pinned.add(HostKeys.line(key));
    return patch;
  }

  static TestResult changed(HostKeys.Check check, SshSettings.Target where, ConnectorException e,
      List<TestResult.Check> checks) {
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    details.put("host", where.host());
    details.put("port", where.port());
    details.put("keyType", KeyUtils.getKeyType(check.seen()));
    details.put("fingerprint", HostKeys.fingerprint(check.seen()));
    details.put("publicKey", HostKeys.line(check.seen()));
    String code = check.verdict() == HostKeys.Verdict.REVOKED ? "ssh.host-key-revoked" : "ssh.host-key-changed";
    return TestResult.failure(code, e.getMessage(), null).withChecks(checks).withDetails(details);
  }

  private static String keyLabel(HostKeys.Check check) {
    PublicKey k = check.seen();
    String how = check.verdict() == HostKeys.Verdict.KNOWN_HOSTS ? "known_hosts" : "pinned";
    return k == null ? null : KeyUtils.getKeyType(k) + " " + HostKeys.fingerprint(k) + ", " + how;
  }

  /** {@code SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.20} → {@code OpenSSH 9.6p1}. */
  static String serverVersion(String banner) {
    if (banner == null) {
      return null;
    }
    String v = banner.startsWith("SSH-2.0-") ? banner.substring(8) : banner;
    int space = v.indexOf(' ');
    String product = space < 0 ? v : v.substring(0, space);
    return product.replaceFirst("_", " ");
  }

  private static String firstWord(String command) {
    String c = command.strip();
    if (c.isEmpty()) {
      return null;
    }
    int end = 0;
    while (end < c.length() && !Character.isWhitespace(c.charAt(end)) && ";&|()<>'\"{".indexOf(c.charAt(end)) < 0) {
      end++;
    }
    String w = c.substring(0, end);
    return w.isEmpty() || w.contains("=") ? null : w;
  }

  private static boolean builtin(String word) {
    return Set.of("cd", "echo", "printf", "test", "[", "true", "false", "set", "export", "exec", "command", "type",
        "read", "cat", "ls").contains(word) || word.startsWith("/");
  }

  private static String one(Map<String, List<String>> facts, String key) {
    List<String> v = facts.get(key);
    return v == null || v.isEmpty() ? null : v.get(0);
  }

  private static TestResult.Check check(String name, long t0, boolean ok, String detail) {
    return new TestResult.Check(name, ok, (System.nanoTime() - t0) / 1_000_000, detail);
  }

  private static String code(ConnectorException e) {
    return switch (e.kind()) {
      case RESOURCE_UNAVAILABLE -> "ssh.unreachable";
      case TIMEOUT -> "ssh.timeout";
      case BAD_INPUT -> "ssh.config";
      case DENIED -> "ssh.host-key";
      default -> "ssh.auth";
    };
  }

  private static String technical(ConnectorException e) {
    Throwable root = e.getCause() == null ? null : SshErrors.root(e.getCause());
    return root == null ? null : root.getClass().getSimpleName() + ": " + root.getMessage();
  }

  private static Duration left(long deadline) {
    return Duration.ofNanos(Math.max(deadline - System.nanoTime(), 1_000_000));
  }
}
