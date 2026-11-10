package ai.msrv.gaterook.core.endpoints;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * Which of a grant's live bridge tokens give way to a new one ({@link ClientGrants#MAX_LIVE_TOKENS}). Several
 * processes often use one grant at once (every Claude Code session with the bridge, every window of a client), each with
 * a token of its own. A token makes room only when no running process can still be using it: it was issued before this
 * run of the core (its bridges must ask again anyway, the core's instance changed), or to a process that has ended, or
 * to the process that is asking now, which replaces its own. When every live token belongs to another running
 * process, the request is refused: taking the oldest would make that process ask again and take the next one, and the
 * grant's clients would take turns invalidating each other.
 */
final class BridgeTokenSlots {

  /**
   * A live token of the grant.
   *
   * @param pid the process the token was issued to, as its request said; {@code null} when unknown (issued by an
   *            earlier run of the core, or the request named none)
   */
  record Live(String id, Instant createdAt, Long pid) {}

  private BridgeTokenSlots() {}

  /**
   * The ids of the tokens to retire so that a new one leaves at most {@code max} live: the ones no running process can
   * use, oldest first, then the asking process's own, oldest first; empty when that is impossible without taking a
   * token from another running process.
   *
   * @param requester the asking process, or {@code null} when the request named none
   * @param running   whether a process still runs
   */
  static Optional<List<String>> toRetire(List<Live> live, int max, Long requester, LongPredicate running) {
    int excess = live.size() - (max - 1);
    if (excess <= 0) {
      return Optional.of(List.of());
    }
    List<Live> oldestFirst = live.stream()
        .sorted(Comparator.comparing(Live::createdAt).thenComparing(Live::id)).toList();
    List<String> out = new ArrayList<>();
    for (Live t : oldestFirst) {
      if (out.size() < excess && (t.pid() == null || (!t.pid().equals(requester) && !running.test(t.pid())))) {
        out.add(t.id());
      }
    }
    for (Live t : oldestFirst) {
      if (out.size() < excess && requester != null && requester.equals(t.pid())) {
        out.add(t.id());
      }
    }
    return out.size() >= excess ? Optional.of(List.copyOf(out)) : Optional.empty();
  }

  /** Whether {@code pid} names a process that still runs on this computer. */
  static boolean running(long pid) {
    try {
      return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    } catch (SecurityException | UnsupportedOperationException e) {
      return false;
    }
  }
}
