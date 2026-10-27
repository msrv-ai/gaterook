package ai.msrv.gaterook.core.notifications;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Settings › Notifications as the admin API answers it (ARCHITECTURE.md §6 settings). No record holds a token: a
 * stored token shows as its field, state and version only.
 */
public final class NotificationViews {

  private NotificationViews() {}

  /**
   * {@code GET /api/notifications}.
   *
   * @param decisionsFrom Settings › Approval rules: {@code app} or {@code app-and-linked}
   * @param host          this computer's name, as messages call it ("MCP Gateway on ravi-mbp")
   */
  public record Overview(List<Channel> channels, List<Link> links, String decisionsFrom, String host) {}

  /**
   * One channel.
   *
   * @param config   the channel's settings without its tokens: desktop {@code soundForProd, maxBurst}; Slack
   *                 {@code deliverTo, channelId, channelName, workspace{team, teamId, bot, botUserId, appId}};
   *                 Telegram {@code protectContent, confirmApprove, bot{id, username}}
   * @param state    {@code off}, {@code connecting}, {@code connected}, {@code error}, {@code stopped},
   *                 {@code changed-outside} (Slack, Telegram); the desktop reports {@code ok} or {@code failed} from
   *                 Electron
   * @param version  for {@code PUT}: {@code 0} until the channel was saved once
   * @param verified the stored settings verify; {@code false}: changed outside MCP Gateway, off until saved again
   * @param tokens   what is stored for the channel's tokens
   */
  public record Channel(String kind, boolean enabled, JsonNode config, String state, String stateDetail,
      Instant lastDeliveryAt, long version, boolean verified, List<StoredToken> tokens) {}

  /** A stored token: {@code field} {@code /botToken} or {@code /appToken}, {@code state} {@code stored}. */
  public record StoredToken(String field, String state, Integer version, Instant updatedAt) {}

  /**
   * A linked account, or one on its way.
   *
   * @param state     {@code linked}; {@code pending} (Slack: waiting for Link account in the DM; Telegram: waiting
   *                  for {@code /start}), {@code presented} (Telegram: an account sent the code, confirm it),
   *                  {@code changed-outside} (the link doesn't verify: it receives nothing and decides nothing)
   * @param code      Slack's short code the DM shows too, while pending
   * @param expiresAt when a pending code stops working
   */
  public record Link(String id, String channel, String display, Instant linkedAt, String state, String code,
      Instant expiresAt) {}

  /** {@code POST /api/notifications/telegram/pairing}: the code (shown this once) and its {@code t.me} link. */
  public record Pairing(String id, String code, String link, Instant expiresAt) {}

  /** {@code POST /api/notifications/test}: what each channel did with the test message. */
  public record Sent(List<SentTo> channels) {}

  /** {@code state}: {@code sent}, {@code failed} or {@code off}. */
  public record SentTo(String channel, String state, String detail) {}

  /** A Slack channel the app was added to. */
  public record SlackChannelOption(String id, String name, boolean isPrivate) {}
}
