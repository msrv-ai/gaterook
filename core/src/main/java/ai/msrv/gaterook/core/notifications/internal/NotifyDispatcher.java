package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.approvals.ApprovalRequested;
import ai.msrv.gaterook.core.approvals.ApprovalResolved;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.internal.persistence.DeliveryState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Announces approval requests (ARCHITECTURE.md §7.4): on {@link ApprovalRequested}, once it committed, the request
 * becomes an {@link ApprovalNotice} (no agent note, masked) and goes to the channels its environment announces in
 * ({@code policy.sections.approvals.announceIn}) that are on — the desktop as a {@code notify.desktop} event, Slack
 * and Telegram as messages, each on its own virtual thread, so no call path waits for a channel. Each channel adds one
 * {@code notified} entry to the request's timeline ("Notified on the desktop and in Slack", "Slack failed: …"). On
 * {@link ApprovalResolved} the messages show the outcome and lose their buttons.
 *
 * <p>A channel neither holds nor decides anything: a failed delivery leaves the request waiting in the app, and the
 * timeout still denies it. The window's own events ({@code approval.requested|resolved}) come from the approvals
 * module; this one doesn't repeat them.
 */
@Component
class NotifyDispatcher implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(NotifyDispatcher.class);

  private final ApprovalService approvals;
  private final Notices notices;
  private final ChannelRules rules;
  private final DesktopNotices desktop;
  private final List<ChatChannel> chats;
  private final DeliveryStore deliveries;
  private final ExecutorService work = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notify-", 0)
      .factory());

  NotifyDispatcher(ApprovalService approvals, Notices notices, ChannelRules rules, DesktopNotices desktop,
      List<ChatChannel> chats, DeliveryStore deliveries) {
    this.approvals = approvals;
    this.notices = notices;
    this.rules = rules;
    this.desktop = desktop;
    this.chats = List.copyOf(chats);
    this.deliveries = deliveries;
  }

  @TransactionalEventListener
  void onRequested(ApprovalRequested e) {
    submit(() -> announce(e.approvalId()));
  }

  @TransactionalEventListener
  void onResolved(ApprovalResolved e) {
    submit(() -> resolve(e.approvalId()));
  }

  /** Messages still showing buttons for requests that ended while the core was stopped get their outcome. */
  @EventListener(ApplicationReadyEvent.class)
  void reconcile() {
    submit(() -> {
      Set<String> open = deliveries.open().stream().map(DeliveryStore.Delivery::approvalId)
          .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
      for (String id : open) {
        Optional<ApprovalView> a = approvals.find(id);
        if (a.isPresent() && a.get().terminal()) {
          resolve(id);
        }
      }
    });
  }

  void announce(String approvalId) {
    Optional<ApprovalView> found = approvals.find(approvalId);
    if (found.isEmpty() || !found.get().pending()) {
      return;
    }
    ApprovalView a = found.get();
    ApprovalNotice notice = notices.of(a);
    Set<String> announceIn = rules.announceIn(a.env());
    if (announceIn.contains("desktop") && desktop.request(notice)) {
      approvals.notified(approvalId, ChannelRules.notified("desktop", "sent", null, false));
    }
    for (ChatChannel chat : chats) {
      String id = chat.kind().id();
      if (!announceIn.contains(id) || !chat.active()) {
        continue;
      }
      boolean flood = rules.flood(id);
      submit(() -> {
        ChatChannel.Delivered d;
        try {
          d = flood ? chat.digest(approvals.pendingTotal()) : chat.deliver(notice);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        } catch (RuntimeException ex) {
          log.warn("Announcing {} in {} failed: {}", approvalId, id, ex.toString());
          d = ChatChannel.Delivered.failed("GateRook couldn't send it: see core.log");
        }
        approvals.notified(approvalId, ChannelRules.notified(id, d.state(), d.error(), flood));
        if (!flood) {
          catchUp(chat, approvalId);
        }
      });
    }
  }

  /**
   * A decision that came while the message was on its way found no sent delivery to update, so the message would
   * keep its buttons: once sent, a request that is no longer pending gets its outcome here. Either the decision's own
   * update saw the delivery as sent, or this check sees the decision — both commit before the other reads.
   */
  private void catchUp(ChatChannel chat, String approvalId) {
    Optional<ApprovalView> now = approvals.find(approvalId);
    if (now.isEmpty() || now.get().pending()) {
      return;
    }
    List<DeliveryStore.Delivery> mine = deliveries.forApproval(approvalId).stream()
        .filter(x -> x.channel() == chat.kind() && x.state() == DeliveryState.SENT).toList();
    if (mine.isEmpty()) {
      return;
    }
    try {
      chat.resolved(now.get(), mine, durationMs(approvalId));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException ex) {
      log.warn("Updating the {} messages of {} failed: {}", chat.kind().id(), approvalId, ex.toString());
    }
  }

  void resolve(String approvalId) {
    Optional<ApprovalView> found = approvals.find(approvalId);
    if (found.isEmpty()) {
      return;
    }
    ApprovalView a = found.get();
    Long duration = durationMs(approvalId);
    Map<String, List<DeliveryStore.Delivery>> byChannel = deliveries.forApproval(approvalId).stream()
        .filter(d -> d.state() == DeliveryState.SENT)
        .collect(Collectors.groupingBy(d -> d.channel().id()));
    for (ChatChannel chat : chats) {
      List<DeliveryStore.Delivery> mine = byChannel.getOrDefault(chat.kind().id(), List.of());
      try {
        if (!mine.isEmpty()) {
          chat.resolved(a, mine, duration);
        }
        if (a.terminal()) {
          chat.refreshDigest(approvals.pendingTotal());
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException ex) {
        log.warn("Updating the {} messages of {} failed: {}", chat.kind().id(), approvalId, ex.toString());
      }
    }
  }

  /** How long the approved call ran, from the request's {@code completed}/{@code failed} entry. */
  private Long durationMs(String approvalId) {
    for (ApprovalService.TimelineEntry e : approvals.timeline(approvalId)) {
      if (("completed".equals(e.kind()) || "failed".equals(e.kind())) && e.detail() != null
          && e.detail().path("durationMs").isNumber()) {
        return e.detail().path("durationMs").asLong();
      }
    }
    return null;
  }

  private void submit(Runnable task) {
    try {
      work.execute(() -> {
        try {
          task.run();
        } catch (RuntimeException e) {
          log.warn("A notification task failed: {}", e.toString());
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      log.debug("Notifications are stopping: a task was dropped");
    }
  }

  @Override
  public void destroy() throws InterruptedException {
    work.shutdown();
    if (!work.awaitTermination(5, TimeUnit.SECONDS)) {
      work.shutdownNow();
    }
  }
}
