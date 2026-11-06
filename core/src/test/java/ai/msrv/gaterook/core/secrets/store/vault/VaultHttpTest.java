package ai.msrv.gaterook.core.secrets.store.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * What a store's name resolves to is judged before each request (ARCHITECTURE.md §9): private addresses are fine,
 * cloud metadata and GateRook's own listener never are, whatever the name.
 */
class VaultHttpTest {

  private final StoreCall call = StoreCall.within(v -> { }, Duration.ofSeconds(5), Clock.systemUTC());

  @Test
  void namesThatResolveToMetadataOrToTheCoreAreRefusedBeforeARequest() throws Exception {
    try (MockWebServer core = new MockWebServer()) {
      core.start(InetAddress.getByName("127.0.0.1"), 0);
      HostResolver resolver = names(Map.of("vault.acme.internal", "169.254.169.254",
          "vault6.acme.internal", "fd00:ec2::254", "vault.core.internal", "127.0.0.1"));

      for (String address : List.of("https://vault.acme.internal:8200", "https://vault6.acme.internal")) {
        try (VaultHttp http = new VaultHttp(settings(address), Set.of(), resolver)) {
          assertThatThrownBy(() -> http.send("GET", "sys/health", null, null, call)).as(address)
              .isInstanceOfSatisfying(StoreException.class, e -> {
                assertThat(e.reason()).isEqualTo(StoreException.Reason.INVALID);
                assertThat(e.getMessage()).contains("resolves to", "cloud metadata");
              });
        }
      }
      try (VaultHttp http = new VaultHttp(settings("http://vault.core.internal:" + core.getPort()),
          Set.of(core.getPort()), resolver)) {
        assertThatThrownBy(() -> http.send("GET", "sys/health", null, "hvs.not-for-the-core".toCharArray(), call))
            .isInstanceOfSatisfying(StoreException.class, e -> {
              assertThat(e.reason()).isEqualTo(StoreException.Reason.INVALID);
              assertThat(e.getMessage()).contains("is GateRook itself");
            });
      }
      assertThat(core.getRequestCount()).isZero();
    }
  }

  @Test
  void aStoreOnAPrivateAddressIsReachedAsBefore() throws Exception {
    try (MockWebServer vault = new MockWebServer()) {
      vault.start(InetAddress.getByName("127.0.0.1"), 0);
      vault.enqueue(new MockResponse.Builder().code(200).body("{}").build());
      int corePort = vault.getPort() == 7465 ? 7466 : 7465;
      try (VaultHttp http = new VaultHttp(settings("http://127.0.0.1:" + vault.getPort()), Set.of(corePort),
          HostResolver.SYSTEM); VaultHttp.Response r = http.send("GET", "sys/health", null, null, call)) {
        assertThat(r.status()).isEqualTo(200);
      }
      assertThat(vault.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void aResponseBodyThatStallsAfterItsHeadersCannotOutliveTheCallDeadline() throws Exception {
    try (MockWebServer vault = new MockWebServer()) {
      vault.start(InetAddress.getByName("127.0.0.1"), 0);
      vault.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
          .body("{\"value\":\"never-echo-this-body\"}").throttleBody(1, 1, TimeUnit.SECONDS).build());
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        try (VaultHttp http = new VaultHttp(settings("http://127.0.0.1:" + vault.getPort()), Set.of(), HostResolver.SYSTEM)) {
          StoreCall shortCall = StoreCall.within(v -> { }, Duration.ofMillis(600), Clock.systemUTC());
          assertThatThrownBy(() -> http.send("GET", "auth/token/lookup-self", null,
              "fixture-private-token".toCharArray(), shortCall)).isInstanceOfSatisfying(StoreException.class, error -> {
                assertThat(error.reason()).isEqualTo(StoreException.Reason.UNAVAILABLE);
                assertThat(error.getMessage()).contains("didn't answer in time")
                    .doesNotContain("never-echo-this-body", "fixture-private-token");
              });
        }
      });
      assertThat(vault.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void interruptingAResponseWaitCancelsTheExchangeAndPreservesTheInterruptFlag() throws Exception {
    try (MockWebServer vault = new MockWebServer()) {
      vault.start(InetAddress.getByName("127.0.0.1"), 0);
      vault.enqueue(new MockResponse.Builder().code(200).body("{\"ok\":true}")
          .throttleBody(1, 1, TimeUnit.SECONDS).build());
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        try (VaultHttp http = new VaultHttp(settings("http://127.0.0.1:" + vault.getPort()), Set.of(), HostResolver.SYSTEM)) {
          AtomicReference<Throwable> failure = new AtomicReference<>();
          AtomicBoolean interrupted = new AtomicBoolean();
          Thread worker = Thread.ofVirtual().start(() -> {
            try (VaultHttp.Response ignored = http.send("GET", "sys/health", null, null,
                StoreCall.within(v -> { }, Duration.ofSeconds(30), Clock.systemUTC()))) {
              failure.set(new AssertionError("The interrupted request must not complete normally."));
            } catch (Throwable error) {
              failure.set(error);
              interrupted.set(Thread.currentThread().isInterrupted());
            }
          });
          try {
            assertThat(vault.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            worker.interrupt();
            worker.join(Duration.ofSeconds(2));
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(StoreException.class).hasMessageContaining("cancelled");
            assertThat(interrupted).isTrue();
          } finally { worker.interrupt(); }
        }
      });
    }
  }

  private static VaultSettings settings(String address) {
    return new VaultSettings(URI.create(address), null, null, VaultSettings.Auth.TOKEN, "approle", null, null,
        Duration.ofHours(1), 60, false);
  }

  private static HostResolver names(Map<String, String> names) {
    return host -> {
      String address = names.get(host);
      if (address == null) {
        throw new UnknownHostException(host);
      }
      return List.of(InetAddress.getByName(address));
    };
  }
}
