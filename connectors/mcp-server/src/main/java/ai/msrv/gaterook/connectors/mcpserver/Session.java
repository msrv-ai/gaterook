package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import reactor.core.publisher.Mono;

/**
 * One running process (stdio) or HTTP session of a server, from {@code initialize} to its end: the MCP Java SDK's
 * async client (ARCHITECTURE.md A1: the SDK is only a client here) over the gateway's own transports, with no client
 * capability declared — no roots, sampling or elicitation, so a server can't ask the model or the person anything
 * through the gateway (report 11 §6.6).
 */
final class Session {

  /** What a JSON-RPC line or response may be at most. */
  static final int MAX_MESSAGE = 4 * 1024 * 1024;
  /** Explicit, never through the SDK's ServiceLoader (report 15 §9 rule 7). */
  static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(Texts.JSON);
  private static volatile DefaultJsonSchemaValidator validator;

  /** The protocol revisions the SDK client speaks (2.1.0: up to 2025-11-25). */
  static final List<String> REVISIONS = List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

  /**
   * What a start needs.
   *
   * @param toolPrefix   the prefix the server's tools are published under
   * @param npmIntegrity the integrity a catalog server's installed package must have, or {@code null}
   */
  record Request(ConnectionConfig cfg, ServerConfig server, String toolPrefix, String npmIntegrity,
      SecretResolver secrets, ExecContext ctx) {}

  final String connectionId;
  final long configVersion;
  final ServerConfig server;
  final McpAsyncClient client;
  final GatewayStdioTransport stdio;
  final GatewayHttpTransport http;
  final List<String> secretValues;
  final Instant startedAt;
  final ServerLog log;
  final AtomicInteger inFlight = new AtomicInteger();
  volatile McpSchema.InitializeResult init;
  volatile List<McpSchema.Tool> tools = List.of();
  volatile Instant lastUsed;
  volatile Instant lastPing;
  volatile boolean closing;
  volatile long startMillis;
  /** The root's descendants as last seen (the supervisor's tick): what is stopped after the root itself crashed. */
  volatile List<ProcessHandle> known = List.of();
  /** Whether the host heard this session's handshake. */
  final java.util.concurrent.atomic.AtomicBoolean announced = new java.util.concurrent.atomic.AtomicBoolean();
  /** The hash of the tools as last listed ({@link #listHash}): a {@code list_changed} with the same list is no change. */
  volatile String listHash;
  /** Whether a changed list waits to be told to the host, and whether a thread is telling it now. */
  volatile boolean toolsDirty;
  final java.util.concurrent.atomic.AtomicBoolean announcing = new java.util.concurrent.atomic.AtomicBoolean();

  private Session(String connectionId, long configVersion, ServerConfig server, McpAsyncClient client,
      GatewayStdioTransport stdio, GatewayHttpTransport http, List<String> secretValues, Instant startedAt,
      ServerLog log) {
    this.connectionId = connectionId;
    this.configVersion = configVersion;
    this.server = server;
    this.client = client;
    this.stdio = stdio;
    this.http = http;
    this.secretValues = List.copyOf(secretValues);
    this.startedAt = startedAt;
    this.lastUsed = startedAt;
    this.lastPing = startedAt;
    this.log = log;
  }

  /** The root process of a stdio session; {@code null} over HTTP. */
  ProcessHandle root() {
    return stdio == null || stdio.process() == null ? null : stdio.process().toHandle();
  }

  boolean alive() {
    if (closing) {
      return false;
    }
    ProcessHandle root = root();
    return http != null || (root != null && root.isAlive());
  }

