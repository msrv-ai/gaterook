package com.gaterook.connectors.mcpserver;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The gateway's MCP client of one server session (ARCHITECTURE.md A1, §7.5): both protocol eras over any
 * {@link Channel}, with no client capability declared — no roots, sampling or elicitation, so a server can't ask the
 * model or the person anything through the gateway (report 11 §6.6).
 *
 * <ul>
 *   <li><b>2026-07-28</b>: no session. {@code server/discover} tells the revisions, capabilities and instructions;
 *       every request carries the revision, the (empty) capabilities and the client's name in {@code _meta}, and over
 *       HTTP {@code MCP-Protocol-Version}, {@code Mcp-Method}, {@code Mcp-Name} and the {@code Mcp-Param-*} headers a
 *       tool declares ({@code x-mcp-header}). A result of type {@code input_required} that only carries state is sent
 *       again with it, up to ten rounds; one that asks for input fails, since the gateway gives none. What isn't an
 *       answer comes on a {@code subscriptions/listen} stream ({@link #listen}).</li>
 *   <li><b>2025-11-25 back to 2024-11-05</b>: {@code initialize}, then {@code notifications/initialized}; the answer
 *       names the revision, and every later HTTP request names it in {@code MCP-Protocol-Version}.</li>
 * </ul>
 *
 * <p>A new server is asked {@code server/discover} first ({@link #connect} with no era): an error, an HTTP 4xx or, on
 * stdio, no answer within the probe's time is a server of the 2025 revisions, which then gets {@code initialize}. A
 * request the gateway gives up on (its deadline, the call's cancellation) is cancelled at the server:
 * {@code notifications/cancelled}, or over 2026-07-28 HTTP the closed stream of its POST. Progress and log messages a
 * request's answer carries go to that request's {@link Watch}; every log message also goes to the session's log.
 *
 * <p>Messages are matched and dispatched on the channel's reading thread; what the session does about a notification
 * (list the tools again) runs on a thread of its own, so it can wait for an answer the reading thread delivers.
 *
 * <p>Nothing a server sends can make the client hold more than a bound (ARCHITECTURE.md §5.4): a list keeps at most
 * {@link #MAX_LIST_BYTES} of entries over all its pages; a log message goes to the server's log on the reading thread,
 * cut to what the log keeps; a list change waiting for the session's thread absorbs the next ones of its kind, and so
 * does an update of a resource already waiting; at most {@link #MAX_QUEUED} wait in all, and a server that sends more
 * faster than they are handled loses its session ({@link #abandon}). Of the server's own requests, at most
 * {@link #MAX_ANSWERS} are answered at a time (over HTTP each answer is a POST), and one whose id isn't a number or a
 * short string gets none.
 */
final class UpstreamClient implements Channel.Sink {

  enum Era { LEGACY, MODERN }

  static final String MODERN = "2026-07-28";
  /** What {@code initialize} asks for and accepts in answer, newest first. */
  static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

  static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
  static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
  static final String META_CLIENT = "io.modelcontextprotocol/clientInfo";
  static final String META_LOG_LEVEL = "io.modelcontextprotocol/logLevel";
  static final String META_SERVER = "io.modelcontextprotocol/serverInfo";
  static final String META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId";
  static final String META_RELATED_TASK = "io.modelcontextprotocol/related-task";
  /** The Tasks extension of 2026-07-28: a server that offers it may answer {@code tools/call} with a task. */
  static final String TASKS_EXTENSION = "io.modelcontextprotocol/tasks";

  /** What a refusal of every revision says. */
  static final String SPEAKS = "GateRook speaks " + MODERN + " and " + LEGACY.getFirst() + " back to " + LEGACY.getLast()
      + ".";

  /** State-only {@code input_required} rounds a request may take, and the pause before each. */
  static final int MAX_ROUNDS = 10;
  static final Duration ROUND_PAUSE = Duration.ofMillis(250);
  /** Pages of one list, and the entries kept in all. */
  static final int MAX_PAGES = 100;
  /**
   * What the entries kept of one list may come to over all its pages, as JSON: no more than one message may carry
   * ({@link Session#MAX_MESSAGE}). Real servers list a few hundred kilobytes; the entries after the bound are left out.
   */
  static final long MAX_LIST_BYTES = Session.MAX_MESSAGE;
  /** The server's requests answered at a time; one beyond them gets no answer, and the log says so once. */
  static final int MAX_ANSWERS = 16;
  /** The longest string id of a server's request that gets an answer, which repeats it. */
  static final int MAX_ID = 256;
  /** The list changes and resource updates that may wait for the session's thread before the session ends. */
  static final int MAX_QUEUED = 1_024;
  /** The longest URI of a resource update that is passed on: what a resource of the server may have. */
  static final int MAX_URI = 2_048;
  /** What is kept of a log message's text (the server's log keeps a line of 4,000 characters). */
  static final int MAX_LOG = 4_000;

  /** The methods whose name, URI or task goes into {@code Mcp-Name}, and the field it comes from. */
  private static final Map<String, String> NAMED = Map.of("tools/call", "name", "prompts/get", "name",
      "resources/read", "uri", "tasks/get", "taskId", "tasks/update", "taskId", "tasks/cancel", "taskId");

  /** What the server says about itself. */
  record Hello(Era era, String protocolVersion, ObjectNode capabilities, ObjectNode serverInfo, String instructions) {

    boolean has(String capability) {
      return capabilities != null && capabilities.path(capability).isObject();
    }
  }

  /**
   * What happens on the session that isn't an answer. List changes and updates are called on a thread of its own, in
   * order; log messages and notes on the thread that read them, so they must be quick.
   */
  interface Events {
    default void toolsChanged() {}

    default void resourcesChanged() {}

    default void promptsChanged() {}

    default void resourceUpdated(String uri) {}

    /**
     * A {@code notifications/message} of the server: its {@code level} and {@code logger} as strings, and its
     * {@code data} as text of at most {@link #MAX_LOG} characters.
     */
    default void log(ObjectNode params) {}

    /** What the client itself has to say about the session (a list cut short, the session given up). */
    default void note(String text) {}
  }

  /**
   * What one request hears while it runs, on the channel's reading thread: quick, never blocking.
   *
   * @param progress the server's {@code notifications/progress} for it ({@code progress}, {@code total},
   *                 {@code message}), or {@code null} for none: then no progress token is sent
   * @param log      the log messages its answer carries, or {@code null}
   * @param logLevel what to ask for over 2026-07-28 ({@code io.modelcontextprotocol/logLevel}), or {@code null}
   */
  record Watch(Consumer<ObjectNode> progress, Consumer<ObjectNode> log, String logLevel) {
    static final Watch NONE = new Watch(null, null, null);
  }

  /**
   * One request.
   *
   * @param budget  how long to wait for the answer
   * @param ctx     whose cancellation cancels it at the server, or {@code null}
   * @param headers what else goes into its HTTP headers ({@code Mcp-Param-*})
   */
  record Ask(Duration budget, Watch watch, ExecContext ctx, Map<String, String> headers) {

    static Ask within(Duration budget) {
      return new Ask(budget, Watch.NONE, null, Map.of());
    }
  }

  private final Channel channel;
  private final ObjectNode clientInfo;
  private final String server;
  private final Events events;
  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
  private final Map<String, Pending> byToken = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<Void>> taskWaits = new ConcurrentHashMap<>();
  private final Set<Long> listens = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor dispatch = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(MAX_QUEUED), Thread.ofVirtual().name("mcp-events-", 0).factory());
  /** The kinds of list change waiting for the session's thread: another of the same kind meanwhile adds nothing. */
  private final Set<String> changesWaiting = ConcurrentHashMap.newKeySet();
  /** The resources whose update waits for the session's thread: another update of one of them adds nothing. */
  private final Set<String> updatesWaiting = ConcurrentHashMap.newKeySet();
  private final Semaphore answers = new Semaphore(MAX_ANSWERS);
  private final AtomicBoolean answersFull = new AtomicBoolean();
  private volatile Era era;
  private volatile String version;
  private volatile Hello hello;
  private volatile String closed;
  private volatile ObjectNode listening;

  /** An answer one request waits for. */
  private static final class Pending {
    final long id;
    final String method;
    final Watch watch;
    final CompletableFuture<ObjectNode> result = new CompletableFuture<>();
    volatile Channel.Exchange exchange;
    volatile String token;

    Pending(long id, String method, Watch watch) {
      this.id = id;
      this.method = method;
      this.watch = watch;
    }
  }

  /**
   * @param clientInfo the {@code Implementation} the gateway names itself with
   * @param server     the server's name in messages
   */
  UpstreamClient(Channel channel, ObjectNode clientInfo, String server, Events events) {
    this.channel = channel;
    this.clientInfo = clientInfo;
    this.server = server;
    this.events = events;
    // The session's thread ends when it has had nothing to do for a while, even if nobody closes the client.
    dispatch.allowCoreThreadTimeOut(true);
  }

  Hello hello() {
    return hello;
  }

  Era era() {
    return era;
  }

  boolean closed() {
    return closed != null;
  }

  // ── Connecting ──────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Opens the channel and agrees on a revision.
   *
   * @param era    the era to speak, or {@code null} to find out ({@code server/discover}, then {@code initialize})
   * @param probe  how long {@code server/discover} may take before a server counts as one of the 2025 revisions (over
   *               stdio a server of those may simply not answer an unknown request)
   * @param budget the whole connect
   */
  Hello connect(Era era, Duration probe, Duration budget) throws ConnectorException {
    long started = System.nanoTime();
    channel.open(this);
    if (era == Era.LEGACY) {
      return initialize(budget);
    }
    try {
      return discover(era == Era.MODERN ? budget : min(probe, budget));
    } catch (ConnectorException e) {
      if (closed != null || !legacySignal(e, era == null)) {
        throw e;
      }
    }
    Duration left = budget.minusNanos(System.nanoTime() - started);
    return initialize(left.isNegative() ? Duration.ZERO : left);
  }

  /**
   * Whether a failed {@code server/discover} means the server speaks the 2025 revisions: it answered with an error
   * (any: "method not found", "not initialized", "no session"), or with a 4xx or a 500/501 without one; on a probe also
   * when it didn't answer in time. A refused sign-in, a network failure or a 502–504 is no answer about the revision.
   */
  private static boolean legacySignal(ConnectorException e, boolean probing) {
    if (McpServerRuntime.SignInRequired.in(e) != null) {
      return false;
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof Rpc.Error) {
        return true;
      }
      if (t instanceof Channel.Failure f) {
        int s = f.status();
        return (s >= 400 && s < 500 && s != 401 && s != 403 && s != 407 && s != 408 && s != 429) || s == 500
            || s == 501;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return probing && e.kind() == ConnectorException.Kind.TIMEOUT;
  }

  private Hello discover(Duration budget) throws ConnectorException {
    this.era = Era.MODERN;
    this.version = MODERN;
    ObjectNode r = request("server/discover", null, Ask.within(budget));
    List<String> versions = new ArrayList<>();
    for (JsonNode v : r.path("supportedVersions")) {
      if (v.isString()) {
        versions.add(v.asString());
      }
    }
    if (!versions.contains(MODERN)) {
      // A server that discovers but speaks other revisions: the 2025 ones go through initialize.
      throw ConnectorException.resourceError(server + " speaks " + String.join(", ", versions) + " by discovery",
          new Rpc.Error(Rpc.UNSUPPORTED_VERSION, "Supported: " + versions, null));
    }
    ObjectNode capabilities = r.path("capabilities") instanceof ObjectNode c ? c : JsonNodeFactory.instance
        .objectNode();
    JsonNode info = r.path("_meta").path(META_SERVER);
    Hello h = new Hello(Era.MODERN, MODERN, capabilities, info instanceof ObjectNode o ? o : null,
        Texts.str(r.get("instructions"), null));
    hello = h;
    return h;
  }

  private Hello initialize(Duration budget) throws ConnectorException {
    this.era = Era.LEGACY;
    this.version = null;
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    params.put("protocolVersion", LEGACY.getFirst());
    // Nothing: the server can't ask for roots, sampling or the person's input through the gateway.
    params.putObject("capabilities");
    params.set("clientInfo", clientInfo.deepCopy());
    ObjectNode r;
    try {
      r = request("initialize", params, Ask.within(budget));
    } catch (ConnectorException e) {
      // A server that speaks only other revisions says which ({@code data.supported}).
      Rpc.Error error = e.getCause() instanceof Rpc.Error x ? x : null;
      if (error != null && error.data() != null && error.data().path("supported").isArray()) {
        List<String> theirs = new ArrayList<>();
        error.data().get("supported").forEach(v -> theirs.add(Texts.clean(v.asString(""), 40, false)));
        throw ConnectorException.unavailable("Speaks " + String.join(", ", theirs) + " only; " + SPEAKS, e);
      }
      throw e;
    }
    String v = Texts.str(r.get("protocolVersion"), null);
    if (v == null || !LEGACY.contains(v)) {
      throw ConnectorException.unavailable(server + " answered initialize with revision " + Texts.clean(
          String.valueOf(v), 40, false) + "; " + SPEAKS, new Rpc.Error(Rpc.UNSUPPORTED_VERSION, "Unsupported " + v,
          null));
    }
    version = v;
    notify("notifications/initialized", null);
    ObjectNode capabilities = r.path("capabilities") instanceof ObjectNode c ? c : JsonNodeFactory.instance
        .objectNode();
    JsonNode info = r.get("serverInfo");
    Hello h = new Hello(Era.LEGACY, v, capabilities, info instanceof ObjectNode o ? o : null,
        Texts.str(r.get("instructions"), null));
    hello = h;
    return h;
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * One request and its answer; over 2026-07-28 also the state-only rounds of an {@code input_required} result.
   *
   * @param params the request's params (copied, never changed), or {@code null}
   */
  ObjectNode request(String method, ObjectNode params, Ask ask) throws ConnectorException {
    long started = System.nanoTime();
    ObjectNode p = params == null ? JsonNodeFactory.instance.objectNode() : params.deepCopy();
    for (int round = 0; ; round++) {
      Duration left = ask.budget().minusNanos(System.nanoTime() - started);
      ObjectNode r = once(method, p, left, ask);
      if (era == Era.MODERN && "tools/call".equals(method) && "task".equals(Texts.str(r.get("resultType"), null))) {
        return awaitTask(r, ask, started);
      }
      if (era != Era.MODERN || !"input_required".equals(Texts.str(r.get("resultType"), null))) {
        return r;
      }
      JsonNode asked = r.path("inputRequests");
      if (asked.isObject() && !asked.isEmpty()) {
        List<String> what = new ArrayList<>();
        for (JsonNode q : asked) {
          what.add(Texts.clean(Texts.str(q.get("method"), "input"), 60, false));
        }
        throw ConnectorException.resourceError(server + " asked for input GateRook doesn't give ("
            + String.join(", ", what) + ") to answer " + method + ".", null);
      }
      if (!r.path("requestState").isString() || round + 1 >= MAX_ROUNDS) {
        throw ConnectorException.resourceError(server + " didn't complete " + method + " in " + MAX_ROUNDS
            + " rounds.", null);
      }
      // The same request again with a new id and the state as it came, byte for byte.
      p.put("requestState", r.get("requestState").asString());
      pause(ROUND_PAUSE);
    }
  }

  private ObjectNode once(String method, ObjectNode params, Duration budget, Ask ask) throws ConnectorException {
    if (budget.isZero() || budget.isNegative()) {
      throw ConnectorException.timeout("No time was left for " + method + ".");
    }
    String why = closed;
    if (why != null) {
      throw ConnectorException.unavailable(why, new Channel.Failure(why));
    }
    long id = ids.incrementAndGet();
    ObjectNode p = params.deepCopy();
    Pending pend = new Pending(id, method, ask.watch());
    if (era == Era.MODERN) {
      envelope(p, ask.watch().logLevel());
      if ("tools/call".equals(method) && offersTasks()) {
        // Only a call may become a task, so only a call says the client takes one.
        ((ObjectNode) meta(p).get(META_CAPABILITIES)).putObject("extensions").putObject(TASKS_EXTENSION);
      }
    }
    if (ask.watch().progress() != null) {
      String token = "gr-" + id;
      pend.token = token;
      meta(p).put("progressToken", token);
      byToken.put(token, pend);
    }
    pending.put(id, pend);
    ObjectNode message = Rpc.request(id, method, p);
    Map<String, String> headers = new LinkedHashMap<>(headers(method, p));
    headers.putAll(ask.headers());
    if (ask.ctx() != null) {
      ask.ctx().onCancel(() -> cancel(pend, "Cancelled by GateRook"));
    }
    try {
      Channel.Exchange ex = channel.send(message, headers);
      pend.exchange = ex;
      ex.done().whenComplete((v, err) -> {
        if (err != null) {
          pend.result.completeExceptionally(err);
        } else if (channel.answersInExchange() && !pend.result.isDone()) {
          pend.result.completeExceptionally(new Channel.Failure(server + " ended its answer to " + method
              + " without a response."));
        }
      });
      return pend.result.get(Math.max(1, budget.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      cancel(pend, "Timed out");
      throw ConnectorException.timeout("Didn't answer " + method + " in " + seconds(budget) + ".");
    } catch (InterruptedException e) {
      cancel(pend, "Cancelled by GateRook");
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(method + " was cancelled.");
    } catch (ExecutionException e) {
      throw failed(e.getCause(), method);
    } finally {
      pending.remove(id);
      if (pend.token != null) {
        byToken.remove(pend.token);
      }
    }
  }

  /** What a request that failed throws: the server's error is data; anything else is about the connection. */
  private ConnectorException failed(Throwable cause, String method) {
    McpServerRuntime.SignInRequired signIn = McpServerRuntime.SignInRequired.in(cause);
    if (signIn != null) {
      // Nothing reached the server: the message says where to sign in.
      return ConnectorException.unavailable(signIn.getMessage(), cause);
    }
    if (cause instanceof Rpc.Error e) {
      return ConnectorException.resourceError(e.getMessage(), e);
    }
    if (cause instanceof Channel.SessionGone g) {
      return ConnectorException.unavailable(server + " ended its session before it took " + method + ".", g);
    }
    if (cause instanceof Channel.Failure f) {
      return ConnectorException.unavailable(f.getMessage(), f);
    }
    if (cause instanceof ConnectorException ce) {
      return ce;
    }
    return ConnectorException.unavailable(Texts.clean(String.valueOf(cause.getMessage()), 300, false), cause);
  }

  /**
   * Gives up a request at the server: over 2026-07-28 HTTP its POST's stream closes (each request is an exchange of
   * its own there, so a notification couldn't find it); everywhere else {@code notifications/cancelled} names it.
   */
  private void cancel(Pending pend, String reason) {
    if (pend.result.isDone() || pending.remove(pend.id) == null) {
      return;
    }
    pend.result.completeExceptionally(ConnectorException.timeout(pend.method + " was cancelled."));
    Channel.Exchange ex = pend.exchange;
    if (era == Era.MODERN && channel.answersInExchange()) {
      if (ex != null) {
        ex.abort();
      }
      return;
    }
    if ("initialize".equals(pend.method)) {
      // The one request 2025-11-25 says is never cancelled.
      return;
    }
    ObjectNode params = JsonNodeFactory.instance.objectNode().put("requestId", pend.id).put("reason", reason);
    notify("notifications/cancelled", params);
  }

  /** A notification to the server; what goes wrong with it is nobody's answer. */
  void notify(String method, ObjectNode params) {
    if (closed != null) {
      return;
    }
    ObjectNode p = params == null ? (era == Era.MODERN ? JsonNodeFactory.instance.objectNode() : null)
        : params.deepCopy();
    if (era == Era.MODERN) {
      envelope(p, null);
    }
    channel.send(Rpc.notification(method, p), headers(method, p == null ? JsonNodeFactory.instance.objectNode() : p));
  }

  /** {@code ping} over the 2025 revisions; 2026-07-28 has none, so it asks {@code server/discover}. */
  void ping(Duration budget) throws ConnectorException {
    request(era == Era.MODERN ? "server/discover" : "ping", null, Ask.within(budget));
  }

  /**
   * Every page of a list ({@code tools/list} → {@code tools}), at most {@code max} entries and
   * {@link #MAX_LIST_BYTES} of them in all: an entry that would go beyond that is left out with every one after it,
   * and the session's log says so.
   *
   * @throws ConnectorException when a page fails: a list cut short isn't the server's list
   */
  List<ObjectNode> list(String method, String field, Duration budget, int max) throws ConnectorException {
    long started = System.nanoTime();
    List<ObjectNode> out = new ArrayList<>();
    long bytes = 0;
    String cursor = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      ObjectNode params = JsonNodeFactory.instance.objectNode();
      if (cursor != null) {
        params.put("cursor", cursor);
      }
      ObjectNode r = request(method, params, Ask.within(budget.minusNanos(System.nanoTime() - started)));
      for (JsonNode item : r.path(field)) {
        if (!(item instanceof ObjectNode o) || out.size() >= max) {
          continue;
        }
        bytes += Texts.JSON.writeValueAsBytes(o).length;
        if (bytes > MAX_LIST_BYTES) {
          events.note(Texts.clean(method, 60, false) + ": " + server + " listed more than "
              + (MAX_LIST_BYTES / (1024 * 1024)) + " MB; GateRook keeps the first " + out.size() + " entries.");
          return out;
        }
        out.add(o);
      }
      String next = Texts.str(r.get("nextCursor"), null);
      if (next == null || next.isEmpty() || next.equals(cursor) || out.size() >= max) {
        return out;
      }
      cursor = next;
    }
    return out;
  }

  // ── Notifications outside a request ─────────────────────────────────────────────────────────────────────────

  /**
   * Opens the 2026-07-28 {@code subscriptions/listen} stream for what the server offers to tell (a new filter
   * replaces the stream), or over the 2025 revisions the GET stream of Streamable HTTP and the resource subscriptions
   * of {@code resources/subscribe}. The stream opens again while the session lives.
   *
   * @param resources the resource URIs to hear {@code notifications/resources/updated} for
   */
  void listen(List<String> resources) {
    Hello h = hello;
    if (h == null || closed != null) {
      return;
    }
    if (h.era() == Era.LEGACY) {
      channel.listen(headers("", JsonNodeFactory.instance.objectNode()));
      return;
    }
    ObjectNode filter = JsonNodeFactory.instance.objectNode();
    if (h.capabilities().path("tools").path("listChanged").asBoolean(false)) {
      filter.put("toolsListChanged", true);
    }
    if (h.capabilities().path("resources").path("listChanged").asBoolean(false)) {
      filter.put("resourcesListChanged", true);
    }
    if (h.capabilities().path("prompts").path("listChanged").asBoolean(false)) {
      filter.put("promptsListChanged", true);
    }
    if (h.capabilities().path("resources").path("subscribe").asBoolean(false) && !resources.isEmpty()) {
      ArrayNode uris = filter.putArray("resourceSubscriptions");
      resources.forEach(uris::add);
    }
    if (filter.isEmpty()) {
      return;
    }
    synchronized (this) {
      if (filter.equals(listening)) {
        return;
      }
      listening = filter;
    }
    // A stream with the previous filter notices within a second and ends itself.
    Thread.ofVirtual().name("mcp-listen").start(() -> listenLoop(filter));
  }

  /**
   * The resources to hear updates of, over the 2025 revisions: {@code resources/subscribe} for each new one,
   * {@code resources/unsubscribe} for each no longer wanted (on a thread of its own: nobody waits for it). Over
   * 2026-07-28 {@link #listen} carries them instead.
   */
  void subscriptions(java.util.Set<String> before, java.util.Set<String> now) {
    Hello h = hello;
    if (h == null || closed != null || h.era() != Era.LEGACY
        || !h.capabilities().path("resources").path("subscribe").asBoolean(false)) {
      return;
    }
    List<String[]> changes = new ArrayList<>();
    now.stream().filter(x -> !before.contains(x)).forEach(x -> changes.add(new String[] {"resources/subscribe", x}));
    before.stream().filter(x -> !now.contains(x)).forEach(x -> changes.add(new String[] {"resources/unsubscribe", x}));
    if (changes.isEmpty()) {
      return;
    }
    Thread.ofVirtual().name("mcp-subscribe").start(() -> {
      for (String[] c : changes) {
        try {
          request(c[0], JsonNodeFactory.instance.objectNode().put("uri", c[1]), Ask.within(Duration.ofSeconds(10)));
        } catch (ConnectorException e) {
          // The server keeps its subscriptions as it can; updates of that resource may not come.
        }
      }
    });
  }

  private void listenLoop(ObjectNode filter) {
    long backoff = 1_000;
    while (closed == null && filter.equals(listening)) {
      long id = ids.incrementAndGet();
      ObjectNode p = JsonNodeFactory.instance.objectNode();
      p.set("notifications", filter.deepCopy());
      envelope(p, null);
      Pending pend = new Pending(id, "subscriptions/listen", Watch.NONE);
      pending.put(id, pend);
      listens.add(id);
      long opened = System.nanoTime();
      try {
        Channel.Exchange ex = channel.send(Rpc.request(id, "subscriptions/listen", p), headers("subscriptions/listen",
            p));
        pend.exchange = ex;
        // Over HTTP the stream is the POST's answer; on stdio the request stays open until the server ends it.
        ex.done().whenComplete((v, err) -> {
          if (err != null || channel.answersInExchange()) {
            pend.result.complete(JsonNodeFactory.instance.objectNode());
          }
        });
        while (closed == null && filter.equals(listening) && !pend.result.isDone()) {
          try {
            pend.result.get(1, TimeUnit.SECONDS);
          } catch (TimeoutException e) {
            // Still listening.
          } catch (ExecutionException e) {
            if (e.getCause() instanceof Rpc.Error) {
              // The server has no such stream (or refuses this filter): asking again won't change that.
              return;
            }
            break;
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } finally {
        if (!filter.equals(listening) || closed != null) {
          stopListening(id);
        }
        pending.remove(id);
        listens.remove(id);
      }
      if (Duration.ofNanos(System.nanoTime() - opened).toSeconds() > 60) {
        backoff = 1_000;
      }
      pause(Duration.ofMillis(backoff));
      backoff = Math.min(30_000, backoff * 2);
    }
  }

  /** Ends a listen stream: its POST's stream closes (HTTP), or {@code notifications/cancelled} names it (stdio). */
  private void stopListening(long id) {
    Pending pend = pending.remove(id);
    if (pend == null) {
      return;
    }
    pend.result.complete(JsonNodeFactory.instance.objectNode());
    Channel.Exchange ex = pend.exchange;
    if (channel.answersInExchange()) {
      if (ex != null) {
        ex.abort();
      }
    } else {
      notify("notifications/cancelled", JsonNodeFactory.instance.objectNode().put("requestId", id));
    }
  }

  // ── Tasks (2025-11-25 and the extension of 2026-07-28) ───────────────────────────────────────────────────────

  /**
   * {@code tools/call} as a task, for a tool that requires one ({@code execution.taskSupport: "required"}) on a server
   * of the 2025-11-25 revision that runs tool calls as tasks: the call answers with the task at once, its status is
   * polled ({@code tasks/get}, as often as the server suggests, sooner when it says the status changed) and its result
   * fetched when it ends ({@code tasks/result}). A task that wants input, or outlives the budget or the call, is
   * cancelled ({@code tasks/cancel}).
   */
  ObjectNode callAsTask(ObjectNode params, Ask ask) throws ConnectorException {
    long started = System.nanoTime();
    ObjectNode p = params.deepCopy();
    p.putObject("task").put("ttl", Math.max(1_000, ask.budget().toMillis()));
    ObjectNode created = request("tools/call", p, ask);
    if (!created.path("task").isObject()) {
      // The server ran it as an ordinary call.
      return created;
    }
    String taskId = Texts.str(created.path("task").get("taskId"), null);
    if (taskId == null) {
      throw ConnectorException.resourceError(server + " started a task without an id.", null);
    }
    ObjectNode task = (ObjectNode) created.get("task");
    try {
      while (!terminal(task)) {
        if ("input_required".equals(Texts.str(task.get("status"), null))) {
          throw ConnectorException.resourceError(server + " wants input GateRook doesn't give to go on with the "
              + "task " + Texts.clean(taskId, 80, false) + ".", null);
        }
        Duration left = ask.budget().minusNanos(System.nanoTime() - started);
        if (left.isNegative() || left.isZero()) {
          throw ConnectorException.timeout("The task of " + server + " didn't end in " + seconds(ask.budget()) + ".");
        }
        if (ask.ctx() != null && ask.ctx().cancelled()) {
          throw ConnectorException.timeout("The task was cancelled.");
        }
        long interval = Math.clamp(task.path("pollInterval").asLong(1_000), 250, 5_000);
        CompletableFuture<Void> wake = new CompletableFuture<>();
        taskWaits.put(taskId, wake);
        try {
          wake.get(Math.min(interval, left.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
          // Time to look.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw ConnectorException.timeout("The task was cancelled.");
        } finally {
          taskWaits.remove(taskId);
        }
        ObjectNode got = request("tasks/get", JsonNodeFactory.instance.objectNode().put("taskId", taskId),
            Ask.within(min(Duration.ofSeconds(30), left)));
        task = got;
      }
      String status = Texts.str(task.get("status"), "");
      if ("cancelled".equals(status)) {
        throw ConnectorException.resourceError(server + " cancelled the task"
            + statusMessage(task) + ".", null);
      }
      Duration left = ask.budget().minusNanos(System.nanoTime() - started);
      return request("tasks/result", JsonNodeFactory.instance.objectNode().put("taskId", taskId),
          new Ask(left.isNegative() ? Duration.ofSeconds(5) : left, Watch.NONE, null, Map.of()));
    } catch (ConnectorException e) {
      if (!terminal(task)) {
        try {
          request("tasks/cancel", JsonNodeFactory.instance.objectNode().put("taskId", taskId),
              Ask.within(Duration.ofSeconds(5)));
        } catch (ConnectorException ignored) {
          // It ends by its ttl.
        }
      }
      throw e;
    }
  }

  /** Whether the server of 2026-07-28 runs tool calls as tasks of the extension. */
  private boolean offersTasks() {
    Hello h = hello;
    return h != null && h.era() == Era.MODERN && h.capabilities().path("extensions").path(TASKS_EXTENSION).isObject();
  }

  /**
   * A {@code tools/call} that a server of 2026-07-28 answered with a task of the Tasks extension: its status is polled
   * ({@code tasks/get}, as often as the server suggests) until it ends, when the same answer carries the result. A
   * task that wants input, or outlives the budget or the call, is cancelled ({@code tasks/cancel}).
   */
  private ObjectNode awaitTask(ObjectNode task, Ask ask, long started) throws ConnectorException {
    String taskId = Texts.str(task.get("taskId"), null);
    if (taskId == null) {
      throw ConnectorException.resourceError(server + " started a task without an id.", null);
    }
    ObjectNode now = task;
    try {
      while (!terminal(now)) {
        if ("input_required".equals(Texts.str(now.get("status"), null))) {
          throw ConnectorException.resourceError(server + " wants input GateRook doesn't give to go on with the "
              + "task " + Texts.clean(taskId, 80, false) + ".", null);
        }
        Duration left = ask.budget().minusNanos(System.nanoTime() - started);
        if (left.isNegative() || left.isZero()) {
          throw ConnectorException.timeout("The task of " + server + " didn't end in " + seconds(ask.budget()) + ".");
        }
        if (ask.ctx() != null && ask.ctx().cancelled()) {
          throw ConnectorException.timeout("The task was cancelled.");
        }
        long interval = Math.clamp(now.path("pollIntervalMs").asLong(1_000), 250, 5_000);
        CompletableFuture<Void> wake = new CompletableFuture<>();
        taskWaits.put(taskId, wake);
        try {
          wake.get(Math.min(interval, left.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
          // Time to look.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw ConnectorException.timeout("The task was cancelled.");
        } finally {
          taskWaits.remove(taskId);
        }
        now = request("tasks/get", JsonNodeFactory.instance.objectNode().put("taskId", taskId),
            Ask.within(min(Duration.ofSeconds(30), left)));
      }
      String status = Texts.str(now.get("status"), "");
      if ("completed".equals(status) && now.get("result") instanceof ObjectNode result) {
        return result;
      }
      if ("failed".equals(status) && now.get("error") instanceof ObjectNode error) {
        Rpc.Error e = Rpc.Error.of(error);
        throw ConnectorException.resourceError(e.getMessage(), e);
      }
      throw ConnectorException.resourceError(server + ("cancelled".equals(status) ? " cancelled the task"
          : " ended the task " + Texts.clean(status, 40, false) + " without a result") + statusMessage(now) + ".",
          null);
    } catch (ConnectorException e) {
      if (!terminal(now)) {
        try {
          request("tasks/cancel", JsonNodeFactory.instance.objectNode().put("taskId", taskId),
              Ask.within(Duration.ofSeconds(5)));
        } catch (ConnectorException ignored) {
          // It ends by its ttl.
        }
      }
      throw e;
    }
  }

  private static boolean terminal(JsonNode task) {
    String s = Texts.str(task.get("status"), "");
    return s.equals("completed") || s.equals("failed") || s.equals("cancelled");
  }

  private static String statusMessage(JsonNode task) {
    String m = Texts.str(task.get("statusMessage"), null);
    return m == null || m.isBlank() ? "" : ": " + Texts.clean(m, 200, false);
  }

  // ── Channel.Sink ────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public void message(ObjectNode m, Long stream) {
    if (closed != null) {
      // A session that ended (or was given up) answers and hears nothing more.
      return;
    }
    if (Rpc.isResponse(m)) {
      Long id = Rpc.id(m);
      Pending p = id == null ? null : pending.get(id);
      if (p == null) {
        return;
      }
      if (m.path("error").isObject()) {
        p.result.completeExceptionally(Rpc.Error.of(m.get("error")));
      } else if (listens.contains(id)) {
        // The server ended the subscription gracefully.
        p.result.complete(JsonNodeFactory.instance.objectNode());
      } else {
        JsonNode result = m.get("result");
        p.result.complete(result instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode());
      }
      return;
    }
    if (Rpc.isRequest(m)) {
      answer(m);
      return;
    }
    if (!Rpc.isNotification(m)) {
      return;
    }
    String method = m.get("method").asString();
    ObjectNode params = m.path("params") instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode();
    switch (method) {
      case "notifications/progress" -> {
        Pending p = byToken.get(progressToken(params.get("progressToken")));
        if (p != null && p.watch.progress() != null) {
          p.watch.progress().accept(params);
        }
      }
      case "notifications/message" -> {
        // The call whose answer carried it; on stdio, which has no such thing, the one call that listens, if one.
        Pending p = stream != null ? pending.get(stream) : soleListener();
        if (p != null && p.watch.log() != null) {
          p.watch.log().accept(params);
        }
        // Here, cut to what the log keeps: however much a server logs, nothing of it waits in memory.
        try {
          events.log(logEntry(params));
        } catch (RuntimeException e) {
          // A log that can't be written ends nothing of the session.
        }
      }
      case "notifications/tools/list_changed" -> once("tools", events::toolsChanged);
      case "notifications/resources/list_changed" -> once("resources", events::resourcesChanged);
      case "notifications/prompts/list_changed" -> once("prompts", events::promptsChanged);
      case "notifications/resources/updated" -> {
        String uri = Texts.str(params.get("uri"), null);
        if (uri != null && uri.length() <= MAX_URI && updatesWaiting.add(uri)) {
          later(() -> {
            updatesWaiting.remove(uri);
            events.resourceUpdated(uri);
          });
        }
      }
      case "notifications/tasks/status" -> {
        CompletableFuture<Void> w = taskWaits.get(Texts.str(params.get("taskId"), ""));
        if (w != null) {
          w.complete(null);
        }
      }
      case "notifications/cancelled" -> {
        // On stdio a server ends a listen stream this way; it never cancels anything else of ours.
        Long id = params.path("requestId").isIntegralNumber() ? params.get("requestId").asLong() : null;
        if (id != null && listens.contains(id)) {
          Pending p = pending.get(id);
          if (p != null) {
            p.result.complete(JsonNodeFactory.instance.objectNode());
          }
        }
      }
      default -> {
        // notifications/subscriptions/acknowledged and anything newer: nothing to do.
      }
    }
  }

  /** The only request waiting that wants log messages, or {@code null} when none or several do. */
  private Pending soleListener() {
    Pending found = null;
    for (Pending p : pending.values()) {
      if (p.watch.log() != null) {
        if (found != null) {
          return null;
        }
        found = p;
      }
    }
    return found;
  }

  @Override
  public void closed(String reason) {
    if (closed != null) {
      return;
    }
    closed = reason == null ? "The session ended" : reason;
    for (Pending p : List.copyOf(pending.values())) {
      p.result.completeExceptionally(new Channel.Failure(closed));
    }
    taskWaits.values().forEach(w -> w.complete(null));
  }

  /** Ends the session: nothing waits any more, and the channel lets go. */
  void close() {
    for (Long id : List.copyOf(listens)) {
      stopListening(id);
    }
    closed("The session is closed");
    listening = null;
    dispatch.shutdown();
    channel.close();
  }

  /**
   * A request of the server: {@code ping} is answered; anything else needs a capability the gateway doesn't declare
   * (roots, sampling, elicitation), so it is "method not found" — never something the gateway asks a person or a model.
   * At most {@link #MAX_ANSWERS} answers are on their way at a time (over HTTP, until the server took the POST); a
   * request beyond them, or one whose id isn't a number or a string of at most {@link #MAX_ID} characters, gets none.
   */
  private void answer(ObjectNode request) {
    JsonNode id = request.get("id");
    if (!id.isNumber() && !(id.isString() && id.asString().length() <= MAX_ID)) {
      return;
    }
    if (!answers.tryAcquire()) {
      if (answersFull.compareAndSet(false, true)) {
        events.note(server + " sent more than " + MAX_ANSWERS + " requests at once; GateRook answers that many at a "
            + "time and left the others unanswered.");
      }
      return;
    }
    String method = request.get("method").asString();
    ObjectNode reply = "ping".equals(method) ? Rpc.result(id, JsonNodeFactory.instance.objectNode())
        : Rpc.error(id, Rpc.METHOD_NOT_FOUND, "GateRook offers no " + Texts.clean(method, 60, false)
            + ": it declares no client capabilities.");
    Channel.Exchange ex;
    try {
      ex = channel.send(reply, headers("", JsonNodeFactory.instance.objectNode()));
    } catch (RuntimeException e) {
      answers.release();
      return;
    }
    ex.done().whenComplete((v, e) -> answers.release());
  }

  /** {@link #later} for a list change of {@code kind}, unless one of that kind waits already. */
  private void once(String kind, Runnable r) {
    if (changesWaiting.add(kind)) {
      later(() -> {
        // Cleared first: a change that comes while this one is handled is handled after it.
        changesWaiting.remove(kind);
        r.run();
      });
    }
  }

  /**
   * Runs {@code r} on the session's thread. A queue of {@link #MAX_QUEUED} that is full means the server sends faster
   * than its notifications are handled: the session is given up.
   */
  private void later(Runnable r) {
    if (dispatch.isShutdown()) {
      return;
    }
    try {
      dispatch.execute(() -> {
        try {
          r.run();
        } catch (RuntimeException e) {
          // A listener's failure ends nothing of the session.
        }
      });
    } catch (RejectedExecutionException e) {
      if (!dispatch.isShutdown()) {
        abandon(server + " sent more than " + MAX_QUEUED + " list changes and resource updates faster than "
            + "GateRook could take them in.");
      }
    }
  }

  /**
   * Gives the session up because of what the server did: every request waiting fails with {@code reason}, the
   * session's log says it, and the channel ends ({@link Channel#abandon}: a process is stopped, so its supervisor
   * records the reason as its crash; an HTTP session closes, and the next call opens another).
   */
  void abandon(String reason) {
    if (closed != null) {
      return;
    }
    closed(reason);
    // What waits for the session's thread is dropped, and a change it is handling now stops.
    dispatch.shutdownNow();
    events.note("GateRook ended the session: " + reason);
    // Not on the reading thread: closing an HTTP session sends a DELETE.
    Thread.ofVirtual().name("mcp-abandon").start(() -> channel.abandon(reason));
  }

  /**
   * The {@code params} of a log message as the session's log takes them: {@code level} and {@code logger} as short
   * strings, {@code data} as text of at most {@link #MAX_LOG} characters.
   */
  static ObjectNode logEntry(ObjectNode params) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("level", Texts.clean(Texts.str(params.get("level"), "info"), 20, false));
    String logger = Texts.str(params.get("logger"), null);
    if (logger != null) {
      out.put("logger", Texts.clean(logger, 200, false));
    }
    JsonNode data = params.get("data");
    if (data != null && !data.isNull()) {
      String text = data.isString() ? data.asString() : data.toString();
      out.put("data", text.length() > MAX_LOG ? text.substring(0, MAX_LOG) + "…" : text);
    }
    return out;
  }

  // ── The envelope and the headers ────────────────────────────────────────────────────────────────────────────

  /** 2026-07-28: what every request (and notification) says about the client, in its {@code _meta}. */
  private void envelope(ObjectNode params, String logLevel) {
    ObjectNode meta = meta(params);
    meta.put(META_VERSION, MODERN);
    meta.putObject(META_CAPABILITIES);
    meta.set(META_CLIENT, clientInfo.deepCopy());
    if (logLevel != null) {
      meta.put(META_LOG_LEVEL, logLevel);
    }
  }

  private static ObjectNode meta(ObjectNode params) {
    return params.get("_meta") instanceof ObjectNode m ? m : params.putObject("_meta");
  }

  /** The protocol's HTTP headers of one message ({@code method} empty: a response). */
  private Map<String, String> headers(String method, ObjectNode params) {
    Map<String, String> h = new LinkedHashMap<>();
    if (era == Era.MODERN) {
      h.put("MCP-Protocol-Version", MODERN);
      if (!method.isEmpty()) {
        h.put("Mcp-Method", method);
      }
      String field = NAMED.get(method);
      String name = field == null ? null : Texts.str(params.get(field), null);
      if (name != null) {
        h.put("Mcp-Name", headerValue(name));
      }
    } else if (version != null) {
      h.put("MCP-Protocol-Version", version);
    }
    return h;
  }

  /**
   * The {@code Mcp-Param-*} headers of a {@code tools/call} over 2026-07-28: every primitive property the tool's input
   * schema marks {@code x-mcp-header}, reachable through {@code properties} alone, with the argument's value.
   */
  static Map<String, String> paramHeaders(JsonNode inputSchema, JsonNode arguments) {
    Map<String, String> out = new LinkedHashMap<>();
    collectParams(inputSchema, arguments, out, 0);
    return out;
  }

  private static void collectParams(JsonNode schema, JsonNode value, Map<String, String> out, int depth) {
    if (schema == null || !schema.isObject() || depth > 16) {
      return;
    }
    if (depth > 0 && schema.path("x-mcp-header").isString() && value != null && value.isValueNode()
        && !value.isNull()) {
      String name = schema.get("x-mcp-header").asString();
      String text = value.isString() ? value.asString() : value.isBoolean() ? Boolean.toString(value.asBoolean())
          : value.isNumber() ? value.asString() : null;
      if (text != null && name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
        out.put("Mcp-Param-" + name, headerValue(text));
      }
    }
    JsonNode properties = schema.path("properties");
    if (properties.isObject() && value != null && value.isObject()) {
      for (Map.Entry<String, JsonNode> p : properties.properties()) {
        collectParams(p.getValue(), value.get(p.getKey()), out, depth + 1);
      }
    }
  }

  /** A header value as it is when HTTP carries it unchanged, else the base64 sentinel {@code =?base64?…?=}. */
  static String headerValue(String value) {
    boolean plain = value.strip().equals(value);
    for (int i = 0; plain && i < value.length(); i++) {
      char c = value.charAt(i);
      plain = c >= 0x20 && c <= 0x7e;
    }
    return plain ? value : "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
        + "?=";
  }

  private static String progressToken(JsonNode token) {
    return token == null ? "" : token.isString() ? token.asString() : token.toString();
  }

  private static void pause(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String seconds(Duration d) {
    return Math.max(1, d.toSeconds()) + " s";
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
