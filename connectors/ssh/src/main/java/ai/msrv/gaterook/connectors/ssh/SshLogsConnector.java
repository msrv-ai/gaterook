package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.impl.AbstractSftpClient;
import tools.jackson.databind.node.JsonNodeFactory;

/** Selected remote log files over SFTP. No shell or write-capable operation is exposed. */
public final class SshLogsConnector implements Connector {
  public static final String TYPE = "ssh-logs";
  static final String LIST = "ssh_logs_list";
  static final String READ = "ssh_logs_read";
  static final String SEARCH = "ssh_logs_search";
  private final ConnectorManifest manifest;
  private final byte[] scanFingerprintKey = new byte[32];
  private final SshClients clients;
  private final SshSessions sessions;
  private final Map<String, Integer> parallel = new ConcurrentHashMap<>();
  private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

  public SshLogsConnector() {
    MinaSecurity.install();
    new java.security.SecureRandom().nextBytes(scanFingerprintKey);
    SchemaResources schemas = SchemaResources.of(SshLogsConnector.class, "ssh-logs");
    manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("SSH log files").icon("file-text").group("Logs")
        .description("Read selected log files over SSH without running shell commands.")
        .configSchema(schemas.load("config"))
        .policySchema(SchemaResources.of(SshConnector.class, "ssh").load("policy"))
        .tools(List.of(tool(LIST, "List allowed log files", "List only the explicitly selected log files and their sizes.", schemas),
            tool(READ, "Read a log file", "Read complete UTF-8 lines from a selected log file. Set tail for the actual latest N lines. "
                + "Scanning, output and deadline limits return an explicit continuation cursor.", schemas),
            tool(SEARCH, "Search a log file", "Search selected text or JSONL logs by literal text or a safe regular expression, time range and level. "
                + "Scans beyond the output window; follow nextCursor with the same filters until scanComplete.", schemas)))
        .build();
    clients = new SshClients(BuiltIn.VERSION);
    sessions = new SshSessions(clients);
  }

  private static ToolDefinition tool(String name, String title, String description, SchemaResources schemas) {
    return ToolDefinition.builder(name).title(title).description(description)
        .inputSchema(schemas.load(name + ".input")).outputSchema(schemas.load(name.equals(LIST) ? "list.output" : "read.output"))
        .build();
  }

