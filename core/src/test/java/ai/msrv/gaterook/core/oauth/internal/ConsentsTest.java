package ai.msrv.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ingress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sign-ins waiting for the person (report 02 §6.3) under concurrency: the per-endpoint limit holds for a burst, one
 * answer acts on a sign-in, and an expiry records only what it decided.
 */
class ConsentsTest {

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-26T10:00:00Z"));
  private final Clock clock = new Clock() {
    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  };
  private final Consents consents = new Consents(new EventBus(clock), clock);

  @AfterEach
  void tearDown() {
    consents.close();
  }

  private Consents.Pending pending(String id, String endpointId) {
    return pending(id, endpointId, "127.0.0.1");
  }

  private Consents.Pending pending(String id, String endpointId, String browserIp) {
    return pending(id, endpointId, browserIp, Ingress.LOCAL);
  }

  private Consents.Pending pending(String id, String endpointId, String browserIp, Ingress ingress) {
    Issuers.Server server = new Issuers.Server(ingress, ingress == Ingress.LOCAL ? "http://127.0.0.1:7465"
        : "https://gw-smoke-7f3k.trycloudflare.com", null);
    Clients.Client client = new Clients.Client("row-1", "gwc_client", OAuthClientKind.DCR, Ingress.LOCAL, "127.0.0.1",
        "Cursor", List.of("http://127.0.0.1/callback"));
    return new Consents.Pending(id, "ABC-DEF", "cookie-hash", server, client, "http://127.0.0.1:5000/callback",
        "st", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "mcp", endpointId, "dev",
        "http://127.0.0.1:7465/mcp/dev", browserIp, clock.instant());
  }

