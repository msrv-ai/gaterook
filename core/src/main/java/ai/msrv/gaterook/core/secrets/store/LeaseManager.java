package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leases of Vault dynamic database credentials (report 12 §6, report 04 §6.7): one lease per connection's secret
 * field, shared by its calls — a pool built on it keeps its user — instead of a database user per call. While in use
 * the lease is renewed after two thirds of its duration; a new one is taken when less than a minute is left (the
 * maximum TTL was reached or renewal failed), and the connector replaces its pool on the new version. A lease nobody
 * used for {@link #IDLE} is revoked, as are all leases when the core stops; leases a crashed core left behind
 * ({@code secret_lease} rows not revoked) are revoked at the next start. The password lives only in memory.
 */
public final class LeaseManager implements AutoCloseable {

  /** A lease unused this long is revoked: the SQL connector closes an idle pool after 10 minutes. */
  public static final Duration IDLE = Duration.ofMinutes(15);
  /** A lease with less than this left is not handed out again (the pool's lifetime ends a minute before). */
  public static final Duration MARGIN = Duration.ofSeconds(60);

  private static final Logger log = LoggerFactory.getLogger(LeaseManager.class);

  /** The login of one use: a copy of the password the receiver zeroes. */
  public record Login(String username, char[] password, String version, Instant notAfter) {}

  /** What Secrets › Short-lived shows: no password. */
  public record Active(String secretId, String storeId, String ref, Instant issuedAt, Instant notAfter,
      Duration ttl) {}

  private static final class Lease {
    final String secretId;
    final String storeId;
    final String ref;
    final VaultStore.Lease lease;
    final Instant issuedAt;
    volatile Instant renewedAt;
    volatile Instant notAfter;
    volatile Instant lastUsed;

    Lease(String secretId, String storeId, String ref, VaultStore.Lease lease, Instant issuedAt) {
      this.secretId = secretId;
      this.storeId = storeId;
      this.ref = ref;
      this.lease = lease;
      this.issuedAt = issuedAt;
      this.renewedAt = issuedAt;
      this.notAfter = issuedAt.plus(lease.ttl());
      this.lastUsed = issuedAt;
    }

    String version() {
      String id = lease.leaseId();
      String tail = id.substring(id.lastIndexOf('/') + 1);
      return "lease " + (tail.length() > 8 ? tail.substring(0, 8) : tail);
    }
  }

  /** How the manager reaches the Vault of a lease. */
  public interface Vaults {
    VaultStore vault(String storeId);
  }

  private final Vaults vaults;
  private final SecretLeaseRepository leases;
  private final SecretRepository secrets;
  private final ExternalStoreRepository stores;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final Map<String, Lease> active = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofVirtual().name("vault-leases").factory());

  public LeaseManager(Vaults vaults, SecretLeaseRepository leases, SecretRepository secrets,
      ExternalStoreRepository stores, Clock clock, PlatformTransactionManager tx) {
    this.vaults = vaults;
    this.leases = leases;
    this.secrets = secrets;
    this.stores = stores;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(tx);
  }

  /**
   * Starts the renew/idle job and, on its thread, revokes what an earlier run left behind: a Vault that doesn't
   * answer never holds up the ready line.
   */
  public void start(Duration every) {
    scheduler.execute(() -> {
      try {
        revokeOrphans();
      } catch (RuntimeException e) {
        log.warn("Leases left by the last run weren't checked: {}", e.getMessage());
      }
    });
    scheduler.scheduleWithFixedDelay(this::maintain, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * The current login of {@code secretId}, issuing a lease when there is none or it runs out within a minute.
   *
   * @throws StoreException when Vault can't issue one
   */
  public Login login(String secretId, String storeId, SecretRef.Vault ref, StoreCall call) {
    ReentrantLock lock = locks.computeIfAbsent(secretId, k -> new ReentrantLock());
    lock.lock();
    try {
      Instant now = clock.instant();
      Lease current = active.get(secretId);
      if (current != null && (!current.storeId.equals(storeId) || !current.ref.equals(ref.uri()))) {
        retire(current, "its reference changed");
        current = null;
      }
      if (current != null && current.notAfter.minus(MARGIN).isAfter(now)) {
        current.lastUsed = now;
        renewIfDue(current, call);
        call.taint().accept(java.nio.CharBuffer.wrap(current.lease.password()));
        return login(current);
      }
      if (current != null) {
        // Nearly out (maximum TTL or a failed renewal): Vault drops the user when it ends; a new lease replaces it.
        retire(current, "it runs out");
      }
      VaultStore vault = vaults.vault(storeId);
      VaultStore.Lease issued = vault.issue(ref, call);
      if (issued.ttl().compareTo(MARGIN.multipliedBy(2)) <= 0) {
        log.warn("Vault issued a lease of {} s for {}: shorter than the gateway can use well", issued.ttl()
            .toSeconds(), ref.display());
      }
      Lease lease = new Lease(secretId, storeId, ref.uri(), issued, now);
      record(lease);
      active.put(secretId, lease);
      return login(lease);
    } finally {
      lock.unlock();
    }
  }

  private static Login login(Lease l) {
    return new Login(l.lease.username(), l.lease.password().clone(), l.version(), l.notAfter);
  }

  /** The lease's facts, if one is active. */
  public Optional<Active> active(String secretId) {
    Lease l = active.get(secretId);
    return l == null ? Optional.empty() : Optional.of(view(l));
  }

  public List<Active> all() {
    return active.values().stream().map(LeaseManager::view).toList();
  }

  private static Active view(Lease l) {
    return new Active(l.secretId, l.storeId, l.ref, l.issuedAt, l.notAfter, l.lease.ttl());
  }

  /** Revokes the lease of {@code secretId} (its connection was deleted, its reference replaced). */
  public void revoke(String secretId, String why) {
    Lease l = active.remove(secretId);
    if (l != null) {
      retire(l, why);
    }
  }

  /** Revokes every lease of a store (it is being disconnected); returns how many. */
  public int revokeStore(String storeId) {
    int n = 0;
    for (Lease l : List.copyOf(active.values())) {
      if (l.storeId.equals(storeId) && active.remove(l.secretId, l)) {
        retire(l, "its store was disconnected");
        n++;
      }
    }
    return n;
  }

  // ── Background ──────────────────────────────────────────────────────────────────────────────────────────────

  /** Renews leases in use, revokes idle ones. */
  void maintain() {
    Instant now = clock.instant();
    for (Lease l : List.copyOf(active.values())) {
      try {
        if (Duration.between(l.lastUsed, now).compareTo(IDLE) >= 0) {
          if (active.remove(l.secretId, l)) {
            retire(l, "nothing used it for " + IDLE.toMinutes() + " minutes");
          }
        } else if (!l.notAfter.isAfter(now)) {
          active.remove(l.secretId, l);
          forget(l);
        } else {
          renewIfDue(l, background());
        }
      } catch (RuntimeException e) {
        log.warn("Lease of {} not maintained: {}", l.ref, e.getMessage());
      }
    }
  }

  private void renewIfDue(Lease l, StoreCall call) {
    Instant now = clock.instant();
    Duration ttl = Duration.between(l.renewedAt, l.notAfter);
    if (!l.lease.renewable() || Duration.between(l.renewedAt, now).compareTo(ttl.multipliedBy(2).dividedBy(3)) < 0) {
      return;
    }
    try {
      Duration granted = vaults.vault(l.storeId).renew(l.lease.leaseId(), l.lease.ttl(), call);
      if (granted.isPositive()) {
        l.renewedAt = now;
        l.notAfter = now.plus(granted);
        writeTx.executeWithoutResult(s -> leases.findById(l.lease.leaseId()).ifPresent(row -> row.renewedUntil(
            l.notAfter)));
      }
    } catch (StoreException e) {
      // The lease keeps its old end; within a minute of it a new lease is taken.
      log.info("Lease of {} not renewed: {}", l.ref, e.getMessage());
    }
  }

  /** Leases left by a run that didn't stop cleanly: revoked when their store still answers, forgotten otherwise. */
  void revokeOrphans() {
    List<SecretLeaseEntity> open = writeTx.execute(s -> leases.findByRevokedAtIsNull());
    for (SecretLeaseEntity row : open == null ? List.<SecretLeaseEntity>of() : open) {
      String storeId = writeTx.execute(s -> leases.findById(row.getLeaseId()).map(r -> r.getStore().getId())
          .orElse(null));
      if (storeId == null) {
        continue;
      }
      boolean expired = !row.getExpiresAt().isAfter(clock.instant());
      if (!expired) {
        try {
          vaults.vault(storeId).revoke(row.getLeaseId(), background());
          log.info("Revoked a lease left by the last run");
        } catch (StoreException e) {
          log.warn("A lease left by the last run couldn't be revoked: {}", e.getMessage());
        }
      }
      writeTx.executeWithoutResult(s -> leases.findById(row.getLeaseId()).ifPresent(r -> r.revoked(clock.instant())));
    }
  }

  private void record(Lease l) {
    writeTx.executeWithoutResult(s -> {
      var store = stores.findById(l.storeId).orElse(null);
      var secret = secrets.findById(l.secretId).orElse(null);
      if (store != null && secret != null) {
        leases.save(new SecretLeaseEntity(l.lease.leaseId(), store, secret, l.issuedAt, l.notAfter));
      }
    });
  }

  /** Revokes at Vault (best effort) and marks the row; the password is zeroed. */
  private void retire(Lease l, String why) {
    try {
      vaults.vault(l.storeId).revoke(l.lease.leaseId(), background());
      log.info("Revoked the lease of {}: {}", l.ref, why);
    } catch (StoreException e) {
      log.warn("The lease of {} couldn't be revoked ({}); Vault ends it at its TTL", l.ref, e.getMessage());
    }
    forget(l);
  }

  private void forget(Lease l) {
    l.lease.clear();
    try {
      writeTx.executeWithoutResult(s -> leases.findById(l.lease.leaseId()).ifPresent(r -> {
        if (r.getRevokedAt() == null) {
          r.revoked(clock.instant());
        }
      }));
    } catch (RuntimeException e) {
      log.debug("Lease row not marked: {}", e.getMessage());
    }
  }

  private StoreCall background() {
    return StoreCall.within(v -> { }, Duration.ofSeconds(10), clock);
  }

  /** Stops the job and revokes every lease (the core is stopping). */
  @Override
  public void close() {
    scheduler.shutdownNow();
    List<Lease> all = new ArrayList<>(active.values());
    active.clear();
    for (Lease l : all) {
      retire(l, "MCP Gateway stopped");
    }
  }

  /** For tests: the password of the active lease, as handed out. */
  boolean holds(String secretId, char[] password) {
    Lease l = active.get(secretId);
    return l != null && Arrays.equals(l.lease.password(), password);
  }
}
