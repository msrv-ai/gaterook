package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretVersionChanged;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolving what a secret row points to, for a connection's {@link ai.msrv.gaterook.core.secrets.ScopedSecretResolver}
 * (report 12 §4.2, §7.6): a reference through the store it is bound to (with the short cache), Vault dynamic
 * credentials through the {@link LeaseManager}, an SSH certificate through a Vault store's CA. No transaction is held
 * while a store answers. A reference whose store isn't connected fails with the kit's words: "billing-api/webhook-secret
 * can't be resolved: AWS Secrets Manager isn't connected."
 */
public final class StoreResolution {

  private static final Logger log = LoggerFactory.getLogger(StoreResolution.class);

  /**
   * A secret row that points into a store, as read (and checked) in a short transaction.
   *
   * @param storeId  {@code null}: no store of its kind is connected
   * @param verified whether {@link StoreIntegrity} vouches for where it points
   */
  public record Pointer(String secretId, String name, SecretOwner owner, String field, boolean dynamic, String refUri,
      String storeId, String remoteVersion, boolean verified) {}

  /** One SSH certificate the gateway had signed: Secrets › Short-lived. */
  public record SignedFor(String ownerId, String storeId, String signer, String principal, Instant at, Duration ttl) {}

  private final StoreClients clients;
  private final SecretCache cache;
  private final LeaseManager leases;
  private final ExternalStoreRepository stores;
  private final SecretRepository secrets;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final Map<String, SignedFor> signed = new ConcurrentHashMap<>();