  @Test
  void aBurstOfSignInsFromManyBrowsersOpensAtMostTenForAnEndpoint() throws Exception {
    int burst = 32;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger opened = new AtomicInteger();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < burst; i++) {
      Consents.Pending p = pending("ar_burst" + i, "endpoint-1", "203.0.113." + (i + 1));
      threads.add(Thread.ofVirtual().start(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (consents.tryOpen(p)) {
          opened.incrementAndGet();
        }
      }));
    }
    start.countDown();
    for (Thread t : threads) {
      t.join(Duration.ofSeconds(10));
    }
    assertThat(opened.get()).isEqualTo(Consents.MAX_PER_ENDPOINT);
    assertThat(consents.waiting()).hasSize(Consents.MAX_PER_ENDPOINT);
    assertThat(consents.open(pending("ar_late", "endpoint-1", "203.0.113.99"))).isEqualTo(Consents.Room.ENDPOINT_FULL);
    // Another endpoint has room of its own.
    assertThat(consents.tryOpen(pending("ar_other", "endpoint-2"))).isTrue();
  }

  @Test
  void oneBrowserHoldsAtMostTwoWaitingSignInsAndNeverTheWholeEndpoint() {
    assertThat(consents.open(pending("ar_b1", "endpoint-1", "198.51.100.7"))).isEqualTo(Consents.Room.OPEN);
    assertThat(consents.open(pending("ar_b2", "endpoint-1", "198.51.100.7"))).isEqualTo(Consents.Room.OPEN);
    assertThat(consents.open(pending("ar_b3", "endpoint-1", "198.51.100.7"))).isEqualTo(Consents.Room.BROWSER_FULL);
    // An IPv6 browser is its /64; another browser, and the owner's, still get in.
    assertThat(consents.open(pending("ar_v6a", "endpoint-1", "2001:db8:1:2::1"))).isEqualTo(Consents.Room.OPEN);
    assertThat(consents.open(pending("ar_v6b", "endpoint-1", "2001:db8:1:2::2"))).isEqualTo(Consents.Room.OPEN);
    assertThat(consents.open(pending("ar_v6c", "endpoint-1", "2001:db8:1:2::3"))).isEqualTo(Consents.Room.BROWSER_FULL);
    assertThat(consents.open(pending("ar_owner", "endpoint-1", "192.0.2.10"))).isEqualTo(Consents.Room.OPEN);
    // The same browser may wait on another endpoint.
    assertThat(consents.open(pending("ar_b4", "endpoint-2", "198.51.100.7"))).isEqualTo(Consents.Room.OPEN);
  }

  @Test
  void aBrowserIsItsAddressOnItsListenerAndTheTunnelsUnknownCallersAreOne() {
    // Whatever arrives through the tunnel never uses up what this computer's own browser may open locally.
    for (int i = 0; i < Consents.MAX_PER_IP_MINUTE; i++) {
      assertThat(consents.admitBrowser(Ingress.TUNNEL, "127.0.0.1")).isTrue();
    }
    assertThat(consents.admitBrowser(Ingress.TUNNEL, "127.0.0.1")).isFalse();
    assertThat(consents.admitBrowser(Ingress.LOCAL, "127.0.0.1")).isTrue();
    // Callers the edge named no address for count together, apart from every address.
    for (int i = 0; i < Consents.MAX_PER_IP_MINUTE; i++) {
      assertThat(consents.admitBrowser(Ingress.TUNNEL, null)).isTrue();
    }
    assertThat(consents.admitBrowser(Ingress.TUNNEL, null)).isFalse();
    assertThat(consents.admitBrowser(Ingress.TUNNEL, "203.0.113.5")).isTrue();

    assertThat(consents.open(pending("ar_u1", "endpoint-1", null, Ingress.TUNNEL))).isEqualTo(Consents.Room.OPEN);
    assertThat(consents.open(pending("ar_u2", "endpoint-1", null, Ingress.TUNNEL))).isEqualTo(Consents.Room.OPEN);
    assertThat(consents.open(pending("ar_u3", "endpoint-1", null, Ingress.TUNNEL)))
        .isEqualTo(Consents.Room.BROWSER_FULL);
    for (String id : List.of("ar_t1", "ar_t2")) {
      assertThat(consents.open(pending(id, "endpoint-1", "127.0.0.1", Ingress.TUNNEL))).isEqualTo(Consents.Room.OPEN);
    }
    assertThat(consents.open(pending("ar_l1", "endpoint-1", "127.0.0.1", Ingress.LOCAL))).as("this computer's browser")
        .isEqualTo(Consents.Room.OPEN);
  }

  @Test
  void oneAnswerActsOnASignIn() {
    Consents.Pending p = pending("ar_answer", "endpoint-1");
    assertThat(p.claim()).isTrue();
    assertThat(p.claim()).as("a second answer").isFalse();
    assertThat(p.waiting()).as("claimed, not decided yet").isTrue();
    assertThat(p.decide("allowed", "http://127.0.0.1:5000/callback?code=x", clock.instant())).isTrue();

    Consents.Pending decided = pending("ar_decided", "endpoint-1");
    assertThat(decided.decide("expired", "http://127.0.0.1:5000/callback?error=access_denied", clock.instant()))
        .isTrue();
    assertThat(decided.claim()).as("expired before the answer").isFalse();
  }

  @Test
  void anExpiryRecordsOnlyASignInItDecided() {
    List<String> recorded = new ArrayList<>();
    consents.onExpiry(p -> "http://127.0.0.1:5000/callback?error=access_denied", p -> recorded.add(p.id));
    Consents.Pending answered = pending("ar_answered", "endpoint-1");
    Consents.Pending silent = pending("ar_silent", "endpoint-1");
    assertThat(consents.tryOpen(answered)).isTrue();
    assertThat(consents.tryOpen(silent)).isTrue();
    // The person's Allow took the first one just as its five minutes ran out.
    assertThat(answered.claim()).isTrue();
    now.set(now.get().plus(Consents.TIMEOUT).plusSeconds(1));
    assertThat(answered.decide("allowed", "http://127.0.0.1:5000/callback?code=x", clock.instant())).isTrue();

    consents.sweep();
    assertThat(recorded).containsExactly("ar_silent");
    assertThat(answered.decision()).isEqualTo("allowed");
    assertThat(silent.decision()).isEqualTo("expired");
    assertThat(silent.take()).contains("http://127.0.0.1:5000/callback?error=access_denied");
  }
}
