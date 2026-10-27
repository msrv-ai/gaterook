package ai.msrv.gaterook.core.audit.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the audit's own followers that agent call rows committed: a streaming export (schedule {@code stream}, K5)
 * sends them a moment later instead of at its next look. In memory and best effort — a follower still looks on its own
 * schedule, so a signal lost to a stop costs a delay, never a row.
 */
@Component
public class AuditCommits {

  private static final Logger log = LoggerFactory.getLogger(AuditCommits.class);

  private final List<Runnable> followers = new CopyOnWriteArrayList<>();

  /** {@code follower} runs on the committing thread: it only notes that there is work, it does none. */
  public void follow(Runnable follower) {
    followers.add(follower);
  }

  /** Agent call rows committed ({@code AuditWriter}, after the commit). */
  public void committed() {
    for (Runnable follower : followers) {
      try {
        follower.run();
      } catch (RuntimeException e) {
        log.debug("An audit follower failed: {}", e.toString());
      }
    }
  }
}
