package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.io.IOException;
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
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import tools.jackson.databind.node.JsonNodeFactory;

/** Selected remote log files through bounded, application-generated read commands and SFTP browsing. */
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
        .description("Read and search selected log files over SSH with bounded server-side commands.")
        .configSchema(schemas.load("config"))
        .policySchema(SchemaResources.of(SshConnector.class, "ssh").load("policy"))
        .tools(List.of(tool(LIST, "List allowed log files", "List explicitly selected log files and their sizes. "
                + "Optionally filter root-relative file names by literal text or a safe regular expression with query, "
                + "matchMode and caseSensitive. Use returned file names with ssh_logs_read or ssh_logs_search.", schemas),
            tool(READ, "Read a log file", "Read complete UTF-8 lines from a selected log file. Set tail for the latest N lines, or startLine/endLine for a line range. "
                + "Scanning, output and deadline limits return an explicit continuation cursor.", schemas),
            tool(SEARCH, "Search a log file", "Search selected text or JSONL logs by literal text or a safe regular expression, time range and level. "
                + "GateRook selects server-side grep/head/tail commands, including for tail and level filters. "
                + "Omit scanBytes for normal searches; use maxLines to bound the answer. "
                + "Follow nextCursor with the same filters until scanComplete.", schemas)))
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
      SshLogFileQuery.parse(call.arguments());
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
    return inspect(cfg, secrets, ctx, null, true);
  }

  /** Reachability checks authenticate SFTP and the selected root; their cost does not grow with file selection. */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    TestResult result = inspect(cfg, secrets, ctx, null, false);
    return result.ok() ? HealthResult.healthy(result.latencyMs()) : HealthResult.unreachable(
        result.error() == null ? "The SSH host key needs confirmation. Test the connection and verify its fingerprint."
            : result.error().message());
  }

  @Override
  public TestResult browseFiles(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path) {
    return inspect(cfg, secrets, ctx, path == null || path.isBlank() ? "/" : path, false);
  }

  private TestResult inspect(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path,
      boolean checkFiles) {
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
          if (checkFiles) {
            for (String file : settings.allowedFiles()) {
              try {
                SshLogFiles.inspectFile(opened.sftp(), settings, file, ctx);
                try (var handle = opened.sftp().open(settings.allowed(file), SftpClient.OpenMode.Read)) {
                  SshLogFiles.inspectFile(opened.sftp(), settings, file, ctx);
                  SshLogFiles.budget(opened.sftp(), ctx);
                  if (!opened.sftp().stat(handle).isRegularFile()) {
                    throw ConnectorException.denied("Only regular log files can be read.");
                  }
                }
              } catch (IOException e) {
                throw selectedFileError(file, e, ctx);
              }
            }
            SshLogFiles.directory(opened.sftp(), settings.root(), ctx);
          }
          details.put("root", settings.root()).put("selectedFiles", settings.allowedFiles().size());
        }
        String version = SshProbe.serverVersion(opened.session().getServerVersion());
        return TestResult.success((System.nanoTime() - started) / 1_000_000,
            version, settings.ssh().target().label(),
            new TestResult.Permissions(TestResult.Level.READ, "Selected log files over SSH", "No write tools", List.of(), null))
            .withDetails(details).withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", version));
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

  private static ConnectorException selectedFileError(String file, IOException failure, ExecContext ctx) {
    if (ctx.cancelled() || ctx.remaining().isZero() || Thread.currentThread().isInterrupted()) {
      return ConnectorException.timeout("The selected log files could not all be checked before the deadline.");
    }
    if (failure instanceof SftpException sftp) {
      if (sftp.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
        return ConnectorException.resourceError("Cannot read selected log file " + file
            + ". Grant this SSH account read access or remove the file from the selection.", failure);
      }
      if (sftp.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE
          || sftp.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
        return ConnectorException.resourceError("Selected log file " + file
            + " no longer exists. Choose its current name or remove it from the selection.", failure);
      }
    }
    return ConnectorException.resourceError("Cannot inspect selected log file " + file
        + ". Check its path, permissions and the SFTP connection.", failure);
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
      try (Connection opened = open(settings, secrets, ctx, check, LIST.equals(call.tool()))) {
        return LIST.equals(call.tool()) ? SshLogFiles.list(opened.sftp(), settings, SshLogFileQuery.parse(call.arguments()), ctx)
            : SshLogScanner.scan(opened.session(), settings, cfg, call, ctx, scanFingerprintKey);
      }
    } catch (IOException e) {
      if (ctx.cancelled() || ctx.remaining().isZero() || Thread.currentThread().isInterrupted()) {
        throw ConnectorException.timeout("The SSH log operation timed out or was cancelled.");
      }
      throw ConnectorException.resourceError("Cannot read this remote log. Check the selected path, permissions and SSH command support.", e);
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
    return open(settings, secrets, ctx, check, true);
  }

  private Connection open(SshLogSettings settings, SecretResolver secrets, ExecContext ctx, HostKeys.Check check,
      boolean sftpRequired) throws ConnectorException, IOException {
    var ssh = settings.ssh();
    if (ctx.cancelled() || ctx.remaining().isZero()) {
      throw ConnectorException.timeout("The SSH log operation was cancelled.");
    }
    ClientSession session = sessions.connect(ssh, ssh.target(), ssh.target().host(), ssh.target().port(), check, ctx.remaining());
    var commandPhase = new java.util.concurrent.atomic.AtomicBoolean();
    ctx.onCancel(() -> {
      // Command cancellation must send TERM/KILL before the authenticated session is closed.
      if (!commandPhase.get()) session.close(true);
    });
    try {
      SshLogins.Login login = SshLogins.login(ssh.credentials(), ssh.target(), secrets, "gw:" + ctx.callId(), false, ctx);
      login.taint(ctx);
      sessions.authenticate(session, login, ctx.remaining(), ssh.target(), check);
      if (!sftpRequired) {
        commandPhase.set(true);
        return new Connection(session, null);
      }
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
        if (sftp != null) sftp.close();
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
