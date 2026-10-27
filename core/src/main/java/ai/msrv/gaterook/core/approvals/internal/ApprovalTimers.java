package ai.msrv.gaterook.core.approvals.internal;

import ai.msrv.gaterook.core.approvals.ApprovalRequested;
import ai.msrv.gaterook.core.approvals.ApprovalResolved;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.platform.EventBus;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The clock of approvals: every second pending requests past their window expire (a timeout is a denial), every
 * minute results older than 60 minutes are forgotten. Also announces requests and decisions on the window's event
 * stream ({@code approval.requested}, {@code approval.resolved}) once they committed — without the agent's note.
 */
@Component
class ApprovalTimers implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ApprovalTimers.class);

  private final ApprovalService approvals;
  private final EventBus events;
  private ScheduledExecutorService timer;

  ApprovalTimers(ApprovalService approvals, EventBus events) {
    this.approvals = approvals;
    this.events = events;
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void start() {
    if (timer != null) {
      return;
    }
    timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("approval-timers").factory());
    timer.scheduleWithFixedDelay(() -> {
      try {
        approvals.expireDue();
      } catch (RuntimeException e) {
        log.debug("Expiring requests failed: {}", e.toString());
      }
    }, 1, 1, TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(() -> {
      try {
        approvals.forgetResults();
      } catch (RuntimeException e) {
        log.debug("Forgetting results failed: {}", e.toString());
      }
    }, 1, 1, TimeUnit.MINUTES);
  }

  @TransactionalEventListener
  void onRequested(ApprovalRequested e) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("id", e.approvalId());
    n.put("endpoint", e.endpointSlug());
    n.put("connection", e.connectionName());
    n.put("env", e.env().id());
    n.put("tool", e.tool());
    n.put("summary", e.summary());
    n.put("commentPolicy", e.commentPolicy());
    n.put("expiresAt", e.expiresAt().toString());
    events.publish("approval.requested", n);
  }

  @TransactionalEventListener
  void onResolved(ApprovalResolved e) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("id", e.approvalId());
    n.put("status", e.status());
    n.put("via", e.decidedVia());
    events.publish("approval.resolved", n);
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (timer != null) {
      timer.shutdownNow();
      timer.awaitTermination(5, TimeUnit.SECONDS);
      timer = null;
    }
  }
}
