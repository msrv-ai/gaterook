package ai.msrv.gaterook.connectors.support.mcp;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * External MCP servers as the core sees them (ARCHITECTURE.md §7.5, step C10): the processes and HTTP sessions behind
 * connections of type {@code mcp-server}, run by the {@code connectors/mcp-server} module, and the snapshots of their
 * tools, kept by the core's {@code mcpservers} module. The core never sees the module's classes: it finds this bean,
 * attaches itself as the {@link Host} and asks for one {@link Connector} per connection.
 *
 * <p>What the core stores is the person's review: a snapshot of the tools as they were listed, each with its
 * {@code exposed} name (the connection's prefix and the upstream name), its definition and a hash of what the server
 * declared. Agents get {@code tools/list} from the snapshot, never from the server; a server that lists something else
 * after a restart hands the core a new {@link Handshake}, and the core decides what that changes on the endpoints
 * (new tools Off, a changed one loses its Read only confirmation, a gone one leaves the endpoints).
 *
 * <p>Snapshot entries are JSON objects of this module's making; the core relies on these keys only:
 * {@code exposed}, {@code original}, {@code definitionHash} (SHA-256 of what the server declared for the tool) and
 * {@code annotations} (as declared, or absent).
 */
public interface McpServerRuntime extends AutoCloseable {

  /** The core attaches once, before it asks for anything else. */
  void attach(Host host);

  /**
   * The connector of one stored connection, serving the tools of {@code snapshot}. Cheap and starts nothing: the
   * process starts on the first call that needs it (or right away when the server is set to start with the core).
   */
  Connector connector(ConnectionConfig cfg, Snapshot snapshot);

  /** What would run for {@code cfg}, without running it: the command as resolved, Node, variables, sandbox. */
  Review review(ConnectionConfig cfg);

  /**
   * Starts a throw-away process (or HTTP session) for a config that isn't saved yet, lists its tools and stops it:
   * Review and start, and Test connection of a draft.
   *
   * @throws ConnectorException when it doesn't start, doesn't answer {@code initialize} in time, or speaks a protocol
   *                            this client doesn't; the message says what happened and what to do
   */
  Handshake probe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) throws ConnectorException;

  /**
   * Starts the server of a stored connection, or lists its tools again when it runs; {@code restart} stops a running
   * process first. The host hears the handshake as it does for every start.
   */
  Handshake start(ConnectionConfig cfg, Snapshot snapshot, SecretResolver secrets, ExecContext ctx, boolean restart)
      throws ConnectorException;

  /**
   * Starts the server of a stored connection the way a call would (a server set to start with the core, after the
   * core started or after a backoff): refused while it backs off or failed, and nothing happens when it runs.
   */
  void warm(ConnectionConfig cfg, Snapshot snapshot, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException;

  /** Stops the process (or session) of a connection and its whole process tree; idempotent. */
  void stop(String connectionId);

  /** The process of a connection right now, or empty when it never ran in this core. */
  Optional<ProcessView> process(String connectionId);

  /**
   * Installs a pinned npm package of a catalog entry into the server's work folder ({@code npm ci --ignore-scripts}
   * from the entry's lockfile) and checks its integrity against the entry: the command to run it and what the start
   * checks every time.
   */
  NpmInstall install(String serverName, NpmPackage pkg, Duration timeout) throws ConnectorException;

  /** Stops every process (core shutdown). */
  @Override
  void close();

  /** What the core gives the runtime. */
  interface Host {

    /** The gateway's home ({@code ~/.mcp-gateway}): work folders {@code mcp/<name>}, logs {@code logs/mcp/}. */
    Path home();

    /** Why no tool of an MCP server may be called {@code toolName} (a built-in or core name), or empty. */
    Optional<String> reservedProblem(String toolName);

    /** A server started (or listed its tools again): what it said about itself and the tools it lists now. */
    void handshake(String connectionId, Handshake handshake);

    /** The process of a connection changed state (SSE {@code mcp-server.state}). */
    void state(String connectionId, ProcessView process);

    /**
     * A server set to start with the core is down and its backoff is over: the core starts it again with its secrets
     * ({@link #start}). Called on the runtime's own thread; the host must not block it for long.
     */
    void wanted(String connectionId);
  }

  /**
   * The reviewed state of one server, as the core stores it.
   *
   * @param toolPrefix      the prefix of its tools ({@code github_}), fixed when the server was added
   * @param serverVersion   {@code serverInfo.version} at the last handshake: Read only confirmations bind to it
   * @param protocolVersion the revision both agreed on ({@code 2024-11-05})
   * @param tools           one JSON object per tool (keys: see the type's description)
   * @param npmIntegrity    the integrity a catalog server's package must have on every start, or {@code null}
   */
  record Snapshot(String toolPrefix, String serverVersion, String protocolVersion, List<JsonNode> tools,
      String npmIntegrity) {

    public Snapshot {
      Objects.requireNonNull(toolPrefix, "toolPrefix");
      tools = tools == null ? List.of() : List.copyOf(tools);
    }
  }

  /**
   * One start of a server.
   *
   * @param serverInfo      {@code {name, title, version}} as the server sent them (cleaned of control characters)
   * @param instructions    the server's instructions, for the review only: agents never get them
   * @param tools           the snapshot entries of the tools it lists now, in its order
   * @param rejected        tools that can't be published, and why (a reserved name, a schema over 64 KB)
   * @param startMillis     from spawn (or connect) to the end of {@code tools/list}
   * @param notes           what the test shows ("26 tools, 0 declare annotations", "Not pinned")
   */
  record Handshake(JsonNode serverInfo, String protocolVersion, String instructions, List<JsonNode> tools,
      List<Rejected> rejected, long startMillis, List<String> notes) {

    public Handshake {
      tools = tools == null ? List.of() : List.copyOf(tools);
      rejected = rejected == null ? List.of() : List.copyOf(rejected);
      notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public String serverVersion() {
      return serverInfo == null ? null : serverInfo.path("version").asString(null);
    }
  }

  /** A tool the server lists that the gateway doesn't publish. */
  record Rejected(String name, String reason) {}

  /**
   * What will run.
   *
   * @param argv            the command as it starts (for a catalog server {@code node …/dist/index.js}, never npx)
   * @param resolvedCommand the absolute path of {@code argv[0]} found on the PATH the gateway gives the process
   * @param node            {@code Node v24.22.1} when the command is Node, npm or npx; {@code null} otherwise
   * @param variables       names of the variables (stdio) or headers (HTTP) whose values come from secrets
   * @param notPinned       an npm package without an exact version: npx can fetch new code on every start
   * @param sandbox         {@code landlock} or {@code sandbox-exec}, or {@code null} where the system has none
   * @param warnings        what the person should know before starting it
   */
  record Review(String transport, List<String> argv, String resolvedCommand, String node, String url,
      List<String> variables, List<String> inherited, boolean notPinned, String sandbox, String workDir,
      String log, List<String> warnings) {

    public Review {
      argv = argv == null ? List.of() : List.copyOf(argv);
      variables = variables == null ? List.of() : List.copyOf(variables);
      inherited = inherited == null ? List.of() : List.copyOf(inherited);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  /** States of a server's process, as {@code ConnectorProcessStatus} names them. */
  enum State { STARTING, RUNNING, DRAINING, STOPPED, CRASHED, BACKOFF, FAILED }

  /**
   * A process (stdio) or a session (HTTP) right now.
   *
   * @param pid    the root process while one runs, else {@code null}
   * @param detail one line for the UI ("Exited with code 3 · restarts in 4 s", "Idle for 15 min")
   */
  record ProcessView(State state, Long pid, Integer rssMb, Instant since, String detail) {

    public ProcessView {
      Objects.requireNonNull(state, "state");
    }
  }

  /**
   * A pinned npm package of a catalog entry.
   *
   * @param integrity the {@code sha512-…} integrity the catalog signed for {@code name@version}
   * @param lockfile  the catalog's {@code package-lock.json} bytes ({@code npm ci} installs exactly it)
   * @param bin       the name of the package's executable ({@code mcp-server-github})
   * @param nodeRange the Node versions it needs ({@code >=18}), or {@code null}
   */
  record NpmPackage(String name, String version, String integrity, byte[] lockfile, String bin, String nodeRange,
      boolean ignoreScripts) {

    public NpmPackage {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(integrity, "integrity");
      Objects.requireNonNull(lockfile, "lockfile");
      Objects.requireNonNull(bin, "bin");
      lockfile = lockfile.clone();
    }

    @Override
    public byte[] lockfile() {
      return lockfile.clone();
    }
  }

  /**
   * An installed package.
   *
   * @param command the argv to start it from the server's work folder ({@code node node_modules/…/dist/index.js})
   */
  record NpmInstall(List<String> command, String integrity, String node, long millis) {

    public NpmInstall {
      command = List.copyOf(command);
    }
  }
}
