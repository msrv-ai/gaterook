package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The short cache of store values (report 12 §7.6): the store's TTL, at most 15 s for prod, copies only. */
class SecretCacheTest {

  private final VaultStoreTest.TestClock clock = new VaultStoreTest.TestClock(Instant.parse("2026-10-20T10:00:00Z"));
  private final SecretCache cache = new SecretCache(clock);

  private static RemoteValue value(String text) {
    return new RemoteValue(text.getBytes(StandardCharsets.UTF_8), "4", null, "string", List.of());
  }

  @Test
  void prodGetsAtMostFifteenSecondsOfAStoresMinute() {
    cache.put("st_1", "vault://kv/stage/db#password", value("cnry-cached"), Duration.ofSeconds(60));
    clock.advance(Duration.ofSeconds(14));
    assertThat(cache.get("st_1", "vault://kv/stage/db#password", SecretCache.PROD_MAX)).isPresent();
    clock.advance(Duration.ofSeconds(2));
    assertThat(cache.get("st_1", "vault://kv/stage/db#password", SecretCache.PROD_MAX)).as("prod").isEmpty();
    assertThat(cache.get("st_1", "vault://kv/stage/db#password", Duration.ofSeconds(60))).as("stage").isPresent();
    clock.advance(Duration.ofSeconds(45));
    assertThat(cache.get("st_1", "vault://kv/stage/db#password", Duration.ofSeconds(60))).isEmpty();
    assertThat(cache.sweep()).isEqualTo(1);
    assertThat(cache.size()).isZero();
  }

  @Test
  void readersGetCopiesAndAZeroTtlKeepsNothing() {
    RemoteValue original = value("cnry-original");
    cache.put("st_1", "a", original, Duration.ofSeconds(30));
    original.close();
    RemoteValue first = cache.get("st_1", "a", Duration.ofSeconds(30)).orElseThrow();
    first.close();
    RemoteValue second = cache.get("st_1", "a", Duration.ofSeconds(30)).orElseThrow();
    assertThat(new String(second.value(), StandardCharsets.UTF_8)).isEqualTo("cnry-original");
    assertThat(second.toString()).doesNotContain("cnry");

    cache.put("st_1", "b", value("x"), Duration.ZERO);
    assertThat(cache.get("st_1", "b", Duration.ofSeconds(30))).isEmpty();
    cache.put("st_2", "c", value("y"), Duration.ofSeconds(30));
    cache.evictStore("st_1");
    assertThat(cache.get("st_1", "a", Duration.ofSeconds(30))).isEmpty();
    assertThat(cache.get("st_2", "c", Duration.ofSeconds(30))).isPresent();
    cache.clear();
    assertThat(cache.size()).isZero();
  }
}
