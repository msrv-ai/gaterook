package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mongodb.client.MongoClient;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MongoClientCacheTest {

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  @Test
  void aSlowFactoryDoesNotBlockAnotherConnection() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    MongoClient slow = mock(MongoClient.class);
    MongoClient fast = mock(MongoClient.class);
    try (var login = login();
         var cache = new MongoClientCache((settings, owned, timeout) -> {
           if (settings.parsed().firstHost().equals("slow.example.test")) {
             started.countDown();
             await(release);
             return slow;
           }
           return fast;
         }, Instant::now);
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionConfig slowConfig = config("slow", "slow.example.test", 1);
      ConnectionConfig fastConfig = config("fast", "fast.example.test", 1);
      var slowResult = executor.submit(() -> cache.acquire(slowConfig, MongoSettings.of(slowConfig), login,
          READ_TIMEOUT));
      try {
        await(started);
        var fastResult = executor.submit(() -> cache.acquire(fastConfig, MongoSettings.of(fastConfig), login,
            READ_TIMEOUT));
        try (var lease = fastResult.get(2, TimeUnit.SECONDS)) {
          assertThat(lease.client()).isSameAs(fast);
        }
      } finally {
        release.countDown();
      }
      try (var lease = slowResult.get(2, TimeUnit.SECONDS)) {
        assertThat(lease.client()).isSameAs(slow);
      }
    }
    verify(slow).close();
    verify(fast).close();
  }

  @Test
  void closingDuringAFactoryClosesItsResultAndClearsItsPassword() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var owned = new AtomicReference<MongoLogin>();
    MongoClient client = mock(MongoClient.class);
    try (var login = login();
         var cache = new MongoClientCache((settings, copy, timeout) -> {
           owned.set(copy);
           started.countDown();
           await(release);
           return client;
         }, Instant::now);
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionConfig cfg = config("closing", "mongo.example.test", 1);
      var result = executor.submit(() -> cache.acquire(cfg, MongoSettings.of(cfg), login, READ_TIMEOUT));
      try {
        await(started);
        cache.close();
      } finally {
        release.countDown();
      }
      assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(cache.size()).isZero();
      verify(client).close();
      assertThat(owned.get().password()).containsOnly('\0');
      assertThat(login.password()).isEqualTo("test-password".toCharArray());
    }
  }

  @Test
  void concurrentMatchingFactoriesReuseTheWinnerAndCloseTheUnusedClient() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var unusedLogin = new AtomicReference<MongoLogin>();
    MongoClient unused = mock(MongoClient.class);
    MongoClient winner = mock(MongoClient.class);
    try (var login = login();
         var cache = new MongoClientCache((settings, owned, timeout) -> {
           if (calls.getAndIncrement() == 0) {
             unusedLogin.set(owned);
             started.countDown();
             await(release);
             return unused;
           }
           return winner;
         }, Instant::now);
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionConfig cfg = config("same", "mongo.example.test", 1);
      var first = executor.submit(() -> cache.acquire(cfg, MongoSettings.of(cfg), login, READ_TIMEOUT));
      try {
        await(started);
        try (var second = cache.acquire(cfg, MongoSettings.of(cfg), login, READ_TIMEOUT)) {
          assertThat(second.client()).isSameAs(winner);
          release.countDown();
          try (var duplicate = first.get(2, TimeUnit.SECONDS)) {
            assertThat(duplicate.client()).isSameAs(winner);
            assertThat(cache.size()).isEqualTo(1);
            verify(unused).close();
            verify(winner, never()).close();
            assertThat(unusedLogin.get().password()).containsOnly('\0');
          }
        }
      } finally {
        release.countDown();
      }
    }
    verify(winner).close();
  }

  @Test
  void droppingDuringAFactoryDoesNotRepopulateTheCache() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var owned = new AtomicReference<MongoLogin>();
    MongoClient client = mock(MongoClient.class);
    try (var login = login();
         var cache = new MongoClientCache((settings, copy, timeout) -> {
           owned.set(copy);
           started.countDown();
           await(release);
           return client;
         }, Instant::now);
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionConfig cfg = config("dropped", "mongo.example.test", 1);
      var result = executor.submit(() -> cache.acquire(cfg, MongoSettings.of(cfg), login, READ_TIMEOUT));
      try {
        await(started);
        cache.drop(cfg.id());
      } finally {
        release.countDown();
      }
      try (var lease = result.get(2, TimeUnit.SECONDS)) {
        assertThat(lease.client()).isSameAs(client);
        assertThat(cache.size()).isZero();
        assertThat(cache.existing(cfg)).isNull();
        verify(client, never()).close();
      }
      verify(client).close();
      assertThat(owned.get().password()).containsOnly('\0');
    }
  }

  @Test
  void anOlderFactoryCannotReplaceANewerConnectionVersion() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    MongoClient older = mock(MongoClient.class);
    MongoClient newer = mock(MongoClient.class);
    try (var login = login();
         var cache = new MongoClientCache((settings, owned, timeout) -> {
           if (settings.parsed().firstHost().equals("old.example.test")) {
             started.countDown();
             await(release);
             return older;
           }
           return newer;
         }, Instant::now);
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionConfig oldConfig = config("same", "old.example.test", 1);
      ConnectionConfig newConfig = config("same", "new.example.test", 2);
      var result = executor.submit(() -> cache.acquire(oldConfig, MongoSettings.of(oldConfig), login, READ_TIMEOUT));
      try {
        await(started);
        try (var lease = cache.acquire(newConfig, MongoSettings.of(newConfig), login, READ_TIMEOUT)) {
          assertThat(lease.client()).isSameAs(newer);
        }
      } finally {
        release.countDown();
      }
      try (var lease = result.get(2, TimeUnit.SECONDS)) {
        assertThat(lease.client()).isSameAs(older);
        try (var cached = cache.existing(newConfig)) {
          assertThat(cached.client()).isSameAs(newer);
        }
        assertThat(cache.existing(oldConfig)).isNull();
      }
      verify(older).close();
      verify(newer, never()).close();
    }
    verify(newer).close();
  }

  private static ConnectionConfig config(String id, String host, long version) {
    return Fixtures.config(id, Env.DEV, "{\"connection\":{\"host\":\"" + host + "\"}}", version);
  }

  private static MongoLogin login() {
    return new MongoLogin("reader", "test-password".toCharArray(), "v1", null);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the test factory", e);
    }
  }
}
