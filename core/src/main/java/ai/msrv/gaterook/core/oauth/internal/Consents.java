package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.platform.EventBus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sign-ins waiting for the person at this computer (report 02 §6.3), in memory only: a restart turns every one into
 * a refusal. Each has a code {@code XXX-XXX} shown on the browser page and in the app, and a cookie only the browser
 * that started it holds ({@code gw_ar}, HttpOnly). At most 3 wait per endpoint and 10 {@code authorize} a minute per
 * browser address; no answer in 5 minutes is a refusal. The decision comes only from the app's
 * {@code POST /api/oauth/consents/{id}}, never from Slack, Telegram or the browser.
 */
final class Consents {

  static final Duration TIMEOUT = Duration.ofMinutes(5);
  static final int MAX_PER_ENDPOINT = 3;
  static final int MAX_PER_IP_MINUTE = 10;
  /** How long a decided sign-in waits for its browser to pick up the answer. */
  static final Duration PICKUP = Duration.ofMinutes(2);

  /**
   * One waiting sign-in. The decision and its redirect are set once, under the object's monitor; a decision of the
   * person is claimed first, so two answers of the app never both act on it.
   */
  static final class Pending {
    final String id;
    final String code;
    final String cookieHash;
    final Issuers.Server server;
    final Clients.Client client;
    final String redirectUri;
    final String state;
    final String challenge;
    final String scope;
    final String endpointId;
    final String slug;
    final String resource;
    final String browserIp;
    final Instant createdAt;
    final Instant expiresAt;
    private String decision;
    private String location;
    private boolean taken;
    private boolean claimed;
    private Instant decidedAt;

    Pending(String id, String code, String cookieHash, Issuers.Server server, Clients.Client client,
        String redirectUri, String state, String challenge, String scope, String endpointId, String slug,
        String resource, String browserIp, Instant createdAt) {
      this.id = id;
      this.code = code;
      this.cookieHash = cookieHash;
      this.server = server;
      this.client = client;
      this.redirectUri = redirectUri;
      this.state = state;
      this.challenge = challenge;
      this.scope = scope;
      this.endpointId = endpointId;
      this.slug = slug;
      this.resource = resource;
      this.browserIp = browserIp;
      this.createdAt = createdAt;
      this.expiresAt = createdAt.plus(TIMEOUT);
    }

    synchronized boolean waiting() {
      return decision == null;
    }

    synchronized String decision() {
      return decision;
    }

    /**
     * The person's answer takes this sign-in: {@code false} when another answer took it or it was decided (or
     * expired) already. Expiry doesn't claim: it can still win the race, and then the claimed answer fails to decide.
     */
    synchronized boolean claim() {
      if (claimed || decision != null) {
        return false;
      }
      claimed = true;
      return true;
    }

    /** Sets the decision once; {@code false} when it was decided (or expired) before. */
    synchronized boolean decide(String decision, String location, Instant at) {
      if (this.decision != null) {
        return false;
      }
      this.decision = decision;
      this.location = location;
      this.decidedAt = at;
      return true;
    }

    /** The redirect for the browser, once. */
    synchronized Optional<String> take() {
      if (location == null || taken) {
        return Optional.empty();
      }
      taken = true;
      return Optional.of(location);
    }

    synchronized boolean finished(Instant now) {
      return taken || decidedAt != null && decidedAt.plus(PICKUP).isBefore(now);
    }
  }

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  /** Guards the per-endpoint count and the insertion together: a burst of requests can't slip past the limit. */
  private final Object openLock = new Object();
  private final RateWindow perIp;
  private final EventBus events;
  private final Clock clock;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("oauth-consents").factory());
  private volatile Function<Pending, String> expiredLocation = p -> null;
  private volatile Consumer<Pending> expiredRecord = p -> { };

  Consents(EventBus events, Clock clock) {
    this.events = events;
    this.clock = clock;
    this.perIp = new RateWindow(MAX_PER_IP_MINUTE, Duration.ofMinutes(1), clock);
    timer.scheduleWithFixedDelay(this::sweep, 5, 5, TimeUnit.SECONDS);
  }

  /**
   * What a sign-in nobody answered becomes (set by the server that knows its issuer): {@code location} makes its
   * redirect, {@code record} writes its outcome — only once the expiry, and not an answer at the same moment, decided
   * it.
   */
  void onExpiry(Function<Pending, String> location, Consumer<Pending> record) {
    this.expiredLocation = location;
    this.expiredRecord = record;
  }

  /** Whether a browser at {@code ip} (an IPv6 one by its /64) may start another sign-in now (counts this attempt). */
  boolean admitBrowser(String ip) {
    return perIp.tryAcquire(RateWindow.addressKey(ip));
  }

  /**
   * Opens {@code p} unless {@value #MAX_PER_ENDPOINT} sign-ins of its endpoint are waiting already; the count and
   * the insertion are one step.
   */
  boolean tryOpen(Pending p) {
    synchronized (openLock) {
      long waiting = pending.values().stream().filter(o -> o.endpointId.equals(p.endpointId) && o.waiting())
          .count();
      if (waiting >= MAX_PER_ENDPOINT) {
        return false;
      }
      pending.put(p.id, p);
    }
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("id", p.id);
    data.put("code", p.code);
    data.put("clientHost", p.client.host());
    data.put("endpoint", p.slug);
    data.put("expiresAt", p.expiresAt.toString());
    events.publish("oauth.consent.requested", data);
    return true;
  }

  Optional<Pending> get(String id) {
    return Optional.ofNullable(pending.get(id));
  }

  /** The sign-ins still waiting, oldest first (the dialog shows the oldest). */
  List<Pending> waiting() {
    List<Pending> out = new ArrayList<>();
    Instant now = clock.instant();
    for (Pending p : pending.values()) {
      if (p.waiting() && p.expiresAt.isAfter(now)) {
        out.add(p);
      }
    }
    out.sort(Comparator.comparing(p -> p.createdAt));
    return out;
  }

  void resolved(Pending p, String decision) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("id", p.id);
    data.put("decision", decision);
    events.publish("oauth.consent.resolved", data);
  }

  /** Every 5 s: sign-ins nobody answered become refusals; answered ones go once their browser had its chance. */
  void sweep() {
    Instant now = clock.instant();
    for (Pending p : List.copyOf(pending.values())) {
      if (p.waiting() && !p.expiresAt.isAfter(now)) {
        try {
          if (p.decide("expired", expiredLocation.apply(p), now)) {
            resolved(p, "expired");
            expiredRecord.accept(p);
          }
        } catch (RuntimeException ignored) {
          // The redirect stands; a Setup change the database didn't take is the only loss.
        }
      }
      if (p.finished(now) || p.expiresAt.plus(PICKUP).isBefore(now)) {
        pending.remove(p.id);
      }
    }
  }

  void close() {
    timer.shutdownNow();
  }
}