  /**
   * Starts the process (or opens the session), answers {@code initialize} and lists every page of tools.
   *
   * @param onToolsChanged what to do when the server said its list changed ({@code notifications/tools/list_changed})
   *                       and the client listed it again; {@link #tools} is the new list by then
   * @param onExit         what to do when the process ends (crash or stop)
   */
  static Session open(Request r, Path workDir, Launcher launcher, ServerLog log, PidFile pids, Instant now,
      Consumer<Session> onToolsChanged, Consumer<Session> onExit) throws ConnectorException {
    long started = System.nanoTime();
    ServerConfig server = r.server();
    Map<String, String> values = new LinkedHashMap<>();
    List<String> secretValues = new ArrayList<>();
    for (ServerConfig.Binding b : server.http() ? server.headers() : server.env()) {
      if (b.secretField() == null) {
        throw ConnectorException.badInput(b.name() + " has no stored secret: give it a value in the server's "
            + "settings.");
      }
      // A String is what an environment variable and a header can hold; the value is tainted for this call.
      try (SecretResolver.Secret s = r.secrets().secret(b.secretField())) {
        String v = s.unsafeString();
        values.put(b.name(), v);
        secretValues.add(v);
      }
    }
    log.secrets(secretValues);
    log.clearTail();
    McpClientTransport transport;
    GatewayStdioTransport stdio = null;
    GatewayHttpTransport http = null;
    if (server.http()) {
      URI url = checkUrl(server.url());
      GuardedEgress guarded = GuardedEgress.of(r.ctx()).orElseThrow(() -> ConnectorException.unavailable(
          "This call has no network access of the gateway's egress guard.", null));
      http = new GatewayHttpTransport(url, guarded.client(), values, MAPPER, MAX_MESSAGE,
          server.callTimeout().plusSeconds(5), server.name());
      transport = http;
    } else {
      prepare(workDir);
      if (r.npmIntegrity() != null) {
        NpmInstaller.verify(workDir, r.npmIntegrity());
      }
      if (server.command().isEmpty()) {
        throw ConnectorException.badInput(server.name() + " has no command to run.");
      }
      String command = server.command().getFirst();
      Path resolved = launcher.resolve(command, workDir).orElseThrow(() -> ConnectorException.unavailable(
          command + " isn't on the PATH GateRook gives servers (" + launcher.path() + "). Install it, or use an "
              + "absolute path.", null));
      List<String> argv = launcher.argv(resolved, server.command().subList(1, server.command().size()), workDir);
      Map<String, String> env = launcher.environment(server, values, workDir);
      stdio = new GatewayStdioTransport(argv, env, workDir, MAPPER, MAX_MESSAGE, log::line, log::mask);
      try {
        Process p = stdio.start();
        pids.record(r.cfg().id(), p.toHandle());
        log.note("Started " + String.join(" ", argv) + " (pid " + p.pid() + ")");
      } catch (IOException e) {
        throw ConnectorException.unavailable("Couldn't start " + command + ": " + Texts.clean(e.getMessage(), 300,
            false), e);
      }
      transport = stdio;
    }
    Session[] self = new Session[1];
    McpAsyncClient client = McpClient.async(transport)
        .clientInfo(McpSchema.Implementation.builder("gaterook", BuiltIn.VERSION).title("GateRook").build())
        // Nothing: the server can't ask for roots, sampling or the person's input through the gateway.
        .capabilities(McpSchema.ClientCapabilities.builder().build())
        .requestTimeout(Duration.ofSeconds(300))
        .initializationTimeout(server.startTimeout())
        .jsonSchemaValidator(validator())
        .toolsChangeConsumer(list -> Mono.fromRunnable(() -> {
          Session current = self[0];
          if (current != null && current.init != null) {
            current.tools = list == null ? List.of() : List.copyOf(list);
            onToolsChanged.accept(current);
          }
        }))
        .loggingConsumer(n -> Mono.fromRunnable(() -> log.line("[" + n.level() + "] " + (n.logger() == null ? ""
            : n.logger() + ": ") + n.data())))
        .build();
    Session s = new Session(r.cfg().id(), r.cfg().version(), server, client, stdio, http, secretValues, now, log);
    self[0] = s;
    if (stdio != null) {
      stdio.process().onExit().thenAccept(p -> onExit.accept(self[0]));
    }
    try {
      Duration budget = min(server.startTimeout(), r.ctx().remaining());
      s.init = s.await(client.initialize(), budget, "initialize");
      if (s.init.capabilities() != null && s.init.capabilities().tools() != null) {
        McpSchema.ListToolsResult listed = s.await(client.listTools(), min(Duration.ofSeconds(30),
            r.ctx().remaining()), "tools/list");
        s.tools = listed.tools() == null ? List.of() : List.copyOf(listed.tools());
      }
      s.listHash = listHash(s.tools);
    } catch (ConnectorException e) {
      s.abort();
      throw s.explain(e);
    } catch (RuntimeException e) {
      s.abort();
      throw s.explain(ConnectorException.unavailable(Texts.clean(String.valueOf(e.getMessage()), 300, false), e));
    }
    s.startMillis = (System.nanoTime() - started) / 1_000_000;
    return s;
  }

