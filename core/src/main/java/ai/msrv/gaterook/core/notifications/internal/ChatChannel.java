package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import java.util.List;

/** Slack or Telegram as the dispatcher sees them: deliver a request, update its messages, a digest during a flood. */
public interface ChatChannel {

  NotifyChannel kind();

  /** On, verified and with its tokens: worth trying to send. */
  boolean active();

  /**
   * What one announcement did, for the request's timeline.
   *
   * @param state {@code sent} (at least one target got it) or {@code failed}
   * @param error why it failed, in words a person can act on; {@code null} when sent
   */
  record Delivered(String state, String error) {

    public static Delivered sent() {
      return new Delivered("sent", null);
    }

    public static Delivered failed(String error) {
      return new Delivered("failed", error);
    }
  }

  /** Sends the request to every target of the channel (linked DMs, the channel, linked chats). */
  Delivered deliver(ApprovalNotice notice) throws InterruptedException;

  /** During a flood: one message saying how many wait, kept up to date, with Open in app only. */
  Delivered digest(long waiting) throws InterruptedException;

  /** A request ended: a digest message still out there shows the new count (nothing happens without one). */
  void refreshDigest(long waiting) throws InterruptedException;

  /**
   * The request changed status: its messages show the outcome; buttons go once it no longer waits.
   *
   * @param durationMs how long the approved call ran, when it ran
   */
  void resolved(ApprovalView approval, List<DeliveryStore.Delivery> deliveries, Long durationMs)
      throws InterruptedException;
}