  @Override
  public int maxConcurrency() {
    return 16;
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    try {
      SshLogSettings settings = SshLogSettings.of(cfg, true);
      validateCall(call, settings, policy.limits());
      List<String> methods = policy.strings("allowedCredentialMethods");
      String method = settings.privateKeyPath() == null ? "password" : "privateKey";
      boolean allowed = policy.section().has("allowedCredentialMethods") ? methods.contains(method)
          : !cfg.env().id().equals("prod") || method.equals("privateKey");
      if (!allowed) {
        return Classification.deny(policy.rule("credential-method"), "This sign-in method is not allowed by the SSH policy.",
            "Choose an allowed sign-in method in the connection settings.");
      }
      parallel.put(cfg.id(), Math.max(1, Math.min(16, policy.integer("maxConcurrentCalls", cfg.env().id().equals("prod") ? 2 : 4))));
      return Classification.read();
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("log-selection"), e.getMessage(), "Review the selected folder and log files.");
    }
  }

  private static void validateCall(ToolCall call, SshLogSettings settings, ai.msrv.gaterook.connector.api.Limits limits) throws ConnectorException {
    if (LIST.equals(call.tool())) {
      return;
    }
    if (!READ.equals(call.tool()) && !SEARCH.equals(call.tool())) {
      throw ConnectorException.badInput("Unknown SSH log tool.");
    }
    SshLogQuery.parse(call, settings, limits, java.time.Instant.now());
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", call.arguments().toString(), cfg.name(),
        "Read selected remote log files");
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return inspect(cfg, secrets, ctx, null);
  }

  @Override
  public TestResult browseFiles(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path) {
    return inspect(cfg, secrets, ctx, path == null || path.isBlank() ? "/" : path);
  }

  private TestResult inspect(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path) {
    HostKeys.Check check = null;
    SshLogSettings settings = null;
    long started = System.nanoTime();
    try {
      settings = SshLogSettings.of(cfg, path == null);
      SshLogParser.of(settings);
      check = HostKeys.check(settings.ssh().target(), settings.ssh().hostKeys());
      try (Connection opened = open(settings, secrets, ctx, check)) {
        var details = path == null ? JsonNodeFactory.instance.objectNode() : SshLogFiles.browse(opened.sftp(), path, ctx);
        if (path == null) {
          SshLogFiles.directory(opened.sftp(), settings.root(), ctx);
          for (String file : settings.allowedFiles()) {
            SshLogFiles.file(opened.sftp(), settings, file, ctx);
            try (var handle = opened.sftp().open(settings.allowed(file), SftpClient.OpenMode.Read)) {
              SshLogFiles.file(opened.sftp(), settings, file, ctx);
              if (!opened.sftp().stat(handle).isRegularFile()) {
                throw ConnectorException.denied("Only regular log files can be read.");
              }
            }
          }
          details.put("root", settings.root()).put("selectedFiles", settings.allowedFiles().size());
        }
        return TestResult.success((System.nanoTime() - started) / 1_000_000,
            SshProbe.serverVersion(opened.session().getServerVersion()), settings.ssh().target().label(),
            new TestResult.Permissions(TestResult.Level.READ, "Selected log files over SFTP", "No write tools", List.of(), null))
            .withDetails(details);
      }
    } catch (ConnectorException e) {
      if (check != null && check.verdict() == HostKeys.Verdict.UNKNOWN && check.seen() != null) {
        return TestResult.confirm(SshProbe.trust(check, settings.ssh().target(), false), List.of());
      }
      if (check != null && (check.verdict() == HostKeys.Verdict.CHANGED || check.verdict() == HostKeys.Verdict.REVOKED)
          && check.seen() != null) {
        return SshProbe.changed(check, settings.ssh().target(), e, List.of());
      }
      return TestResult.failure("ssh-logs.connection", e.getMessage(), null);
    } catch (IOException e) {
      return TestResult.failure("ssh-logs.sftp", "Cannot read the remote folder or files. Check the path, permissions and SFTP support.", null);
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    SshLogSettings settings = SshLogSettings.of(cfg, true);
    validateCall(call, settings, ctx.limits());
    String slotKey = cfg.id() + "@" + cfg.version() + ":" + parallel.getOrDefault(cfg.id(), 2);
    Semaphore slot = slots.computeIfAbsent(slotKey, ignored -> new Semaphore(parallel.getOrDefault(cfg.id(), 2)));
    boolean acquired = false;
    try {
      acquired = slot.tryAcquire(200, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw ConnectorException.unavailable("This SSH log connection is busy. Try again shortly.", null);
      }
      var check = HostKeys.check(settings.ssh().target(), settings.ssh().hostKeys());
      try (Connection opened = open(settings, secrets, ctx, check)) {
        return LIST.equals(call.tool()) ? SshLogFiles.list(opened.sftp(), settings, ctx)
            : SshLogScanner.scan(opened.sftp(), settings, cfg, call, ctx, scanFingerprintKey);
      }
    } catch (IOException e) {
      if (ctx.cancelled() || ctx.remaining().isZero() || Thread.currentThread().isInterrupted()) {
        throw ConnectorException.timeout("The SSH log operation timed out or was cancelled.");
      }
      throw ConnectorException.resourceError("Cannot read this remote log. Check the selected path, permissions and SFTP support.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The SSH log operation was cancelled.");
    } finally {
      if (acquired) {
        slot.release();
      }
    }
  }

  private Connection open(SshLogSettings settings, SecretResolver secrets, ExecContext ctx, HostKeys.Check check)
      throws ConnectorException, IOException {
    var ssh = settings.ssh();
    if (ctx.cancelled() || ctx.remaining().isZero()) {
      throw ConnectorException.timeout("The SSH log operation was cancelled.");
    }
    ClientSession session = sessions.connect(ssh, ssh.target(), ssh.target().host(), ssh.target().port(), check, ctx.remaining());
    ctx.onCancel(() -> session.close(true));
    try {
      SshLogins.Login login;
      if (settings.privateKeyPath() == null) {
        login = SshLogins.login(ssh.credentials(), ssh.target(), secrets, "gw:" + ctx.callId(), false);
      } else {
        // Read after host verification. Each operation rereads the file, so key replacement takes effect immediately.
        if (!Files.isRegularFile(settings.privateKeyPath())) {
          throw ConnectorException.badInput("The local private key path must point to a regular file.");
        }
        byte[] data;
        try (var input = Files.newInputStream(settings.privateKeyPath())) {
          data = input.readNBytes(20001);
        } catch (IOException e) {
          throw ConnectorException.resourceError("Cannot read the local private key file. Check its path and permissions.", null);
        }
        if (data.length > 20000) {
          Arrays.fill(data, (byte) 0);
          throw ConnectorException.badInput("The local private key file is larger than 20,000 bytes.");
        }
        char[] key = new String(data, StandardCharsets.US_ASCII).toCharArray();
        Arrays.fill(data, (byte) 0);
        try (SecretResolver.Secret passphrase = secrets.isSet("/credentials/passphrase")
            ? secrets.secret("/credentials/passphrase") : null) {
          ctx.taint(new String(key));
          KeyPair pair = PrivateKeys.parse(key, passphrase == null ? null : passphrase.chars(), "private key file");
          login = new SshLogins.Login(SshSettings.Method.PRIVATE_KEY, null, pair, null, null, 0);
        } finally {
          Arrays.fill(key, '\0');
        }
      }
      login.taint(ctx);
      sessions.authenticate(session, login, ctx.remaining(), ssh.target(), check);
      SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(session, ctx.remaining());
      AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT.set(session, ctx.remaining());
      SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
      return new Connection(session, sftp);
    } catch (ConnectorException | IOException | RuntimeException e) {
      session.close(true);
      throw e;
    }
  }

  private record Connection(ClientSession session, SftpClient sftp) implements AutoCloseable {
    @Override
    public void close() {
      try {
        sftp.close();
      } catch (IOException ignored) {
        // Closing the session also closes its SFTP channel.
      } finally {
        session.close(true);
      }
    }
  }

  @Override
  public void onConnectionChanged(String id) {
    slots.keySet().removeIf(key -> key.startsWith(id + "@"));
    parallel.remove(id);
  }

  @Override
  public void onConnectionRemoved(String id) {
    onConnectionChanged(id);
  }

  @Override
  public void close() {
    Arrays.fill(scanFingerprintKey, (byte) 0);
    sessions.close();
    clients.close();
  }
}
