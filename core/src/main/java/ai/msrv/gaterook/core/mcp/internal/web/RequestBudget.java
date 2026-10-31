package ai.msrv.gaterook.core.mcp.internal.web;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many bytes of MCP request bodies one principal may send an hour (ARCHITECTURE.md §5 step 1). Every call is
 * written to the audit before it runs, with its arguments and its client's words, and the audit keeps its rows for a
 * year: without a bound, a token's calls could fill the disk with requests as large as the body limit at the rate
 * limit's pace. The budget counts what arrives, whatever the call does with it; a principal over it gets 429 until
 * the hour has passed.
 */
@Component
public class RequestBudget {

  /** The default: 32 MiB of bodies an hour, hundreds of times what an hour of ordinary calls weighs. */
  public static final long DEFAULT_BYTES_PER_HOUR = 32L * 1024 * 1024;

  static final Duration WINDOW = Duration.ofHours(1);

  private final long bytesPerHour;
  private final Clock clock;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  private static final class Window {
    long since;
    long bytes;
  }

  public RequestBudget(@Value("${gateway.mcp.body-budget-bytes-per-hour:" + DEFAULT_BYTES_PER_HOUR + "}")
      long bytesPerHour, Clock clock) {
    this.bytesPerHour = bytesPerHour;
    this.clock = clock;
  }

  public long bytesPerHour() {
    return bytesPerHour;
  }

  /**
   * Counts {@code bytes} for {@code principal} and tells whether they are within this hour's budget. A request over
   * the budget isn't counted: when the hour passes, the principal starts clean.
   */
  public boolean charge(String principal, long bytes) {
    long now = clock.millis();
    Window w = windows.computeIfAbsent(principal, k -> new Window());
    synchronized (w) {
      if (now - w.since >= WINDOW.toMillis()) {
        w.since = now;
        w.bytes = 0;
      }
      if (w.bytes + bytes > bytesPerHour) {
        return false;
      }
      w.bytes += bytes;
    }
    if (windows.size() > 4_096) {
      prune(now);
    }
    return true;
  }

  private void prune(long now) {
    for (Iterator<Window> it = windows.values().iterator(); it.hasNext();) {
      Window w = it.next();
      synchronized (w) {
        if (now - w.since >= WINDOW.toMillis()) {
          it.remove();
        }
      }
    }
  }
}
