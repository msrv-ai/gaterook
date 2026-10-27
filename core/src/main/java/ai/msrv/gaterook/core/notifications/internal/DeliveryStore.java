package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.notifications.internal.persistence.DeliveryState;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyDeliveryEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyDeliveryRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The messages sent about requests ({@code notify_delivery}). A click in Slack or Telegram is accepted only on a
 * message recorded here for the same request ({@code externalRef}: {@code <channel>:<ts>} in Slack,
 * {@code <chat>:<message id>} in Telegram), and only with the buttons the message was sent with.
 */
@Component
public class DeliveryStore {

  /**
   * One message.
   *
   * @param target         the DM, channel or chat it went to
   * @param externalRef    where it is, once sent
   * @param offeredApprove it carried Approve when it was sent (a click on Approve needs it, and is checked again)
   */
  public record Delivery(String id, String approvalId, NotifyChannel channel, String target, String externalRef,
      boolean offeredApprove, DeliveryState state) {}

  private final NotifyDeliveryRepository deliveries;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  DeliveryStore(NotifyDeliveryRepository deliveries, Clock clock, PlatformTransactionManager transactions) {
    this.deliveries = deliveries;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  /** A message about to be sent ({@code sending}). */
  public Delivery start(String approvalId, NotifyChannel channel, String target, boolean offeredApprove) {
    return writeTx.execute(s -> view(deliveries.saveAndFlush(new NotifyDeliveryEntity(approvalId, channel,
        cut(target, 64), offeredApprove))));
  }

  public void sent(String id, String externalRef) {
    writeTx.executeWithoutResult(s -> deliveries.findById(id).ifPresent(d -> {
      d.sent(cut(externalRef, 64), clock.instant());
      deliveries.saveAndFlush(d);
    }));
  }

  public void failed(String id, String error) {
    writeTx.executeWithoutResult(s -> deliveries.findById(id).ifPresent(d -> {
      d.failed(error == null ? null : cut(error, 512));
      deliveries.saveAndFlush(d);
    }));
  }

  /** The message shows the request's final outcome and no buttons: nothing more will change it. */
  public void resolved(String id) {
    writeTx.executeWithoutResult(s -> deliveries.findById(id).ifPresent(d -> {
      d.resolved(clock.instant());
      deliveries.saveAndFlush(d);
    }));
  }

  public List<Delivery> forApproval(String approvalId) {
    return readTx.execute(s -> deliveries.findByApprovalId(approvalId).stream().map(DeliveryStore::view).toList());
  }

  /**
   * The delivery that is the message at {@code externalRef}, for the request a button names. Looked up by request
   * first: a reference alone isn't unique — a new Telegram bot's private chat has the same chat id (the user's) and
   * numbers its messages from the start.
   */
  public Optional<Delivery> at(NotifyChannel channel, String externalRef, String approvalId) {
    return readTx.execute(s -> deliveries.findByApprovalIdAndChannel(approvalId, channel).stream()
        .filter(d -> externalRef.equals(d.getExternalRef())).map(DeliveryStore::view).findFirst());
  }

  /** Messages still showing buttons. */
  public List<Delivery> open() {
    return readTx.execute(s -> deliveries.findByState(DeliveryState.SENT).stream().map(DeliveryStore::view).toList());
  }

  /**
   * The channel's messages still showing buttons are left as they are: the bot or app that sent them is gone
   * (Disconnect, another Telegram bot), and its successor must never edit them — a new bot's chat counts its messages
   * from the start, so the same reference would be one of its own messages. Call in that change's transaction.
   *
   * @return how many were closed
   */
  public int closeOpen(NotifyChannel channel) {
    List<NotifyDeliveryEntity> open = deliveries.findByState(DeliveryState.SENT).stream()
        .filter(d -> d.getChannel() == channel).toList();
    open.forEach(d -> d.resolved(clock.instant()));
    deliveries.saveAllAndFlush(open);
    return open.size();
  }

  private static Delivery view(NotifyDeliveryEntity d) {
    return new Delivery(d.getId(), d.getApprovalId(), d.getChannel(), d.getTarget(), d.getExternalRef(),
        d.isOfferedApprove(), d.getState());
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
