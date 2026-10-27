package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code logs_search}: the newest entries of a range, then older pages through the cursor (report 07 §3.4).
 *
 * <p>One page asks the backend for {@code limit + 1 + seen} entries of {@code [from, to]}, newest first: {@code seen}
 * are the entries an earlier page already gave at its boundary {@code to}, which this page skips. It keeps whole
 * entries up to the limit and the size budget; if anything is left, the cursor's new boundary is the oldest entry
 * kept, together with every kept entry at that same timestamp. So the next page starts exactly where this one stopped,
 * however many entries share a timestamp and however the backend orders ties.
 */
final class Search {

  private Search() {}

  static ToolResult run(Backend backend, LogsRequest r, Settings s, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException {
    String queryKey = Cursors.queryKey(r, s);
    Cursors.SearchState state;
    if (r.cursor() != null) {
      if (!backend.searchCursor()) {
        throw ConnectorException.badInput("Logs Insights searches have no cursor: search again with an earlier to.");
      }
      state = Cursors.openSearch(ctx, r.cursor(), cfg.id(), queryKey);
    } else {
      state = new Cursors.SearchState(nanos(r.from()), nanos(r.to()), Set.of(), null);
    }
    int want = r.limit() + 1 + state.seen().size();
    Page page = backend.newest(r.query(), r.source(), state.fromNs(), state.toNs(), want, state.continuation());
    if (page.aggregate() != null) {
      return Results.search(new Results.SearchAnswer(cfg.name(), s, state.fromNs(), state.toNs(), List.of(),
          page.aggregate(), page.partial(), null, false, page.stats(), r.notes()));
    }
    List<LogEntry> fresh = new ArrayList<>();
    for (LogEntry e : page.entries()) {
      boolean inWindow = e.nanos() >= state.fromNs() && e.nanos() <= state.toNs();
      boolean handedOut = e.nanos() == state.toNs() && state.seen().contains(e.key());
      if (inWindow && !handedOut) {
        fresh.add(e);
      }
    }
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
      // Always at least one entry: a page that gives nothing would never move the cursor.
      if (!kept.isEmpty() && used + cost > budget) {
        cutBy = "size";
        break;
      }
      kept.add(e);
      used += cost;
    }
    boolean more = cutBy != null || page.more();
    String reason = cutBy != null ? cutBy : page.partial() != null ? page.partial() : page.more() ? "limit" : null;
    String next = null;
    boolean gap = false;
    if (more && backend.searchCursor()) {
      Cursors.SearchState after;
      if (!kept.isEmpty()) {
        LogEntry last = kept.getLast();
        Set<Long> seen = new LinkedHashSet<>();
        if (last.nanos() == state.toNs()) {
          seen.addAll(state.seen());
        }
        for (LogEntry e : kept) {
          if (e.nanos() == last.nanos()) {
            seen.add(e.key());
          }
        }
        if (seen.size() > Cursors.MAX_SEEN) {
          // More entries at one instant than a cursor holds: step over the instant and say so.
          after = new Cursors.SearchState(state.fromNs(), last.nanos() - 1, Set.of(), null);
          gap = true;
        } else {
          after = new Cursors.SearchState(state.fromNs(), last.nanos(), seen, null);
        }
      } else if (page.continuation() != null) {
        // Empty pages with a token (Cloud Logging, CloudWatch scan in chunks): resume the same window with it.
        after = new Cursors.SearchState(state.fromNs(), state.toNs(), state.seen(), page.continuation());
      } else {
        after = new Cursors.SearchState(state.fromNs(), state.toNs() - 1, Set.of(), null);
        gap = !state.seen().isEmpty();
      }
      if (after.toNs() >= after.fromNs()) {
        next = Cursors.seal(ctx, cfg.id(), queryKey, after);
      } else {
        reason = null;
      }
    }
    return Results.search(new Results.SearchAnswer(cfg.name(), s, state.fromNs(), state.toNs(), kept, null, reason,
        next, gap, page.stats(), r.notes()));
  }

  static long nanos(Instant i) {
    return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
  }
}