  /** The tools again, all pages (Test, Restart, {@code list_changed}). */
  List<McpSchema.Tool> listTools(Duration budget) throws ConnectorException {
    McpSchema.ListToolsResult listed = await(client.listTools(), budget, "tools/list");
    tools = listed.tools() == null ? List.of() : List.copyOf(listed.tools());
    listHash = listHash(tools);
    return tools;
  }

  /** What a list of tools declares, as one hash of their definition hashes in order. */
  static String listHash(List<McpSchema.Tool> tools) {
    StringBuilder out = new StringBuilder();
    for (McpSchema.Tool t : tools) {
      out.append(Snapshots.definitionHash(t)).append(',');
    }
    return Texts.sha256(out.toString());
  }

  /** Stops the session: the client lets go, then the whole process tree is stopped (TERM, then KILL). */
  void close(PidFile pids) {
    closing = true;
    try {
      client.closeGracefully().block(Duration.ofSeconds(4));
    } catch (RuntimeException e) {
      // The process may be gone already.
    }
    ProcessHandle root = root();
    if (root != null || !known.isEmpty()) {
      ProcessTree.stop(root != null && root.isAlive() ? root : null, known, Duration.ofSeconds(3));
    }
    if (root != null) {
      pids.forget(connectionId);
    }
  }

  /** Remembers the root's descendants while it runs (see {@link #known}). */
  void observe() {
    ProcessHandle root = root();
    if (root != null && root.isAlive()) {
      known = root.descendants().toList();
    }
  }

  /** A failed start: nothing may be left running. */
  private void abort() {
    closing = true;
    try {
      client.close();
    } catch (RuntimeException e) {
      // Already broken.
    }
    ProcessHandle root = root();
    if (root != null) {
      ProcessTree.stop(root, Duration.ofSeconds(2));
    }
  }

  /** The start's error with what the process said or did: the gateway's reason, the exit code, stderr's last lines. */
  private ConnectorException explain(ConnectorException e) {
    StringBuilder msg = new StringBuilder(e.getMessage() == null ? "It didn't start." : e.getMessage());
    if (stdio != null && stdio.failure() != null) {
      msg = new StringBuilder(stdio.failure());
    }
    if (stdio != null && stdio.process() != null && !stdio.process().isAlive()) {
      msg.append(" It exited with code ").append(stdio.process().exitValue()).append('.');
    }
    List<String> tail = log.tail();
    if (!tail.isEmpty()) {
      msg.append("\nLast lines on stderr:\n").append(String.join("\n", tail));
    }
    msg.append("\nLog: ").append(log.file());
    return new ConnectorException(e.kind(), msg.toString(), e.getCause());
  }

  /**
   * {@link #block} that also ends when the server's process does: the SDK keeps a request open until its timeout, and
   * a server that died with the answer still owed must not hold a call (or a start) for that long.
   */
  <T> T await(Mono<T> mono, Duration budget, String what) throws ConnectorException {
    Process p = stdio == null ? null : stdio.process();
    if (p == null) {
      return block(mono, budget, what);
    }
    Mono<T> ended = Mono.fromFuture(p.onExit(), true).then(Mono.error(() -> new Ended(stdio.failure() != null
        ? stdio.failure() : "The server's process ended before it answered " + what + ".")));
    return block(Mono.firstWithSignal(mono, ended), budget, what);
  }