  public StoreResolution(StoreClients clients, SecretCache cache, LeaseManager leases, ExternalStoreRepository stores,
      SecretRepository secrets, ApplicationEventPublisher publisher, Clock clock, PlatformTransactionManager tx) {
    this.clients = clients;
    this.cache = cache;
    this.leases = leases;
    this.stores = stores;
    this.secrets = secrets;
    this.publisher = publisher;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(tx);
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  /** The kit's name of a store kind: "HashiCorp Vault", "GCP Secret Manager", "AWS Secrets Manager". */
  public static String title(StoreKind kind) {
    return switch (kind) {
      case VAULT -> "HashiCorp Vault";
      case GCP_SM -> "GCP Secret Manager";
      case AWS_SM -> "AWS Secrets Manager";
    };
  }

  /**
   * The value a reference names, for one use: from the cache when it is fresh enough for {@code env}, else from the
   * store. The caller owns (and zeroes) the result.
   *
   * @throws StoreException with the words the agent and the person see
   */
  public RemoteValue value(Pointer p, Env env, StoreCall call) {
    SecretRef ref = usable(p);
    SecretStore store = clients.client(p.storeId());
    if (store.kind() != ref.kind()) {
      throw StoreException.invalid(p.name() + " points to " + ref.uri() + ", which its store can't resolve.");
    }
    Duration ttl = store.cacheTtl();
    Duration maxAge = env == Env.PROD && ttl.compareTo(SecretCache.PROD_MAX) > 0 ? SecretCache.PROD_MAX : ttl;
    var cached = cache.get(p.storeId(), ref.uri(), maxAge);
    if (cached.isPresent()) {
      return cached.get();
    }
    RemoteValue fresh;
    try {
      fresh = store.read(ref, call);
    } catch (StoreException e) {
      cache.evict(p.storeId(), ref.uri());
      throw new StoreException(e.reason(), p.name() + " can't be resolved: " + e.getMessage(), e);
    }
    cache.put(p.storeId(), ref.uri(), fresh, ttl);
    noteVersion(p, fresh.version());
    return fresh;
  }

  /** Reads {@code ref} through store {@code storeId} now, bypassing the cache (Test reference). */
  public RemoteValue readFresh(String storeId, SecretRef ref, StoreCall call) {
    SecretStore store = clients.client(storeId);
    return store.read(ref, call);
  }

  /**
   * The current login of a dynamic secret ({@code vault://database/creds/<role>}).
   *
   * @throws StoreException when Vault can't issue it
   */
  public LeaseManager.Login login(Pointer p, StoreCall call) {
    SecretRef ref = usable(p);
    if (!(ref instanceof SecretRef.Vault v) || !v.credsPath()) {
      throw StoreException.invalid(p.name() + " isn't Vault dynamic credentials (vault://<mount>/creds/<role>).");
    }
    try {
      return leases.login(p.secretId(), p.storeId(), v, call);
    } catch (StoreException e) {
      throw new StoreException(e.reason(), p.name() + " can't be resolved: " + e.getMessage(), e);
    }
  }

  /** Credentials leased for one Test connection of a draft; revoked when the test ends. */
  public record DraftLease(String storeId, String leaseId, String username, char[] password, Instant notAfter) {
    @Override
    public String toString() {
      return "DraftLease[" + leaseId + ", password=‹secret›]";
    }
  }

  /** Leases credentials of {@code ref} for a draft's test: not shared, not kept, revoked by {@link #revokeDraft}. */
  public DraftLease issueDraft(String storeId, SecretRef.Vault ref, StoreCall call) {
    if (!(clients.client(storeId) instanceof VaultStore vault)) {
      throw StoreException.invalid("Only Vault issues short-lived credentials.");
    }
    VaultStore.Lease l = vault.issue(ref, call);
    return new DraftLease(storeId, l.leaseId(), l.username(), l.password(), clock.instant().plus(l.ttl()));
  }

  public void revokeDraft(DraftLease lease) {
    try {
      if (clients.client(lease.storeId()) instanceof VaultStore vault) {
        vault.revoke(lease.leaseId(), StoreCall.within(v -> { }, Duration.ofSeconds(5), clock));
      }
    } catch (StoreException e) {
      log.info("The test's lease couldn't be revoked ({}); Vault ends it at its TTL", e.getMessage());
    } finally {
      java.util.Arrays.fill(lease.password(), '\0');
    }
  }

  /**
   * Has Vault store {@code storeName} sign {@code key} for one call of {@code ownerId} (report 06: a fresh key pair
   * per call, no private key kept anywhere).
   */
  public VaultStore.Signed sign(String ownerId, String storeName, String mount, String role, PublicKey key,
      String principal, Duration ttl, String keyId, Map<String, String> extensions, StoreCall call) {
    String id = readTx.execute(s -> stores.findByName(storeName).filter(e -> e.getKind() == StoreKind.VAULT)
        .map(e -> e.getId()).orElse(null));
    if (id == null) {
      throw StoreException.notFound("There is no Vault store named " + storeName + ": connect it in Secrets › "
          + "External stores.");
    }
    if (!(clients.client(id) instanceof VaultStore vault)) {
      throw StoreException.invalid(storeName + " isn't a Vault store.");
    }
    VaultStore.Signed cert = vault.sign(mount, role, key, principal, ttl, keyId, extensions, call);
    signed.put(ownerId + "|" + id + "|" + mount + "/sign/" + role, new SignedFor(ownerId, id, mount + "/sign/" + role,
        principal, clock.instant(), ttl));
    return cert;
  }

  /** The SSH certificates signed since the core started, newest per connection and signer. */
  public List<SignedFor> signed() {
    return List.copyOf(signed.values());
  }

  private SecretRef usable(Pointer p) {
    if (!p.verified()) {
      throw StoreException.invalid(p.name() + " was changed outside GateRook: review its reference in Secrets.");
    }
    SecretRef ref;
    try {
      ref = SecretRef.parse(p.refUri());
    } catch (IllegalArgumentException e) {
      throw StoreException.invalid(p.name() + " has a reference that can't be read: " + e.getMessage());
    }
    if (p.storeId() == null) {
      throw StoreException.unavailable(p.name() + " can't be resolved: " + title(ref.kind()) + " isn't connected.",
          null);
    }
    return ref;
  }

  /**
   * A reference whose value changed in its store: the row learns the version (Secrets shows it), and the owner hears
   * of it like of a Replace, so pools keyed by the old value go.
   */
  private void noteVersion(Pointer p, String version) {
    if (version == null || Objects.equals(version, p.remoteVersion())) {
      return;
    }
    try {
      writeTx.executeWithoutResult(s -> secrets.findById(p.secretId()).ifPresent(row -> {
        String before = row.getRemoteVersion();
        if (Objects.equals(before, version)) {
          return;
        }
        row.remoteChecked(version, clock.instant());
        if (before != null) {
          publisher.publishEvent(new SecretVersionChanged(row.getId(), p.owner(), p.field(), null));
        }
      }));
    } catch (RuntimeException e) {
      // The value was read; the version shows up at the next read.
      log.debug("Remote version of {} not recorded: {}", p.name(), e.getMessage());
    }
  }
}
