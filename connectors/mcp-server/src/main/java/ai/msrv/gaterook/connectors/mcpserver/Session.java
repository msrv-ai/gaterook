package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One running process (stdio) or HTTP session of a server, from its handshake to its end: the gateway's own
 * {@link UpstreamClient} (ARCHITECTURE.md A1) over a {@link Channel} — the process's stdio, Streamable HTTP, or the
 * 2024-11-05 HTTP+SSE transport of a URL that takes no Streamable HTTP — with no client capability declared, so a server
 * can't ask the model or the person anything through the gateway (report 11 §6.6).
 *
 * <p>A new server and a person's Test, Start or Restart find out the revision (2026-07-28 first); a call of a stored
 * server speaks the era its snapshot recorded, so a working server is talked to as it was reviewed.
 */
final class Session {

  /** What a JSON-RPC line or response may be at most. */
  static final int MAX_MESSAGE = 4 * 1024 * 1024;
  /** Explicit, never through the SDK's ServiceLoader (report 15 §9 rule 7): only the tools' types for their hash. */
  static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(Texts.JSON);
  /** How many tools of one server are listed at most. */
  static final int MAX_TOOLS = 1_000;

  /** The revisions the client speaks, newest first. */
  static final List<String> REVISIONS = List.of(UpstreamClient.MODERN, "2025-11-25", "2025-06-18", "2025-03-26",
      "2024-11-05");

  /** How long a stdio server may take to answer {@code server/discover} before it counts as one of 2025. */
  static final Duration PROBE = Duration.ofSeconds(10);

  /**
   * What a start needs.
   *
   * @param toolPrefix    the prefix the server's tools are published under
   * @param npmIntegrity  the integrity a catalog server's installed package must have, or {@code null}
   * @param authorization the core's sign-in for every request of an HTTP session, or {@code null}
   * @param credentials   what opens the server's cloud credentials when a session starts, or {@code null}
   * @param era           the era to speak, or {@code null} to find out (a new server, a person's start)
   */
  record Request(ConnectionConfig cfg, ServerConfig server, String toolPrefix, String npmIntegrity,
      SecretResolver secrets, ExecContext ctx, McpServerRuntime.Authorization authorization, Credentials credentials,
      UpstreamClient.Era era) {

    Request(ConnectionConfig cfg, ServerConfig server, String toolPrefix, String npmIntegrity, SecretResolver secrets,
        ExecContext ctx) {
      this(cfg, server, toolPrefix, npmIntegrity, secrets, ctx, null, null, null);
    }

    Request(ConnectionConfig cfg, ServerConfig server, String toolPrefix, String npmIntegrity, SecretResolver secrets,
        ExecContext ctx, McpServerRuntime.Authorization authorization) {
      this(cfg, server, toolPrefix, npmIntegrity, secrets, ctx, authorization, null, null);
    }

    Request(ConnectionConfig cfg, ServerConfig server, String toolPrefix, String npmIntegrity, SecretResolver secrets,
        ExecContext ctx, McpServerRuntime.Authorization authorization, Credentials credentials) {
      this(cfg, server, toolPrefix, npmIntegrity, secrets, ctx, authorization, credentials, null);
    }

    /** The era a reviewed snapshot recorded ({@code null}: find out). */
    static UpstreamClient.Era eraOf(String protocolVersion) {
      if (protocolVersion == null) {
        return null;
      }
      return UpstreamClient.MODERN.equals(protocolVersion) ? UpstreamClient.Era.MODERN
          : UpstreamClient.LEGACY.contains(protocolVersion) ? UpstreamClient.Era.LEGACY : null;
    }
  }

  /**
   * Opens the cloud credentials of an HTTP session (a Google token, SigV4) when it starts: a call that finds the
   * server running never opens them again.
   */
  interface Credentials {
    RequestCredentials open() throws ConnectorException;
  }

