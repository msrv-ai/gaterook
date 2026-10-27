package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.notifications.internal.persistence.NotificationChannelEntity;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotificationChannelRepository;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.RowMac;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rows of {@code notification_channel}: a channel's switch, its config (bot tokens only as
 * {@code {"$secret": "<id>"}}) and what it last reported. The switch and the config are signed ({@link RowMac}, V30):
 * they say where exact actions go and which workspace and app a click must come from, so a row written around the
 * core reads as {@code verified = false} and the channel stays off until the person saves it again.
 */
@Component
public class ChannelStore {

  static final String TABLE = "notification_channel";

  /**
   * One channel as the module reads it.
   *
   * @param stored   a row exists (the person saved the channel at least once)
   * @param verified the row's MAC holds (always {@code true} without a row: the defaults are the core's own)
   */
  public record Row(NotifyChannel channel, boolean stored, boolean enabled, ObjectNode config, String state,
      String stateDetail, Instant lastDeliveryAt, Instant updatedAt, long version, boolean verified) {

    /** On, and trustworthy. */
    public boolean active() {
      return enabled && verified;
    }

    /** A string of the config, or {@code null}. */
    public String text(String key) {
      JsonNode v = config.path(key);
      return v.isString() ? v.asString() : null;
    }

    /** The secret id stored for {@code key} ({@code {"$secret": id}}), or {@code null}. */
    public String secretId(String key) {
      JsonNode v = config.path(key).path("$secret");
      return v.isString() ? v.asString() : null;
    }
  }

  private final NotificationChannelRepository channels;
  private final RowMac mac;
  private final IntegrityAlerts alerts;
  private final EventBus events;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  ChannelStore(NotificationChannelRepository channels, RowMac mac, IntegrityAlerts alerts, EventBus events,
      JsonMapper json, Clock clock, PlatformTransactionManager transactions) {
    this.channels = channels;
    this.mac = mac;
    this.alerts = alerts;
    this.events = events;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  /** The defaults of a channel nobody configured: desktop notifications on, Slack and Telegram not set up. */
  public static ObjectNode defaults(NotifyChannel channel) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    switch (channel) {
      case DESKTOP -> c.put("soundForProd", true).put("maxBurst", 3);
      case SLACK -> c.put("deliverTo", "dm");
      case TELEGRAM -> c.put("protectContent", true).put("confirmApprove", true);
    }
    return c;
  }

  public Row read(NotifyChannel channel) {
    return readTx.execute(s -> channels.findById(channel.id()).map(this::row).orElseGet(() -> new Row(channel, false,
        channel == NotifyChannel.DESKTOP, defaults(channel), null, null, null, null, 0, true)));
  }

  /**
   * Stores the channel as the person saved it and signs it: saving is the review that makes a changed row trusted
   * again. Call in the transaction that also stores its secrets and Setup changes.
   *
   * @param expectedVersion the version the form read ({@code 0} for a channel never saved)
   * @throws org.springframework.dao.OptimisticLockingFailureException when someone saved it meanwhile
   */
  public Row save(NotifyChannel channel, boolean enabled, ObjectNode config, long expectedVersion) {
    Instant now = clock.instant();
    NotificationChannelEntity row = channels.findById(channel.id()).orElse(null);
    long current = row == null ? 0 : row.getVersion() + 1;
    if (current != expectedVersion) {
      throw new org.springframework.dao.OptimisticLockingFailureException("The " + channel.id()
          + " channel is at version " + current + ", not " + expectedVersion);
    }
    if (row == null) {
      row = new NotificationChannelEntity(channel, enabled, map(config), now);
    } else {
      row.configure(enabled, map(config), now);
    }
    row = channels.saveAndFlush(row);
    row.setMac(mac.sign(TABLE, row.getChannel().id(), row.getVersion(), fields(row)));
    channels.saveAndFlush(row);
    alerts.resolve("notification-channel", channel.id());
    return row(row);
  }

  /** Forgets a channel (Disconnect): its row goes, the defaults come back. Call in the disconnect's transaction. */
  public void delete(NotifyChannel channel) {
    channels.findById(channel.id()).ifPresent(channels::delete);
    alerts.resolve("notification-channel", channel.id());
  }

  /**
   * What the channel reports ({@code connected}, {@code error} + why). Not signed and outside optimistic locking: it
   * decides nothing, and a form that read the channel keeps its version.
   */
  public void state(NotifyChannel channel, String state, String detail) {
    Boolean changed = writeTx.execute(s -> channels.findById(channel.id()).map(row -> {
      if (java.util.Objects.equals(row.getState(), state) && java.util.Objects.equals(row.getStateDetail(),
          detail)) {
        return false;
      }
      row.setState(state, detail == null ? null : cut(detail, 512));
      channels.saveAndFlush(row);
      return true;
    }).orElse(false));
    if (Boolean.TRUE.equals(changed)) {
      ObjectNode e = JsonNodeFactory.instance.objectNode().put("channel", channel.id()).put("state", state);
      e.put("detail", detail);
      events.publish("notify.state", e);
    }
  }

  /** The time of the channel's latest delivery ("Last request delivered 1 min ago"). */
  public void delivered(NotifyChannel channel, Instant at) {
    writeTx.executeWithoutResult(s -> channels.findById(channel.id()).ifPresent(row -> {
      row.setLastDeliveryAt(at);
      channels.saveAndFlush(row);
    }));
  }

  private Row row(NotificationChannelEntity e) {
    boolean verified = verified(e);
    if (!verified) {
      alerts.report("notification-channel", e.getChannel().id(), "Notifications · " + e.getChannel().id(),
          "the channel's settings were changed outside GateRook: it stays off until you save it again");
    }
    ObjectNode config = json.valueToTree(e.getConfig());
    return new Row(e.getChannel(), true, e.isEnabled(), config, e.getState(), e.getStateDetail(),
        e.getLastDeliveryAt(), e.getUpdatedAt(), e.getVersion() + 1, verified);
  }

  private boolean verified(NotificationChannelEntity e) {
    return mac.verify(TABLE, e.getChannel().id(), e.getVersion(), fields(e), e.getMac());
  }

  private ObjectNode fields(NotificationChannelEntity e) {
    ObjectNode f = mac.fields();
    f.put("kind", e.getChannel().id());
    f.put("enabled", e.isEnabled());
    f.set("config", mac.tree(e.getConfig()));
    return f;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(ObjectNode config) {
    return json.convertValue(config, Map.class);
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
