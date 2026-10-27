package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyLinkEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyLinkRepository;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyPairingEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyPairingRepository;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.RowMac;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Linked accounts ({@code notify_link}) and the codes that link them ({@code notify_pairing}).
 *
 * <p>A link lets a Slack or Telegram account decide from its messages, so it is a security row: signed with
 * {@link RowMac} (V30) over the channel, the account and its chat and the link's dates, version 0 (the row has no
 * version column; revoking re-signs it). A link that doesn't verify counts for nothing: no message goes to it and no
 * click from it is accepted (fail closed), and Settings shows it as changed outside GateRook.
 *
 * <p>A pairing code is stored only as its hash; the code itself goes once to the person (Telegram's
 * {@code /start <code>}) or into our own Slack message's button. The account a code waits for — who presented it on
 * Telegram, whom the DM went to on Slack — decides who gets linked when the person confirms, so the candidate is
 * signed too (its MAC inside the JSON column, {@code notify_pairing} has no MAC column): a candidate changed in
 * {@code gateway.db} between {@code /start} and the person's confirmation links no one.
 */
@Component
public class LinkStore {

  static final String TABLE = "notify_link";
  static final String PAIRING_TABLE = "notify_pairing";
  private static final String MAC = "mac";

  /** One linked account; {@code chatId} is the Slack DM channel or the Telegram private chat. */
  public record Link(String id, NotifyChannel channel, String teamId, String userId, String chatId, String display,
      Instant linkedAt, Instant revokedAt, boolean verified) {

    public boolean active() {
      return revokedAt == null && verified;
    }
  }

  /**
   * A code waiting to be used: by the account it was sent to (Slack's Link button), or for the person's confirmation
   * in the app once an account presented it (Telegram's {@code /start <code>}).
   *
   * @param id        the code's hash: what the API and the button name, never the code
   * @param candidate who presented the code, or {@code null} while nobody has
   */
  public record Pairing(String id, NotifyChannel channel, Instant expiresAt, ObjectNode candidate) {}

