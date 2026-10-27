package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The anchor of both audit hash chains (ARCHITECTURE.md §4 "Audit — hash chain plus its head HMAC in settings once per
 * minute; the tail is verified at startup"). Once a minute, and when the core stops, the last row of {@code tool_call} and of
 * {@code setup_change} go into {@code setting audit.head-mac} with an HMAC under the {@code audit-head/v1} key. At
 * start (and whenever the master key changes) the core checks:
 * <ol>
 *   <li>the anchor's MAC: an edited anchor, or one made under another key;</li>
 *   <li>that the anchored rows are still there with the same hashes: rows removed or rewritten up to the anchor;</li>
 *   <li>the tail after the anchor, row by row: every {@code prev_sha256} links and every {@code row_sha256}
 *       recomputes;</li>
 *   <li>then, in the background, every row since the chain was last re-anchored.</li>
 * </ol>
 * The anchor never moves over what it can't vouch for: before each move (the minute's, the one at stop, retention's)
 * checks 2 and 3 run against the anchor in force, in the same read as the new heads. The rows' hashes take no key, so
 * the anchor is the only evidence: without that check, rows deleted or rewritten (with their hashes recomputed
 * forward) while the core runs — the agent's shell runs as the same OS user — would be covered by the next anchor.
 *
 * <p>A break is never accepted silently: it raises an integrity alert, and the chain is re-anchored with a Setup
 * change that says the history before this point is not verified — so the break stays visible in the (itself
 * chained) Setup changes. One found at stop leaves the anchor where it was instead: the next start finds the same break
 * and raises its alert there. A missing anchor on a database that already has rows (an older dev build, or a deleted
 * setting) is treated the same way. {@code DROP TRIGGER} followed by an edit shows up here, not in the triggers.
 */
@Component
public class AuditHead implements DisposableBean {

  static final String KEY = "audit.head-mac";

  private static final Logger log = LoggerFactory.getLogger(AuditHead.class);
  private static final int PAGE = 500;
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  /** The last rows of both tables; {@code 0}/{@link ChainHash#GENESIS} for an empty table. */
  record Heads(long toolCallId, String toolCallSha, long setupChangeId, String setupChangeSha) {

    boolean empty() {
      return toolCallId == 0 && setupChangeId == 0;
    }
  }

  /** The chain as one read saw it: its heads now, and the first problem since the anchor in force. */
  private record Look(Heads heads, Optional<String> problem) {}

  /** What retention's transaction came to: its result, or the break it found before deleting anything. */
  private record Retained<T>(T result, String problem) {}

  private final ToolCallRepository calls;
  private final SetupChangeRepository changes;
  private final SettingStore settings;
  private final HmacKeys keys;
  private final SetupChangeRecorder recorder;
  private final IntegrityAlerts alerts;
  private final Clock clock;
  private final Duration interval;
  private final TransactionTemplate readTx;
  private final TransactionTemplate writeTx;
  private final Object lock = new Object();
  private ScheduledExecutorService timer;
  /** The heads the stored anchor vouches for; {@code null} until a check passed (a locked master key at start). */
  private volatile Heads anchored;

  AuditHead(ToolCallRepository calls, SetupChangeRepository changes, SettingStore settings, HmacKeys keys,
      SetupChangeRecorder recorder, IntegrityAlerts alerts, Clock clock, PlatformTransactionManager tx,
      @Value("${gateway.audit.anchor-interval:1m}") Duration interval) {
    this.calls = calls;
    this.changes = changes;
    this.settings = settings;
    this.keys = keys;
    this.recorder = recorder;
    this.alerts = alerts;
    this.clock = clock;
    this.interval = interval;
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
    this.writeTx = new TransactionTemplate(tx);
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    verify();
    synchronized (this) {
      if (timer == null) {
        long every = Math.max(1, interval.toMillis());
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("audit-head").factory());
        timer.scheduleWithFixedDelay(this::anchorQuietly, every, every, TimeUnit.MILLISECONDS);
      }
    }
  }

  /**
   * New derived keys make the stored anchor unverifiable (and a restored key makes a check possible that a locked
   * start had to skip): check again; a failure re-anchors with its Setup change.
   */
  @Async
  @TransactionalEventListener
  public void onMasterKeyChanged(MasterKeyChanged event) {
    verify();
  }

  /** The start check; returns whether the history verified. */
  boolean verify() {
    synchronized (lock) {
      try {
        keys.mac(HmacKeys.Purpose.AUDIT_HEAD, new byte[0]);
      } catch (IllegalStateException locked) {
        log.info("The master key is locked: the audit chain is checked once it opens");
        return true;
      }
      Optional<JsonNode> stored = settings.find(KEY).map(SettingStore.Setting::value);
      if (stored.isEmpty()) {
        Heads heads = readTx.execute(s -> heads());
        if (heads.empty()) {
          anchor(heads, heads);
          return true;
        }
        return broken("the audit history has no anchor yet");
      }
      JsonNode anchor = stored.get();
      Boolean mac = macValid(anchor);
      if (mac == null) {
        log.info("The master key is locked: the audit chain is checked once it opens");
        return true;
      }
      if (!mac) {
        return broken("the stored head of the audit chain didn't verify");
      }
      Heads at = headsOf(anchor.path("heads"));
      Look look = readTx.execute(s -> look(at));
      if (look.problem().isPresent()) {
        return broken(look.problem().get());
      }
      anchored = at;
      Heads from = headsOf(anchor.path("verifiedFrom"));
      Thread.ofVirtual().name("audit-chain-check").start(() -> fullCheck(from));
      if (!look.heads().equals(at)) {
        anchor(look.heads(), from);
      }
      return true;
    }
  }

  /**
   * Anchors a history that has no rows and no anchor yet, now rather than at the start check. Whatever fills a new
   * home before that check runs (the demo seed) calls this first: otherwise the check would find rows on a database
   * without an anchor, which it can't tell from a deleted one, and report the whole seeded history as not verified.
   *
   * @return whether the history has an anchor now; {@code false} while the master key is locked or when rows exist
   *     without one (the start check reports that)
   */
  public boolean anchorIfEmpty() {
    synchronized (lock) {
      if (settings.find(KEY).isPresent()) {
        return true;
      }
      try {
        keys.mac(HmacKeys.Purpose.AUDIT_HEAD, new byte[0]);
      } catch (IllegalStateException locked) {
        return false;
      }
      Heads heads = readTx.execute(s -> heads());
      if (!heads.empty()) {
        return false;
      }
      anchor(heads, heads);
      return true;
    }
  }

  /** The minute's move of the anchor: a break since the last one is reported now. */
  void anchorQuietly() {
    advance(true);
  }

  /**
   * Moves the anchor to the current heads once the rows since the anchor in force check out.
   *
   * @param report a break is reported now (alert, Setup change, re-anchor); otherwise (at stop) the anchor stays, so
   *     the next start finds the same break and raises its alert where someone sees it
   */
  private void advance(boolean report) {
    try {
      synchronized (lock) {
        Heads at = anchored;
        if (at == null) {
          // Not checked yet (the master key was locked at start): verify() anchors once it could check.
          return;
        }
        Look look = readTx.execute(s -> look(at));
        if (look.problem().isPresent()) {
          if (report) {
            broken(look.problem().get());
          } else {
            log.warn("Audit history check failed at stop: {}. The anchor stays, so the next start reports it.",
                look.problem().get());
          }
          return;
        }
        if (!look.heads().equals(at)) {
          anchor(look.heads(), verifiedFrom());
        }
      }
    } catch (RuntimeException e) {
      // A locked key or a busy database: the next minute tries again.
      log.debug("Couldn't anchor the audit chain: {}", e.toString());
    }
  }

  /**
   * Runs retention's delete ({@link AuditRetentionJob}), the one legitimate removal of audit rows, where the anchor can
   * follow it: under the anchor's lock and in one writer transaction (the single writer connection: no row is written
   * in between), the rows since the anchor are checked first — a break is reported, never deleted away — then the
   * delete runs and the anchor moves to the heads that remain. A delete that took the anchored row itself (nothing
   * newer than the retention) therefore isn't mistaken for a break.
   *
   * @throws IllegalStateException while the chain isn't checked yet (a locked master key): retention waits for it
   */
  <T> T retain(Supplier<T> delete) {
    synchronized (lock) {
      for (int attempt = 0; ; attempt++) {
        Heads at = anchored;
        if (at == null) {
          throw new IllegalStateException("The audit history isn't checked yet");
        }
        Retained<T> done = writeTx.execute(s -> {
          Look look = look(at);
          if (look.problem().isPresent()) {
            return new Retained<T>(null, look.problem().get());
          }
          T result = delete.get();
          Heads now = heads();
          if (!now.equals(at)) {
            anchor(now, verifiedFrom());
          }
          return new Retained<>(result, null);
        });
        if (done.problem() == null) {
          return done.result();
        }
        broken(done.problem());
        if (attempt > 0) {
          throw new IllegalStateException("The audit history broke again: " + done.problem());
        }
      }
    }
  }

  private void anchor(Heads heads, Heads verifiedFrom) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.set("heads", json(heads));
    body.set("verifiedFrom", json(verifiedFrom));
    body.put("at", clock.instant().toEpochMilli());
    body.put("mac", B64.encodeToString(keys.mac(HmacKeys.Purpose.AUDIT_HEAD, CanonicalJson.bytes(unsigned(body)))));
    settings.put(KEY, body, null);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // Inside a larger transaction (a re-anchoring, retention): the anchor holds only once that commits.
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          anchored = heads;
        }
      });
    } else {
      anchored = heads;
    }
  }

  /** Where the last re-anchoring started the verified history ({@link #fullCheck} walks from there). */
  private Heads verifiedFrom() {
    return settings.find(KEY).map(s -> s.value().path("verifiedFrom")).filter(JsonNode::isObject)
        .map(AuditHead::headsOf).orElse(new Heads(1, null, 1, null));
  }

  /** {@code null}: the keys are locked, no answer. */
  private Boolean macValid(JsonNode anchor) {
    try {
      byte[] mac = B64D.decode(anchor.path("mac").asString(""));
      return keys.verify(HmacKeys.Purpose.AUDIT_HEAD, CanonicalJson.bytes(unsigned(anchor)), mac);
    } catch (IllegalStateException e) {
      return null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * The history before this point can't be vouched for: a Setup change that stays — written in the same transaction
   * as the new anchor, which therefore covers it — and an alert. The alert goes out after that commit, so that whoever
   * it sends to the Setup changes finds the entry there.
   */
  private boolean broken(String detail) {
    log.warn("Audit history check failed: {}. The chain is re-anchored; calls and changes before this point are not "
        + "verified.", detail);
    try {
      writeTx.executeWithoutResult(s -> {
        recorder.record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SETTINGS, "Audit history",
            "verified", "not verified before this point", "MCP Gateway found that " + detail + ".", null, null, null,
            null));
        Heads now = heads();
        anchor(now, new Heads(now.toolCallId() + 1, null, now.setupChangeId(), null));
      });
    } catch (RuntimeException e) {
      log.warn("Couldn't re-anchor the audit chain: {}", e.toString());
    }
    alerts.report("audit", "chain", "Audit history", detail);
    return false;
  }

  /**
   * The heads now and, when they moved since {@code at}, the first problem of the anchored rows or of the tail after
   * them. Runs inside one transaction, so the heads and the walk see the same rows.
   */
  private Look look(Heads at) {
    Heads now = heads();
    if (now.equals(at)) {
      return new Look(now, Optional.empty());
    }
    Optional<String> problem = anchoredRowsProblem(at);
    return new Look(now, problem.isPresent() ? problem : tailProblem(at));
  }

  private Heads heads() {
    Optional<ToolCallEntity> call = calls.findTopByOrderByIdDesc();
    Optional<SetupChangeEntity> change = changes.findTopByOrderByIdDesc();
    return new Heads(call.map(ToolCallEntity::getId).orElse(0L),
        call.map(ToolCallEntity::getRowSha256).orElse(ChainHash.GENESIS),
        change.map(SetupChangeEntity::getId).orElse(0L),
        change.map(SetupChangeEntity::getRowSha256).orElse(ChainHash.GENESIS));
  }

  private Optional<String> anchoredRowsProblem(Heads at) {
    if (at.toolCallId() > 0) {
      Optional<ToolCallEntity> row = calls.findById(at.toolCallId());
      if (row.isEmpty() || !row.get().getRowSha256().equals(at.toolCallSha())) {
        return Optional.of("agent call row " + at.toolCallId() + " was removed or changed");
      }
    }
    if (at.setupChangeId() > 0) {
      Optional<SetupChangeEntity> row = changes.findById(at.setupChangeId());
      if (row.isEmpty() || !row.get().getRowSha256().equals(at.setupChangeSha())) {
        return Optional.of("setup change row " + at.setupChangeId() + " was removed or changed");
      }
    }
    return Optional.empty();
  }

  /** Every row after the anchor links to the one before and hashes to what it says. */
  private Optional<String> tailProblem(Heads at) {
    Optional<String> calls = walk(at.toolCallId(), at.toolCallId() == 0 ? ChainHash.GENESIS : at.toolCallSha(),
        after -> this.calls.findByIdGreaterThanOrderByIdAsc(after, PageRequest.ofSize(PAGE)), ToolCallEntity::getId,
        ToolCallEntity::getPrevSha256, ToolCallEntity::getRowSha256, ChainHash::ofStored, "agent call");
    if (calls.isPresent()) {
      return calls;
    }
    return walk(at.setupChangeId(), at.setupChangeId() == 0 ? ChainHash.GENESIS : at.setupChangeSha(),
        after -> changes.findByIdGreaterThanOrderByIdAsc(after, PageRequest.ofSize(PAGE)), SetupChangeEntity::getId,
        SetupChangeEntity::getPrevSha256, SetupChangeEntity::getRowSha256, ChainHash::of, "setup change");
  }

  /**
   * Background check of everything since the last re-anchoring; the first row there is trusted for its link. A page
   * per read transaction: a year of calls never sits in one persistence context, and rows only ever join at the end.
   */
  private void fullCheck(Heads from) {
    try {
      Optional<String> problem = walk(from.toolCallId() - 1, null,
          after -> readTx.execute(s -> calls.findByIdGreaterThanOrderByIdAsc(after, PageRequest.ofSize(PAGE))),
          ToolCallEntity::getId, ToolCallEntity::getPrevSha256, ToolCallEntity::getRowSha256, ChainHash::ofStored,
          "agent call");
      if (problem.isEmpty()) {
        problem = walk(from.setupChangeId() - 1, null,
            after -> readTx.execute(s -> changes.findByIdGreaterThanOrderByIdAsc(after, PageRequest.ofSize(PAGE))),
            SetupChangeEntity::getId, SetupChangeEntity::getPrevSha256, SetupChangeEntity::getRowSha256,
            ChainHash::of, "setup change");
      }
      if (problem.isPresent()) {
        synchronized (lock) {
          broken(problem.get());
        }
      } else {
        log.info("Audit history verified since rows {}/{}", from.toolCallId(), from.setupChangeId());
      }
    } catch (RuntimeException e) {
      log.warn("Couldn't check the audit history: {}", e.toString());
    }
  }

  @FunctionalInterface
  private interface Rehash<T> {
    String of(String prev, T row);
  }

  /**
   * Walks rows after {@code afterId} in order.
   *
   * @param expectedPrev what the first row must link to, or {@code null} to trust its link
   */
  private static <T> Optional<String> walk(long afterId, String expectedPrev, Function<Long, List<T>> page,
      Function<T, Long> id, Function<T, String> prev, Function<T, String> sha, Rehash<T> rehash, String what) {
    String expected = expectedPrev;
    long after = Math.max(0, afterId);
    while (true) {
      List<T> rows = page.apply(after);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      for (T row : rows) {
        if (expected != null && !expected.equals(prev.apply(row))) {
          return Optional.of(what + " row " + id.apply(row) + " doesn't follow the row before it");
        }
        if (!rehash.of(prev.apply(row), row).equals(sha.apply(row))) {
          return Optional.of(what + " row " + id.apply(row) + " was changed");
        }
        expected = sha.apply(row);
        after = id.apply(row);
      }
    }
  }

  private static ObjectNode json(Heads h) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("toolCall", h.toolCallId());
    n.put("toolCallSha", h.toolCallSha());
    n.put("setupChange", h.setupChangeId());
    n.put("setupChangeSha", h.setupChangeSha());
    return n;
  }

  private static Heads headsOf(JsonNode n) {
    return new Heads(n.path("toolCall").asLong(0), n.path("toolCallSha").asString(null),
        n.path("setupChange").asLong(0), n.path("setupChangeSha").asString(null));
  }

  private static ObjectNode unsigned(JsonNode body) {
    ObjectNode copy = (ObjectNode) body.deepCopy();
    copy.remove("mac");
    return copy;
  }

  @Override
  public void destroy() throws InterruptedException {
    synchronized (this) {
      if (timer != null) {
        timer.shutdownNow();
        timer.awaitTermination(5, TimeUnit.SECONDS);
        timer = null;
      }
    }
    advance(false);
  }
}