  /** The process ended while a request waited for its answer. */
  static final class Ended extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Ended(String message) {
      super(message, null, false, false);
    }
  }

  /**
   * Waits for {@code mono} at most {@code budget}; what the server answered or why not, as the core's error kinds.
   * A server that only speaks 2026-07-28 refuses the legacy {@code initialize} or answers it with that revision.
   */
  static <T> T block(Mono<T> mono, Duration budget, String what) throws ConnectorException {
    if (budget.isZero() || budget.isNegative()) {
      throw ConnectorException.timeout("No time was left for " + what + ".");
    }
    try {
      T value = mono.block(budget);
      if (value == null) {
        throw ConnectorException.unavailable("The server gave no answer to " + what + ".", null);
      }
      return value;
    } catch (RuntimeException e) {
      throw translate(e, budget, what);
    }
  }

  static ConnectorException translate(RuntimeException e, Duration budget, String what) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof Ended ended) {
        return ConnectorException.unavailable(ended.getMessage(), e);
      }
      if (t instanceof McpError mcp) {
        String message = mcpMessage(mcp);
        if (message.contains("2026-07-28")) {
          return ConnectorException.unavailable("Speaks 2026-07-28 only; GateRook connects up to 2025-11-25.",
              e);
        }
        return ConnectorException.resourceError(message, e);
      }
      if (t instanceof TimeoutException || (t instanceof IllegalStateException
          && String.valueOf(t.getMessage()).startsWith("Timeout on blocking read"))) {
        return ConnectorException.timeout("Didn't answer " + what + " in " + seconds(budget) + ".");
      }
      if (t instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return ConnectorException.timeout(what + " was cancelled.");
      }
    }
    return ConnectorException.unavailable(Texts.clean(String.valueOf(rootMessage(e)), 300, false), e);
  }

  /** The text of a JSON-RPC error of the server, with its data when that says more. */
  static String mcpMessage(McpError e) {
    McpSchema.JSONRPCResponse.JSONRPCError error = e.getJsonRpcError();
    if (error == null) {
      return Texts.clean(String.valueOf(e.getMessage()), 500, false);
    }
    String message = error.message() == null ? "error " + error.code() : error.message();
    if (error.data() != null) {
      String data = String.valueOf(error.data());
      if (!data.isBlank() && !message.contains(data)) {
        message = message + " (" + data + ")";
      }
    }
    return Texts.clean(message, 500, false);
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }

  private static String seconds(Duration d) {
    long s = Math.max(1, d.toSeconds());
    return s + " s";
  }

  static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  /**
   * {@code https://…} anywhere the egress guard allows; plain {@code http} only to this computer. The URL is judged as
   * OkHttp reads it — the reading the connection goes by (a backslash is a slash to it, and to no other parser).
   */
  static URI checkUrl(String url) throws ConnectorException {
    if (url == null || url.isBlank()) {
      throw ConnectorException.badInput("The server has no URL.");
    }
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url);
    } catch (UrlRules.UrlRejectedException e) {
      throw ConnectorException.badInput(e.getMessage());
    }
    String host = parsed.host();
    if (parsed.isHttps() || host.equals("127.0.0.1") || host.equals("localhost")) {
      return parsed.uri();
    }
    throw ConnectorException.badInput("Use an https:// URL, or http://127.0.0.1 for a server on this computer.");
  }

  private static void prepare(Path workDir) throws ConnectorException {
    try {
      for (Path dir : List.of(workDir.getParent(), workDir, workDir.resolve("tmp"))) {
        if (!Files.isDirectory(dir)) {
          Files.createDirectories(dir);
          try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
          } catch (UnsupportedOperationException e) {
            // Windows: the home folder's ACL protects it.
          }
        }
      }
    } catch (IOException e) {
      throw ConnectorException.unavailable("Couldn't create the server's folder " + workDir + ": " + e.getMessage(),
          e);
    }
  }

  private static DefaultJsonSchemaValidator validator() {
    DefaultJsonSchemaValidator v = validator;
    if (v == null) {
      v = new DefaultJsonSchemaValidator(Texts.JSON);
      validator = v;
    }
    return v;
  }
}
