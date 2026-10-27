package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.platform.BuildFlags;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the channels' APIs are and how the module paces itself.
 *
 * <p>The API addresses are fixed: {@code https://slack.com/api/} and {@code https://api.telegram.org}. A person never
 * types them (report 13 §4 had an advanced {@code apiBase}; it would be a switch that sends the bot tokens and every
 * exact action anywhere), so only tests of a non-release build move them to a local fake
 * ({@code gateway.test.notifications.slack-api}, {@code …telegram-api}); a release ignores both, and then also
 * insists that Slack's Socket Mode URL is {@code wss://} on a {@code slack.com} host.
 */
@Component
public class NotifyConfig {

  private static final Logger log = LoggerFactory.getLogger(NotifyConfig.class);

  static final URI SLACK = URI.create("https://slack.com/api/");
  static final URI TELEGRAM = URI.create("https://api.telegram.org");

  private final URI slackApi;
  private final URI telegramApi;
  private final boolean testApis;
  private final Duration pingInterval;
  private final Duration pongTimeout;
  private final Duration pollTimeout;
  private final Duration pace;
  private final Duration retryDelay;
  private final Duration helloTimeout;
  private final int floodPerMinute;

  public NotifyConfig(@Value("${gateway.test.notifications.slack-api:}") String testSlack,
      @Value("${gateway.test.notifications.telegram-api:}") String testTelegram,
      @Value("${gateway.notifications.slack.ping-interval:30s}") Duration pingInterval,
      @Value("${gateway.notifications.slack.pong-timeout:10s}") Duration pongTimeout,
      @Value("${gateway.notifications.slack.hello-timeout:10s}") Duration helloTimeout,
      @Value("${gateway.notifications.telegram.poll-timeout:50s}") Duration pollTimeout,
      @Value("${gateway.notifications.pace:1s}") Duration pace,
      @Value("${gateway.notifications.retry-delay:1s}") Duration retryDelay,
      @Value("${gateway.notifications.flood-per-minute:" + Eligibility.FLOOD_PER_MINUTE + "}") int floodPerMinute) {
    boolean overridden = !testSlack.isBlank() || !testTelegram.isBlank();
    if (overridden && BuildFlags.RELEASE) {
      log.warn("gateway.test.notifications.* is ignored: this is a release build");
    }
    boolean use = overridden && !BuildFlags.RELEASE;
    this.slackApi = use && !testSlack.isBlank() ? directory(testSlack) : SLACK;
    this.telegramApi = use && !testTelegram.isBlank() ? URI.create(strip(testTelegram)) : TELEGRAM;
    this.testApis = use;
    this.pingInterval = pingInterval;
    this.pongTimeout = pongTimeout;
    this.helloTimeout = helloTimeout;
    this.pollTimeout = pollTimeout;
    this.pace = pace;
    this.retryDelay = retryDelay;
    // Only ever stricter than the rule: a higher limit would let a flood through one message at a time.
    this.floodPerMinute = BuildFlags.RELEASE ? Eligibility.FLOOD_PER_MINUTE : Math.max(1, floodPerMinute);
  }

  /**
   * New requests a minute a channel announces one by one ({@value Eligibility#FLOOD_PER_MINUTE}); tests of a
   * non-release build may raise it so that a run of ordinary requests isn't a flood.
   */
  public int floodPerMinute() {
    return floodPerMinute;
  }

  /** {@code https://slack.com/api/}: methods resolve against it ({@code chat.postMessage}). */
  public URI slackApi() {
    return slackApi;
  }

  /** {@code https://api.telegram.org}: {@code /bot<token>/<method>} goes after it. */
  public URI telegramApi() {
    return telegramApi;
  }

  /**
   * Whether {@code url} may be the Socket Mode connection Slack handed out: {@code wss://} on {@code slack.com} or a
   * subdomain; a test of a non-release build may use its local fake.
   */
  public boolean socketUrlAllowed(URI url) {
    if (url == null || url.getHost() == null) {
      return false;
    }
    if (testApis) {
      return "ws".equals(url.getScheme()) || "wss".equals(url.getScheme());
    }
    String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
    return "wss".equals(url.getScheme()) && (host.equals("slack.com") || host.endsWith(".slack.com"));
  }

  public Duration pingInterval() {
    return pingInterval;
  }

  public Duration pongTimeout() {
    return pongTimeout;
  }

  public Duration helloTimeout() {
    return helloTimeout;
  }

  /** {@code getUpdates} long-poll timeout (50 s: Telegram holds the request until an update or the timeout). */
  public Duration pollTimeout() {
    return pollTimeout;
  }

  /** At most one message per this interval to one chat or channel (Slack and Telegram both ask for 1/s). */
  public Duration pace() {
    return pace;
  }

  /** The first wait before a failed send is tried again (then three times as long). */
  public Duration retryDelay() {
    return retryDelay;
  }

  private static URI directory(String base) {
    return URI.create(base.endsWith("/") ? base : base + "/");
  }

  private static String strip(String base) {
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
