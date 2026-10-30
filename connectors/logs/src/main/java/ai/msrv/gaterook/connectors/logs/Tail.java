package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code logs_tail} without sessions (report 07 §1, §7; MCP 2026-07-28 has none): each call asks the backend for
 * entries after the cursor until some arrive or {@code wait} runs out, then returns them with the next cursor.
 *
 * <p>The window ends {@code delay} behind now (two seconds on Loki, ten on Cloud Logging): an entry reaches the
 * backend after its own timestamp, and reading up to now would step past the ones still on their way. The cursor
 * moves only past entries handed out — the last timestamp and every entry given at it — so a burst at one instant
 * spread over two calls comes out once. An entry that arrives later than the delay is missed: that is the price of
 * having no session, and {@code logs_search} still finds it.
 */
final class Tail {

  /** Kept back from the call's deadline for one more poll and the answer. */
  private static final Duration LAST_POLL = Duration.ofMillis(1_500);

  private Tail() {}

  static ToolResult run(Backend backend, LogsRequest r, Settings s, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException {
    String queryKey = Cursors.queryKey(r, s);
    long started = System.nanoTime();
    long now = nanos(ctx);
    Cursors.TailState state;
    boolean gap = false;
    long gapBefore = 0;
    if (r.cursor() != null) {
      state = Cursors.openTail(ctx, r.cursor(), cfg.id(), queryKey);
      Duration catchUp = Cursors.TAIL_HORIZON;
      Duration maxRange = ctx.limits().maxRange();
      if (maxRange != null && maxRange.compareTo(catchUp) < 0) catchUp = maxRange;
      long horizon = now - catchUp.toNanos();
      if (state.lastNs() < horizon) {
        // An old cursor would read a long backlog in tiny pages: start an hour back and say what may be missing.
        state = new Cursors.TailState(horizon, Set.of());
        gap = true;
        gapBefore = horizon;
      }
    } else {
      state = new Cursors.TailState(Search.nanos(r.from()), Set.of());
    }
    long waitUntil = started + r.waitFor().toNanos();
    Page.Stats stats = Page.Stats.NONE;
    while (true) {
      long until = nanos(ctx) - backend.delay().toNanos();
      if (until >= state.lastNs()) {
        int want = r.limit() + 1 + state.seen().size();
        Page page = backend.oldest(r.query(), r.source(), state.lastNs(), until, want);
        stats = stats.plus(page.stats());
        List<LogEntry> fresh = new ArrayList<>();
        for (LogEntry e : page.entries()) {
          boolean inWindow = e.nanos() >= state.lastNs() && e.nanos() <= until;
          boolean handedOut = e.nanos() == state.lastNs() && state.seen().contains(e.key());
          if (inWindow && !handedOut) {
            fresh.add(e);
          }
        }
        if (!fresh.isEmpty()) {
          return answer(r, s, cfg, ctx, state, fresh, page, stats, started, gap, gapBefore, queryKey);
        }
      }
      // The last poll happens when the wait is over; the deadline keeps room for one more poll and the answer.
      long left = Math.min(waitUntil - System.nanoTime(), ctx.remaining().minus(LAST_POLL).toNanos());
      if (left <= 0) {
        String cursor = Cursors.seal(ctx, cfg.id(), queryKey, state);
        return Results.tail(new Results.TailAnswer(cfg.name(), s, r.waitFor(), List.of(), null, cursor,
            (System.nanoTime() - started) / 1_000_000, gap, gapBefore, stats, r.notes()), cfg.type());
      }
      try {
        Thread.sleep(Duration.ofNanos(Math.min(left, backend.pollEvery().toNanos())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ConnectorException.timeout(LogsTools.tailName(cfg.type()) + " was cancelled while it waited.");
      }
    }
  }

  private static ToolResult answer(LogsRequest r, Settings s, ConnectionConfig cfg, ExecContext ctx,
      Cursors.TailState state, List<LogEntry> fresh, Page page, Page.Stats stats, long started, boolean gap,
      long gapBefore, String queryKey) {
    long budget = Results.budget(ctx.limits());
    long used = 0;
    String cutBy = null;
    List<LogEntry> kept = new ArrayList<>();
    for (LogEntry e : fresh) {
      if (kept.size() == r.limit()) {
        cutBy = "limit";
        break;
      }
      long cost = Results.cost(e);
      if (!kept.isEmpty() && used + cost > budget) {
        cutBy = "size";
        break;
      }
      kept.add(e);
      used += cost;
    }
    if (cutBy == null && page.more()) {
      cutBy = page.partial() != null ? page.partial() : "limit";
    }
    LogEntry last = kept.getLast();
    Set<Long> seen = new LinkedHashSet<>();
    if (last.nanos() == state.lastNs()) {
      seen.addAll(state.seen());
    }
    for (LogEntry e : kept) {
      if (e.nanos() == last.nanos()) {
        seen.add(e.key());
      }
    }
    Cursors.TailState next;
    if (seen.size() > Cursors.MAX_SEEN) {
      next = new Cursors.TailState(last.nanos() + 1, Set.of());
      gap = true;
      gapBefore = last.nanos() + 1;
    } else {
      next = new Cursors.TailState(last.nanos(), seen);
    }
    String cursor = Cursors.seal(ctx, cfg.id(), queryKey, next);
    return Results.tail(new Results.TailAnswer(cfg.name(), s, r.waitFor(), kept, cutBy, cursor,
        (System.nanoTime() - started) / 1_000_000, gap, gapBefore, stats, r.notes()), cfg.type());
  }

  private static long nanos(ExecContext ctx) {
    return Search.nanos(ctx.clock().instant());
  }
}
