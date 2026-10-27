package ai.msrv.gaterook.core.secrets.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Values read from external stores, kept briefly so a burst of calls doesn't hit the store each time (report 12
 * §7.6): per store and canonical reference, for the store's {@code cacheTtlSeconds} (60 s by default, at most 300),
 * and never more than 15 s for a prod connection. Every entry's bytes are zeroed when it goes — expired, replaced or
 * dropped with its store. Local values and dynamic credentials never come here. A reader gets its own copy.
 */
public final class SecretCache {

  /** The longest a prod connection uses a cached value (report 12 §4.3 "Stricter production rules"). */
  public static final Duration PROD_MAX = Duration.ofSeconds(15);

  private record Key(String storeId, String uri) {}

  private record Entry(RemoteValue value, Instant fetchedAt, Duration ttl) {}

  private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
  private final Clock clock;

  public SecretCache(Clock clock) {
    this.clock = clock;
  }

  /** A copy of the value of {@code uri}, if it was read less than {@code maxAge} ago. */
  public Optional<RemoteValue> get(String storeId, String uri, Duration maxAge) {
    Entry e = entries.get(new Key(storeId, uri));
    if (e == null || maxAge.isZero() || !clock.instant().isBefore(e.fetchedAt().plus(maxAge))) {
      return Optional.empty();
    }
    return Optional.of(e.value().copy());
  }

  /** Keeps a copy of {@code value} for {@code ttl}; a zero TTL keeps nothing. */
  public void put(String storeId, String uri, RemoteValue value, Duration ttl) {
    if (ttl.isZero() || ttl.isNegative()) {
      return;
    }
    Entry old = entries.put(new Key(storeId, uri), new Entry(value.copy(), clock.instant(), ttl));
    if (old != null) {
      old.value().close();
    }
  }

  /** Forgets one reference (Replace reference, a failed read). */
  public void evict(String storeId, String uri) {
    Entry old = entries.remove(new Key(storeId, uri));
    if (old != null) {
      old.value().close();
    }
  }

  /** Forgets every value of a store (its settings changed, it was disconnected). */
  public void evictStore(String storeId) {
    entries.entrySet().removeIf(e -> {
      if (e.getKey().storeId().equals(storeId)) {
        e.getValue().value().close();
        return true;
      }
      return false;
    });
  }

  /** Drops what expired; run every few seconds. */
  public int sweep() {
    Instant now = clock.instant();
    int[] n = {0};
    entries.entrySet().removeIf(e -> {
      if (!now.isBefore(e.getValue().fetchedAt().plus(e.getValue().ttl()))) {
        e.getValue().value().close();
        n[0]++;
        return true;
      }
      return false;
    });
    return n[0];
  }

  public void clear() {
    entries.values().forEach(e -> e.value().close());
    entries.clear();
  }

  int size() {
    return entries.size();
  }
}