  private final NotifyLinkRepository links;
  private final NotifyPairingRepository pairings;
  private final RowMac mac;
  private final IntegrityAlerts alerts;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  LinkStore(NotifyLinkRepository links, NotifyPairingRepository pairings, RowMac mac, IntegrityAlerts alerts,
      JsonMapper json, Clock clock, PlatformTransactionManager transactions) {
    this.links = links;
    this.pairings = pairings;
    this.mac = mac;
    this.alerts = alerts;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  /** b64url(SHA-256) of a code: the key of {@code notify_pairing}. */
  public static String hash(String code) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── Links ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** The accounts of {@code channel} that may receive requests and decide: linked, not revoked, verified. */
  public List<Link> active(NotifyChannel channel) {
    return readTx.execute(s -> links.findByChannelAndRevokedAtIsNull(channel).stream().map(this::link)
        .filter(Link::active).toList());
  }

  /** Every link not revoked, the ones that don't verify included (Settings shows them). */
  public List<Link> current() {
    return readTx.execute(s -> links.findByRevokedAtIsNullOrderByLinkedAtAsc().stream().map(this::link).toList());
  }

  public Optional<Link> find(String id) {
    return readTx.execute(s -> links.findById(id).map(this::link));
  }

  /**
   * Links an account; an earlier link of the same account in the same channel is revoked. Call in the transaction that
   * consumes the pairing and records the Setup change.
   */
  public Link create(NotifyChannel channel, String teamId, String userId, String chatId, String display) {
    Instant now = clock.instant();
    for (NotifyLinkEntity old : links.findByChannelAndRevokedAtIsNull(channel)) {
      if (old.getUserId().equals(userId) && Objects.equals(old.getTeamId(), teamId)) {
        old.revoke(now);
        sign(old);
      }
    }
    NotifyLinkEntity row = new NotifyLinkEntity(channel, teamId, userId, chatId, cut(display, 128), now);
    sign(row);
    return link(links.saveAndFlush(row));
  }

  /**
   * Revokes a link: its account gets no more requests and its clicks count for nothing. Revoking is always safe, so a
   * link that doesn't verify can be revoked too (the revoked row is signed: it grants nothing).
   *
   * @return the link as it was, or empty for an unknown or already revoked id
   */
  public Optional<Link> revoke(String id) {
    return Optional.ofNullable(writeTx.execute(s -> links.findById(id).filter(l -> l.getRevokedAt() == null)
        .map(row -> {
          Link before = link(row);
          row.revoke(clock.instant());
          sign(row);
          links.saveAndFlush(row);
          return before;
        }).orElse(null)));
  }

  /** Revokes every link of {@code channel} (Disconnect). Call in the disconnect's transaction. */
  public List<Link> revokeAll(NotifyChannel channel) {
    Instant now = clock.instant();
    List<NotifyLinkEntity> rows = links.findByChannelAndRevokedAtIsNull(channel);
    List<Link> before = rows.stream().map(this::link).toList();
    for (NotifyLinkEntity row : rows) {
      row.revoke(now);
      sign(row);
    }
    links.saveAllAndFlush(rows);
    return before;
  }

  // ── Pairings ────────────────────────────────────────────────────────────────────────────────────────────────

  /** A new code, by hash, valid for {@code ttl}; {@code candidate} is known up front for Slack, null for Telegram. */
  public Pairing newPairing(NotifyChannel channel, String codeHash, Duration ttl, ObjectNode candidate) {
    return writeTx.execute(s -> {
      pairings.deleteExpired(clock.instant());
      NotifyPairingEntity row = new NotifyPairingEntity(codeHash, channel, clock.instant().plus(ttl));
      if (candidate != null) {
        row.presentedBy(signed(row, candidate));
      }
      pairings.saveAndFlush(row);
      return pairing(row);
    });
  }

  /** A pairing that can still be used: not consumed, not expired, its candidate as the core wrote it. */
  public Optional<Pairing> open(String id) {
    return readTx.execute(s -> pairings.findById(id).filter(this::usable).filter(this::trusted).map(this::pairing));
  }

  public List<Pairing> openPairings() {
    return readTx.execute(s -> pairings.findByConsumedAtIsNullAndExpiresAtAfter(clock.instant()).stream()
        .filter(this::trusted).map(this::pairing).toList());
  }

  /** The account that presented the code (Telegram); the person confirms it in the app. */
  public Optional<Pairing> present(String id, ObjectNode candidate) {
    return Optional.ofNullable(writeTx.execute(s -> pairings.findById(id).filter(this::usable).filter(this::trusted)
        .map(row -> {
          row.presentedBy(signed(row, candidate));
          pairings.saveAndFlush(row);
          return pairing(row);
        }).orElse(null)));
  }

  /**
   * Uses the code up: exactly one caller wins. Joins the caller's transaction (the link and its Setup change commit
   * with it).
   *
   * @return the pairing as it was before, or empty when it was already used, expired, unknown or changed outside the
   *     core
   */
  public Optional<Pairing> consume(String id) {
    return Optional.ofNullable(writeTx.execute(s -> pairings.findById(id).filter(this::usable).filter(this::trusted)
        .map(row -> {
          Pairing before = pairing(row);
          row.consume(clock.instant());
          pairings.saveAndFlush(row);
          return before;
        }).orElse(null)));
  }

  private boolean usable(NotifyPairingEntity p) {
    return p.getConsumedAt() == null && p.getExpiresAt().isAfter(clock.instant());
  }

  /** No candidate yet, or one whose MAC holds; anything else is reported and counts for nothing (fail closed). */
  private boolean trusted(NotifyPairingEntity p) {
    if (p.getCandidate() == null) {
      return true;
    }
    ObjectNode candidate = (ObjectNode) json.valueToTree(p.getCandidate());
    JsonNode stored = candidate.remove(MAC);
    boolean verified = false;
    if (stored != null && stored.isString()) {
      try {
        verified = mac.verify(PAIRING_TABLE, p.getCodeHash(), 0, pairingFields(p, candidate),
            Base64.getDecoder().decode(stored.asString()));
      } catch (IllegalArgumentException e) {
        verified = false;
      }
    }
    if (!verified) {
      alerts.report("notify-pairing", p.getCodeHash(), "Notifications · a code to link a " + p.getChannel().id()
          + " account", "the account waiting for it was changed outside GateRook: the code links no one; make a "
              + "new one");
    }
    return verified;
  }

  /** {@code candidate} with its MAC: over the channel, the code's hash and expiry, and the candidate itself. */
  private Map<String, Object> signed(NotifyPairingEntity row, ObjectNode candidate) {
    ObjectNode c = candidate.deepCopy();
    c.remove(MAC);
    // As it reads back from the column, so that the MAC checks against what a reload gives.
    ObjectNode stored = (ObjectNode) mac.tree(map(c));
    stored.put(MAC, Base64.getEncoder().encodeToString(mac.sign(PAIRING_TABLE, row.getCodeHash(), 0,
        pairingFields(row, (ObjectNode) mac.tree(map(c))))));
    return map(stored);
  }

  private ObjectNode pairingFields(NotifyPairingEntity p, ObjectNode candidateWithoutMac) {
    ObjectNode f = mac.fields();
    f.put("channel", p.getChannel().id());
    f.put("expiresAt", p.getExpiresAt().toEpochMilli());
    f.set("candidate", candidateWithoutMac);
    return f;
  }

  private Pairing pairing(NotifyPairingEntity p) {
    ObjectNode candidate = p.getCandidate() == null ? null : (ObjectNode) json.valueToTree(p.getCandidate());
    if (candidate != null) {
      candidate.remove(MAC);
    }
    return new Pairing(p.getCodeHash(), p.getChannel(), p.getExpiresAt(), candidate);
  }

  // ── Signing ─────────────────────────────────────────────────────────────────────────────────────────────────

  private Link link(NotifyLinkEntity e) {
    boolean verified = mac.verify(TABLE, e.getId(), 0, fields(e), e.getMac());
    if (!verified && e.getRevokedAt() == null) {
      alerts.report("notify-link", e.getId(), "Notifications · linked " + e.getChannel().id() + " account",
          "the link was changed outside GateRook: it receives no requests and can't decide until you link the "
              + "account again");
    }
    return new Link(e.getId(), e.getChannel(), e.getTeamId(), e.getUserId(), e.getChatId(), e.getDisplay(),
        e.getLinkedAt(), e.getRevokedAt(), verified);
  }

  private void sign(NotifyLinkEntity e) {
    e.setMac(mac.sign(TABLE, e.getId(), 0, fields(e)));
  }

  private ObjectNode fields(NotifyLinkEntity e) {
    ObjectNode f = mac.fields();
    f.put("channel", e.getChannel().id());
    f.put("teamId", e.getTeamId());
    f.put("userId", e.getUserId());
    f.put("chatId", e.getChatId());
    f.put("linkedAt", e.getLinkedAt().toEpochMilli());
    if (e.getRevokedAt() == null) {
      f.putNull("revokedAt");
    } else {
      f.put("revokedAt", e.getRevokedAt().toEpochMilli());
    }
    return f;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(ObjectNode node) {
    return json.convertValue(node, Map.class);
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
