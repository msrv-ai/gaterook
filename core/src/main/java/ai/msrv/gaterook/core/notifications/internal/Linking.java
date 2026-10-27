package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Linking a Slack or Telegram account to this computer's user (ARCHITECTURE.md §7.4, report 13 §5), always in two
 * steps that need both ends: Slack — the person asks in the app, the account answers "Link account" in our DM, which
 * shows the same code as the app; Telegram — the person makes a code in the app, the account sends
 * {@code /start <code>} to the bot, and the person confirms that account in the app. A leaked link alone links no
 * one. Each link and each revocation is a Setup change; the window hears {@code notify.link.updated}.
 */
@Component
public class Linking {

  /** How long a pairing code lives. */
  public static final Duration TTL = Duration.ofMinutes(10);

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] CODE = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

  private final LinkStore links;
  private final EventBus events;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final TransactionTemplate writeTx;

  Linking(LinkStore links, EventBus events, ObjectProvider<SetupChangeRecorder> setupChanges,
      PlatformTransactionManager transactions) {
    this.links = links;
    this.events = events;
    this.setupChanges = setupChanges;
    this.writeTx = new TransactionTemplate(transactions);
  }

  /** 16 random bytes, base64url: a Telegram {@code /start} code or a Slack button's nonce. */
  public static String nonce() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** {@code 4F7-2KQ}: the short code a person compares between the app and the DM (≈ 30 bits, not a secret). */
  public static String shortCode() {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      if (i == 3) {
        s.append('-');
      }
      s.append(CODE[RANDOM.nextInt(CODE.length)]);
    }
    return s.toString();
  }

  /** A new pairing of {@code channel} for the code {@code secret} (stored by hash). */
  public LinkStore.Pairing pairing(NotifyChannel channel, String secret, ObjectNode candidate) {
    LinkStore.Pairing p = links.newPairing(channel, LinkStore.hash(secret), TTL, candidate);
    publish(p.id(), null, channel, "pending");
    return p;
  }

  /**
   * Turns a pairing into a link, once: consumes the code, links the account, records the Setup change, all in one
   * transaction.
   *
   * @return the new link, or empty when the code was used, expired or unknown
   */
  public Optional<LinkStore.Link> complete(String pairingId, NotifyChannel channel, String teamId, String userId,
      String chatId, String display, String reason) {
    Optional<LinkStore.Link> link = Optional.ofNullable(writeTx.execute(s -> {
      Optional<LinkStore.Pairing> used = links.consume(pairingId).filter(p -> p.channel() == channel);
      if (used.isEmpty()) {
        return null;
      }
      LinkStore.Link created = links.create(channel, teamId, userId, chatId, display);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Settings › Notifications · " + title(channel) + " account", null, display == null ? userId
              : display + " (" + userId + ")", reason, null, null, null, JsonNodeFactory.instance.objectNode()
              .put("link", created.id()).put("channel", channel.id())));
      return created;
    }));
    link.ifPresent(l -> publish(pairingId, l.id(), channel, "linked"));
    return link;
  }

  /** The account said "Not me", or the person dropped the code: it can't be used any more. */
  public boolean decline(String pairingId) {
    Optional<LinkStore.Pairing> p = links.consume(pairingId);
    p.ifPresent(x -> publish(pairingId, null, x.channel(), "declined"));
    return p.isPresent();
  }

  /** An account presented a code; the person confirms it in the app. */
  public Optional<LinkStore.Pairing> present(String pairingId, ObjectNode candidate) {
    Optional<LinkStore.Pairing> p = links.present(pairingId, candidate);
    p.ifPresent(x -> publish(pairingId, null, x.channel(), "presented"));
    return p;
  }

  /** Revokes a link with its Setup change. */
  public Optional<LinkStore.Link> revoke(String linkId, String reason) {
    Optional<LinkStore.Link> revoked = Optional.ofNullable(writeTx.execute(s -> links.revoke(linkId).map(l -> {
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Settings › Notifications · " + title(l.channel()) + " account", l.display() == null ? l.userId()
              : l.display() + " (" + l.userId() + ")", "Unlinked", reason, null, null, null, null));
      return l;
    }).orElse(null)));
    revoked.ifPresent(l -> publish(null, l.id(), l.channel(), "revoked"));
    return revoked;
  }

  /** Revokes every link of a channel and drops its open codes (Disconnect, another bot); call in that transaction. */
  public List<LinkStore.Link> revokeAll(NotifyChannel channel) {
    // A code still open for this channel would link an account to a bot or app that is gone: it goes too.
    List<String> dropped = new ArrayList<>();
    for (LinkStore.Pairing p : links.openPairings()) {
      if (p.channel() == channel && links.consume(p.id()).isPresent()) {
        dropped.add(p.id());
      }
    }
    List<LinkStore.Link> revoked = links.revokeAll(channel);
    Runnable announce = () -> dropped.forEach(id -> publish(id, null, channel, "declined"));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          announce.run();
        }
      });
    } else {
      announce.run();
    }
    return revoked;
  }

  public void publish(String pairingId, String linkId, NotifyChannel channel, String state) {
    ObjectNode e = JsonNodeFactory.instance.objectNode();
    e.put("channel", channel.id());
    if (pairingId != null) {
      e.put("pairing", pairingId);
    }
    if (linkId != null) {
      e.put("link", linkId);
    }
    e.put("state", state);
    events.publish("notify.link.updated", e);
  }

  static String title(NotifyChannel channel) {
    return switch (channel) {
      case DESKTOP -> "Desktop";
      case SLACK -> "Slack";
      case TELEGRAM -> "Telegram";
    };
  }
}
