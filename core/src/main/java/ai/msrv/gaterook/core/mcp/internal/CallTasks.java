package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.platform.CallTracing;
import ai.msrv.gaterook.core.platform.Ids;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The calls clients run as tasks (ARCHITECTURE.md §5.6): a {@code tools/call} with {@code params.task} of 2025-11-25,
 * or one of 2026-07-28 from a client that takes tasks of the Tasks extension and that doesn't end within
 * {@link #QUICK}. The call runs on a thread of its own the way any call runs (policy, rate limits, approval, audit,
 * masking), except that a task waits for a person's decision on its approval request, until the request expires,
 * instead of answering with a handle; what the call reports as its progress becomes the task's status message. The
 * client polls the task and reads the result from it.
 *
 * <p>A task is its client's: the principal and, on a bridge several clients share, the bridge's client, on the
 * endpoint. Tasks live in memory: at most {@value #PER_CLIENT} unfinished per client and {@value #OVERALL} in all, and
 * {@value #FINISHED} finished ones (the oldest goes first), each until its ttl from creation passes but at least
 * {@link #GRACE} after it ends. A restart forgets them; an approval request a task waited for stays in GateRook under
 * its handle.
 */
@Component
public class CallTasks {

  private static final Logger log = LoggerFactory.getLogger(CallTasks.class);

  /** The Tasks extension of 2026-07-28. */
  public static final String EXTENSION = "io.modelcontextprotocol/tasks";
  /** Where 2025-11-25 names the task a result belongs to. */
  static final String RELATED = "io.modelcontextprotocol/related-task";

  static final int PER_CLIENT = 32;
  static final int OVERALL = 256;
  static final int FINISHED = 256;
  static final Duration DEFAULT_TTL = Duration.ofHours(1);
  static final Duration MIN_TTL = Duration.ofMinutes(1);
  static final Duration MAX_TTL = Duration.ofHours(24);
  static final Duration GRACE = Duration.ofMinutes(1);
  /** How long a call of 2026-07-28 may take before its answer is a task instead of its result. */
  static final Duration QUICK = Duration.ofSeconds(2);
  /** How long {@code tasks/result} waits for a task that still works. */
  static final Duration RESULT_WAIT = Duration.ofMinutes(10);
  static final long POLL_MS = 1_000;
  static final int PAGE = 100;

  /** One call that runs as a task. */
  public static final class Task {

    private final String id;
    private final String principal;
    private final String client;
    private final String endpointId;
    private final Instant created;
    private final Duration ttl;
    private final Clock clock;
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final List<Runnable> stops = new CopyOnWriteArrayList<>();
    private Instant updated;
    private Instant endedAt;
    private String status = "working";
    private boolean cancelling;
    private String message;
    private String approvalId;
    private ObjectNode result;
    private ObjectNode error;

    Task(String id, String principal, String client, String endpointId, Duration ttl, Clock clock) {
      this.id = id;
      this.principal = principal;
      this.client = client;
      this.endpointId = endpointId;
      this.ttl = ttl;
      this.clock = clock;
      this.created = clock.instant();
      this.updated = created;
    }

    public String id() {
      return id;
    }

    public synchronized boolean working() {
      return "working".equals(status);
    }

    synchronized Instant endedAt() {
      return endedAt;
    }

    synchronized String approvalId() {
      return approvalId;
    }

    /** What the call does now, for the client ({@code statusMessage}). */
    public synchronized void says(String text) {
      if (working() && text != null && !text.isBlank() && !text.equals(message)) {
        message = text.length() > 300 ? text.substring(0, 299) + "…" : text;
        updated = clock.instant();
      }
    }

    /** The call waits for a person to decide the approval request {@code approvalId}. */
    public synchronized void waitsFor(String approvalId, String text) {
      this.approvalId = approvalId;
      says(text);
    }

    void onStop(Runnable stop) {
      stops.add(stop);
    }

    /**
     * @return whether it ended now: a task that ended already, a cancelled one too, keeps what it ended with. While a
     *     cancel decides, the end waits for it: the refusal of a request the cancel declined isn't the task's result.
     */
    synchronized boolean end(String status, ObjectNode result, ObjectNode error, String text) {
      while (cancelling) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      if (!working()) {
        return false;
      }
      this.status = status;
      this.result = result;
      this.error = error;
      if (text != null) {
        message = text.length() > 300 ? text.substring(0, 299) + "…" : text;
      }
      updated = clock.instant();
      endedAt = updated;
      ended.complete(null);
      return true;
    }

    /** Holds the task's end for a cancel; {@code null}, or why it can't be cancelled. */
    synchronized String cancelling() {
      if (!working()) {
        return "is " + status + " already";
      }
      if (cancelling) {
        return "is being cancelled";
      }
      cancelling = true;
      return null;
    }

    /** The cancel decided: the task ends cancelled, or goes on and ends as its call does. */
    synchronized void cancelled(boolean cancel) {
      cancelling = false;
      if (cancel) {
        status = "cancelled";
        message = "Cancelled by the client.";
        updated = clock.instant();
        endedAt = updated;
        ended.complete(null);
      }
      notifyAll();
    }
  }

  private final Map<String, Task> tasks = new LinkedHashMap<>();
  private final ApprovalService approvals;
  private final CallTracing tracing;
  private final Clock clock;

  public CallTasks(ApprovalService approvals, CallTracing tracing, Clock clock) {
    this.approvals = approvals;
    this.tracing = tracing;
    this.clock = clock;
  }

  /** The ttl a 2025-11-25 client asks for ({@code params.task.ttl}, ms) within the bounds, or the default. */
  static Duration ttl(JsonNode asked) {
    if (asked == null || !asked.isIntegralNumber()) {
      return DEFAULT_TTL;
    }
    return Duration.ofMillis(Math.clamp(asked.asLong(), MIN_TTL.toMillis(), MAX_TTL.toMillis()));
  }

  /**
   * Starts the call of {@code r} as a task of its client, on a thread of its own.
   *
   * @param call runs the call the way the request would, given the task's own request
   * @return empty when the client has {@value #PER_CLIENT} unfinished tasks, or the gateway {@value #OVERALL}
   */
  Optional<Task> start(McpRequest r, Duration ttl, Function<McpRequest, ObjectNode> call) {
    Task t;
    synchronized (this) {
      sweep();
      int mine = 0;
      int all = 0;
      for (Task x : tasks.values()) {
        if (x.working()) {
          all++;
          mine += owned(x, r) ? 1 : 0;
        }
      }
      if (mine >= PER_CLIENT || all >= OVERALL) {
        return Optional.empty();
      }
      t = new Task(Ids.next("tsk_"), r.caller().principalId(), r.client(), r.snapshot().id(), ttl, clock);
      tasks.put(t.id, t);
    }
    Thread.ofVirtual().name("mcp-task").start(() -> run(r, t, call));
    return Optional.of(t);
  }

  private void run(McpRequest r, Task t, Function<McpRequest, ObjectNode> call) {
    String tool = r.env().params().path("name").asString("");
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put("mcp.method.name", "tools/call");
    tags.put("mcp.protocol.version", r.env().version());
    tags.put("gen_ai.tool.name", tool);
    tags.put("mcp.task.id", t.id);
    try (CallTracing.Started span = tracing.child(("tools/call " + tool).strip(),
        r.span() == null ? null : r.span().traceparent(), tags)) {
      // The task hears the call: its progress, and how to stop it. Log messages have nowhere to go.
      McpRequest own = new McpRequest(r.env(), r.caller(), r.snapshot(), r.catalog(), span, stream(t), null,
          r.client(), t);
      try {
        t.end("completed", call.apply(own), null, null);
      } catch (McpException e) {
        span.error(Integer.toString(e.code()));
        t.end("failed", null, error(e.code(), e.getMessage(), e.data()), e.getMessage());
      } catch (RuntimeException e) {
        log.error("The task {} of {} failed", t.id, tool, e);
        span.error("internal");
        String text = "Internal error in GateRook. See its log.";
        t.end("failed", null, error(JsonRpc.INTERNAL_ERROR, text, null), text);
      }
    }
  }

  /** What a task's call reports: its progress becomes the task's status message. */
  private static McpGateway.Stream stream(Task t) {
    return new McpGateway.Stream() {
      @Override
      public boolean send(ObjectNode notification) {
        if (!"notifications/progress".equals(notification.path("method").asString(null))) {
          return false;
        }
        JsonNode p = notification.path("params");
        String text = p.path("message").asString(null);
        t.says(text != null ? text : p.path("total").isNumber() ? number(p.get("progress")) + " of "
            + number(p.get("total")) : number(p.get("progress")));
        return true;
      }

      @Override
      public void onClose(Runnable action) {
        t.onStop(action);
      }
    };
  }

  private static String number(JsonNode n) {
    if (n == null || !n.isNumber()) {
      return "?";
    }
    double d = n.asDouble();
    return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
  }

  private static ObjectNode error(int code, String message, JsonNode data) {
    ObjectNode e = JsonNodeFactory.instance.objectNode();
    e.put("code", code);
    e.put("message", message);
    if (data != null && !data.isNull()) {
      e.set("data", data);
    }
    return e;
  }

  private static boolean owned(Task t, McpRequest r) {
    return t.principal.equals(r.caller().principalId()) && Objects.equals(t.client, r.client())
        && t.endpointId.equals(r.snapshot().id());
  }

  /** The task {@code id} when it is the client's: another's is as unknown as one that never was. */
  synchronized Optional<Task> find(McpRequest r, String id) {
    sweep();
    Task t = tasks.get(id);
    return t != null && owned(t, r) ? Optional.of(t) : Optional.empty();
  }

  /**
   * The answer of a 2026-07-28 call that ended within {@link #QUICK}: its result, and the task is forgotten (the client
   * never heard of it); empty while it works.
   */
  Optional<ObjectNode> quick(Task t) {
    try {
      t.ended.get(QUICK.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    synchronized (this) {
      tasks.remove(t.id);
    }
    synchronized (t) {
      if (t.result != null) {
        return Optional.of(t.result);
      }
      throw failure(t);
    }
  }

  private static McpException failure(Task t) {
    ObjectNode e = t.error;
    return e == null ? new McpException(JsonRpc.INTERNAL_ERROR, "Task " + t.id + " ended without a result.")
        : new McpException(e.path("code").asInt(JsonRpc.INTERNAL_ERROR), e.path("message").asString(""), e.get("data"));
  }

  /**
   * {@code tasks/result} of 2025-11-25: waits for the task to end, then the call's result naming its task, or the
   * call's error.
   */
  ObjectNode result(Task t) {
    try {
      t.ended.get(RESULT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "Task " + t.id + " still works. Ask for its result again.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpException(JsonRpc.INTERNAL_ERROR, "GateRook stopped waiting for task " + t.id + ".");
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    synchronized (t) {
      if ("cancelled".equals(t.status)) {
        throw McpException.invalidParams("Task " + t.id + " was cancelled: it has no result.");
      }
      if (t.result == null) {
        throw failure(t);
      }
      ObjectNode out = t.result.deepCopy();
      ObjectNode meta = out.get("_meta") instanceof ObjectNode m ? m : out.putObject("_meta");
      meta.putObject(RELATED).put("taskId", t.id);
      return out;
    }
  }

  /**
   * Stops a task: a call that runs stops as its deadline would stop it; an approval request nobody decided yet is
   * declined (nothing ran, and nobody needs to decide it any more). What a person decided runs to its end, and the
   * task gets it.
   */
  void cancel(Task t) {
    String refused = t.cancelling();
    if (refused != null) {
      throw McpException.invalidParams("Task " + t.id + " " + refused + ".");
    }
    boolean cancel = false;
    try {
      String approval = t.approvalId();
      if (approval != null) {
        ApprovalView before = approvals.find(approval).orElse(null);
        ApprovalView after = before != null && before.pending() ? approvals.declineInClient(approval) : before;
        if (after != null && !"rejected".equals(after.status()) && !"expired".equals(after.status())) {
          throw McpException.invalidParams("Request " + approval + " of task " + t.id + " was approved in "
              + "GateRook: the action runs to its end and the task gets its result.");
        }
      }
      cancel = true;
    } finally {
      t.cancelled(cancel);
    }
    t.stops.forEach(Runnable::run);
  }

  /** {@code tasks/list} of 2025-11-25: the client's tasks, newest first, {@value #PAGE} a page. */
  ObjectNode list(McpRequest r) {
    List<Task> mine = new ArrayList<>();
    synchronized (this) {
      sweep();
      for (Task t : tasks.values()) {
        if (owned(t, r)) {
          mine.addFirst(t);
        }
      }
    }
    int offset = 0;
    JsonNode cursor = r.env().params().get("cursor");
    if (cursor != null && !cursor.isNull()) {
      try {
        offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor.asString()),
            StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        throw McpException.invalidParams("Invalid cursor: start the list again.");
      }
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode list = out.putArray("tasks");
    for (int i = Math.max(0, offset); i < Math.min(mine.size(), offset + PAGE); i++) {
      list.add(legacy(mine.get(i)));
    }
    if (offset + PAGE < mine.size()) {
      out.put("nextCursor", Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(offset + PAGE)
          .getBytes(StandardCharsets.UTF_8)));
    }
    return out;
  }

  /** The task as 2025-11-25 writes it ({@code Task}). */
  ObjectNode legacy(Task t) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    synchronized (t) {
      n.put("taskId", t.id);
      n.put("status", t.status);
      if (t.message != null) {
        n.put("statusMessage", t.message);
      }
      n.put("createdAt", iso(t.created));
      n.put("lastUpdatedAt", iso(t.updated));
      n.put("ttl", t.ttl.toMillis());
      n.put("pollInterval", POLL_MS);
    }
    return n;
  }

  /** The task as the Tasks extension of 2026-07-28 writes it, with the call's result or error once it ended. */
  ObjectNode modern(Task t, boolean outcome) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    synchronized (t) {
      n.put("taskId", t.id);
      n.put("status", t.status);
      if (t.message != null) {
        n.put("statusMessage", t.message);
      }
      n.put("createdAt", iso(t.created));
      n.put("lastUpdatedAt", iso(t.updated));
      n.put("ttlMs", t.ttl.toMillis());
      if ("working".equals(t.status)) {
        n.put("pollIntervalMs", POLL_MS);
      }
      if (outcome && t.result != null) {
        // The call's own result: the answer that carries it has its own resultType.
        ObjectNode result = t.result.deepCopy();
        result.remove("resultType");
        n.set("result", result);
      }
      if (outcome && t.error != null) {
        n.set("error", t.error.deepCopy());
      }
    }
    return n;
  }

  private static String iso(Instant at) {
    return at.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  /** Forgets finished tasks past their ttl (at least {@link #GRACE} after they ended), and the oldest too many. */
  private void sweep() {
    Instant now = clock.instant();
    List<Task> finished = new ArrayList<>();
    for (Iterator<Task> it = tasks.values().iterator(); it.hasNext();) {
      Task t = it.next();
      Instant endedAt = t.endedAt();
      if (endedAt == null) {
        continue;
      }
      Instant byTtl = t.created.plus(t.ttl);
      Instant keep = byTtl.isAfter(endedAt.plus(GRACE)) ? byTtl : endedAt.plus(GRACE);
      if (now.isAfter(keep)) {
        it.remove();
      } else {
        finished.add(t);
      }
    }
    if (finished.size() > FINISHED) {
      finished.sort(Comparator.comparing(Task::endedAt));
      for (int i = 0; i < finished.size() - FINISHED; i++) {
        tasks.remove(finished.get(i).id);
      }
    }
  }
}