  final String connectionId;
  final long configVersion;
  final ServerConfig server;
  final UpstreamClient client;
  final GatewayStdioTransport stdio;
  /** The requests of an HTTP session (Streamable HTTP or SSE); {@code null} over stdio. */
  final HttpCalls http;
  final List<String> secretValues;
  final Instant startedAt;
  final ServerLog log;
  final AtomicInteger inFlight = new AtomicInteger();
  volatile UpstreamClient.Hello init;
  volatile List<ObjectNode> tools = List.of();
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
  /** Whether the server took no Streamable HTTP and speaks the HTTP+SSE transport of 2024-11-05. */
  volatile boolean sse;

  /**
   * A list of what the server shares, as an endpoint publishes it, kept until the server says it changed or
   * {@link #LIST_TTL} passed; for prompts also the server's name of each published one.
   */
  record Listed(ObjectNode list, Map<String, String> originals, Instant until) {

    boolean fresh(Instant now) {
      return now.isBefore(until);
    }
  }

  static final Duration LIST_TTL = Duration.ofSeconds(30);
  volatile Listed resources;
  volatile Listed templates;
  volatile Listed prompts;

  private Session(String connectionId, long configVersion, ServerConfig server, UpstreamClient client,
      GatewayStdioTransport stdio, HttpCalls http, List<String> secretValues, Instant startedAt, ServerLog log) {
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
    if (closing || client.closed()) {
      return false;
    }
    ProcessHandle root = root();
    return http != null || (root != null && root.isAlive());
  }

  /** How the session reaches the server: {@code stdio}, {@code http} (Streamable HTTP) or {@code sse} (2024-11-05). */
  String transport() {
    return stdio != null ? ServerConfig.STDIO : sse ? "sse" : ServerConfig.HTTP;
  }

  /** The revision both agreed on ({@code 2026-07-28}, {@code 2025-11-25}). */
  String protocolVersion() {
    UpstreamClient.Hello h = init;
    return h == null ? null : h.protocolVersion();
  }

  /**
   * Starts the process (or opens the session), agrees on a revision and lists every page of tools.
   *
   * @param onToolsChanged what to do when the server said its list changed and it was listed again; {@link #tools} is
   *                       the new list by then
   * @param onExit         what to do when the process ends (crash or stop)
   */
  static Session open(Request r, Path workDir, Launcher launcher, ServerLog log, PidFile pids, Instant now,
      Consumer<Session> onToolsChanged, Consumer<Session> onExit) throws ConnectorException {
    return open(r, workDir, launcher, log, pids, now, onToolsChanged, onExit, (what, uri) -> {});
  }

  /**
   * @param onNotice what the server says outside any call besides its tools: {@code resources} or {@code prompts}
   *                 changed ({@code uri} null), or the resource {@code uri} (the server's) was {@code updated}
   */
  static Session open(Request r, Path workDir, Launcher launcher, ServerLog log, PidFile pids, Instant now,
      Consumer<Session> onToolsChanged, Consumer<Session> onExit, java.util.function.BiConsumer<String, String> onNotice)
      throws ConnectorException {
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
    Channel channel;
    GatewayStdioTransport stdio = null;
    HttpCalls http = null;
    if (server.http()) {
      URI url = checkUrl(server.url());
      GuardedEgress guarded = GuardedEgress.of(r.ctx()).orElseThrow(() -> ConnectorException.unavailable(
          "This call has no network access of the gateway's egress guard.", null));
      if (server.oauth() && r.authorization() == null && !server.signInOptional()) {
        throw ConnectorException.unavailable(server.name() + " isn't signed in.", new McpServerRuntime.SignInRequired(
            server.name() + " isn't signed in. Sign in to it in GateRook › Connectors › MCP servers."));
      }
      // A server whose sign-in is optional connects without one until the person signs in.
      RequestCredentials cloud = r.credentials() == null ? null : r.credentials().open();
      http = new HttpCalls(url, guarded.client(), values, MAX_MESSAGE, server.callTimeout().plusSeconds(5),
          server.name(), server.oauth() ? r.authorization() : null, cloud);
      channel = new GatewayHttpTransport(http);
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
      List<String> argv = launcher.argv(resolved, server.command().subList(1, server.command().size()), workDir,
          server.offThisComputer());
      Map<String, String> env = launcher.environment(server, values, workDir);
      stdio = new GatewayStdioTransport(argv, env, workDir, MAX_MESSAGE, log::line, log::mask);
      try {
        Process p = stdio.start();
        pids.record(r.cfg().id(), p.toHandle());
        log.note("Started " + String.join(" ", argv) + " (pid " + p.pid() + ")");
      } catch (IOException e) {
        throw ConnectorException.unavailable("Couldn't start " + command + ": " + Texts.clean(e.getMessage(), 300,
            false), e);
      }
      channel = stdio;
    }
    Session[] self = new Session[1];
    UpstreamClient client = new UpstreamClient(channel, clientInfo(), server.name(), events(self, log,
        onToolsChanged, onNotice));
    Session s = new Session(r.cfg().id(), r.cfg().version(), server, client, stdio, http, secretValues, now, log);
    self[0] = s;
    if (stdio != null) {
      GatewayStdioTransport own = stdio;
      own.process().onExit().thenAccept(p -> {
        // An answer still owed never comes: the request fails now, not at its timeout.
        client.closed(own.failure() != null ? own.failure() : "The server's process ended before it answered.");
        onExit.accept(self[0]);
      });
    }
    try {
      Duration budget = min(server.startTimeout(), r.ctx().remaining());
      try {
        s.init = client.connect(r.era(), PROBE, budget);
      } catch (ConnectorException e) {
        Session sse = http != null && noStreamableHttp(e)
            ? viaSse(r, s, http, log, now, self, onToolsChanged, onNotice, e, started) : null;
        if (sse == null) {
          throw e;
        }
        return sse;
      }
      s.afterConnect(r);
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

  /** The tools of a session that just agreed on a revision. */
  private void afterConnect(Request r) throws ConnectorException {
    if (init.has("tools")) {
      tools = client.list("tools/list", "tools", min(Duration.ofSeconds(30), r.ctx().remaining()), MAX_TOOLS);
    }
    listHash = listHash(tools);
  }

  /** The server's URIs of the resources clients subscribed to through the gateway. */
  private volatile java.util.Set<String> subscribed = java.util.Set.of();

  /**
   * Hears what changes and updates from now on: the tool, resource and prompt lists the server offers to tell about,
   * and the updates of {@code uris} (the server's) when it takes subscriptions. Its stream opens when the server offers
   * to tell anything, and only for a session that serves calls, never a throw-away review.
   */
  synchronized void subscribe(java.util.Set<String> uris) {
    java.util.Set<String> before = subscribed;
    subscribed = java.util.Set.copyOf(uris);
    if (init == null) {
      return;
    }
    JsonNode caps = init.capabilities();
    boolean tells = caps.path("tools").path("listChanged").asBoolean(false)
        || caps.path("resources").path("listChanged").asBoolean(false)
        || caps.path("prompts").path("listChanged").asBoolean(false)
        || (caps.path("resources").path("subscribe").asBoolean(false) && !subscribed.isEmpty());
    if (tells) {
      client.listen(List.copyOf(subscribed));
    }
    client.subscriptions(before, subscribed);
  }

  /**
   * A URL whose POSTs are refused (404, 405 and the like) as Streamable HTTP's backwards compatibility says: maybe a
   * server of the 2024-11-05 HTTP+SSE transport, which takes a GET there and names its message endpoint.
   */
  private static boolean noStreamableHttp(ConnectorException e) {
    if (McpServerRuntime.SignInRequired.in(e) != null) {
      return false;
    }
    Channel.Failure f = Channel.Failure.in(e);
    if (f == null) {
      return false;
    }
    int status = f.status();
    return status == 400 || status == 404 || status == 405 || status == 406 || status == 415;
  }

  /**
   * The same server over the HTTP+SSE transport. When it isn't one either, the Streamable HTTP failure is the
   * answer: that is the transport the URL was given for.
   */
  private static Session viaSse(Request r, Session failed, HttpCalls http, ServerLog log, Instant now,
      Session[] self, Consumer<Session> onToolsChanged, java.util.function.BiConsumer<String, String> onNotice,
      ConnectorException original, long started) throws ConnectorException {
    failed.closing = true;
    failed.client.close();
    GatewaySseTransport sse = new GatewaySseTransport(http);
    UpstreamClient client = new UpstreamClient(sse, clientInfo(), r.server().name(), events(self, log,
        onToolsChanged, onNotice));
    Session s = new Session(r.cfg().id(), r.cfg().version(), r.server(), client, null, http, failed.secretValues, now,
        log);
    self[0] = s;
    try {
      s.init = client.connect(UpstreamClient.Era.LEGACY, PROBE, min(r.server().startTimeout(), r.ctx().remaining()));
      s.afterConnect(r);
    } catch (ConnectorException e) {
      client.close();
      if (!sse.opened()) {
        // No event stream there either: the Streamable HTTP failure says what is wrong with the URL.
        throw original;
      }
      throw e;
    }
    s.sse = true;
    log.note("Connected over the HTTP+SSE transport of 2024-11-05: the URL takes no Streamable HTTP POST.");
    s.startMillis = (System.nanoTime() - started) / 1_000_000;
    return s;
  }

  private static ObjectNode clientInfo() {
    ObjectNode info = JsonNodeFactory.instance.objectNode();
    info.put("name", "gaterook");
    info.put("title", "GateRook");
    info.put("version", BuiltIn.VERSION);
    return info;
  }

  /** What the session does about what isn't an answer: a changed tool list is listed again; logs go to its log. */
  private static UpstreamClient.Events events(Session[] self, ServerLog log, Consumer<Session> onToolsChanged,
      java.util.function.BiConsumer<String, String> onNotice) {
    return new UpstreamClient.Events() {
      @Override
      public void toolsChanged() {
        Session current = self[0];
        if (current == null || current.init == null || current.closing) {
          return;
        }
        try {
          current.tools = current.client.list("tools/list", "tools", Duration.ofSeconds(30), MAX_TOOLS);
        } catch (ConnectorException e) {
          log.note("Couldn't list the tools again after the server said they changed: " + e.getMessage());
          return;
        }
        onToolsChanged.accept(current);
      }

      @Override
      public void resourcesChanged() {
        Session current = self[0];
        if (current != null) {
          current.resources = null;
          current.templates = null;
        }
        onNotice.accept("resources", null);
      }

      @Override
      public void promptsChanged() {
        Session current = self[0];
        if (current != null) {
          current.prompts = null;
        }
        onNotice.accept("prompts", null);
      }

      @Override
      public void resourceUpdated(String uri) {
        onNotice.accept("updated", uri);
      }

      @Override
      public void log(ObjectNode params) {
        JsonNode data = params.get("data");
        String logger = Texts.str(params.get("logger"), null);
        log.line("[" + Texts.str(params.get("level"), "info") + "] " + (logger == null ? "" : logger + ": ")
            + (data == null ? "" : data.isString() ? data.asString() : data.toString()));
      }
    };
  }

  /** The tools again, all pages (Test, Restart). */
  List<ObjectNode> listTools(Duration budget) throws ConnectorException {
    tools = client.list("tools/list", "tools", budget, MAX_TOOLS);
    listHash = listHash(tools);
    return tools;
  }

  /** What a list of tools declares, as one hash of their definition hashes and apps in order. */
  static String listHash(List<ObjectNode> tools) {
    StringBuilder out = new StringBuilder();
    for (ObjectNode t : tools) {
      out.append(Snapshots.definitionHash(t)).append(',');
      ObjectNode app = Snapshots.appOf(t);
      if (app != null) {
        out.append(app).append(',');
      }
    }
    return Texts.sha256(out.toString());
  }

  /** The tool the server lists now under its own name, or {@code null}. */
  ObjectNode tool(String original) {
    for (ObjectNode t : tools) {
      if (original.equals(Texts.str(t.get("name"), null))) {
        return t;
      }
    }
    return null;
  }

  /** Stops the session: the client lets go, then the whole process tree is stopped (TERM, then KILL). */
  void close(PidFile pids) {
    closing = true;
    try {
      client.close();
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

  /** The work folder, its {@code tmp} ({@code TMPDIR}) and the folder of all work folders, owner-only when new. */
  static void prepare(Path workDir) throws ConnectorException {
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
}
