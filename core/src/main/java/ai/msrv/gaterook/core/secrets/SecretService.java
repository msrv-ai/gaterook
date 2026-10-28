package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.platform.WalCheckpoint;
import ai.msrv.gaterook.core.secrets.internal.crypto.Envelope;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretOwnerKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionState;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import ai.msrv.gaterook.core.secrets.store.LeaseManager;
import ai.msrv.gaterook.core.secrets.store.RemoteValue;
import ai.msrv.gaterook.core.secrets.store.SecretCache;
import ai.msrv.gaterook.core.secrets.store.SecretRef;
import ai.msrv.gaterook.core.secrets.store.StoreBinding;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import ai.msrv.gaterook.core.secrets.store.StoreResolution;
import java.security.PublicKey;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Local secrets (ARCHITECTURE.md A14, §6 {@code /api/secrets}; report 12): values encrypted here, one data key per
 * version, write-only for everybody. Replace adds a version; the previous one stays 7 days for a roll back and is then
 * crypto-shredded (its wrapped data key nulled, the WAL checkpointed); older versions never survive a replace.
 *
 * <p>Values come in as {@code char[]} and are zeroed here; they leave only through a {@link ScopedSecretResolver} of
 * one owner and one call, never through an API response.
 *
 * <p>A secret can also point into an external store (step S1, report 12 §4.2): a reference ({@code vault://…},
 * {@code gcp-sm://…}, {@code aws-sm://…}) read at call time through {@code secrets.store}, or Vault dynamic credentials
 * ({@code vault://database/creds/<role>}) leased per connection. Only a person sets either, in the window.
 */
@Service
public class SecretService {

  /** How long the previous version of a replaced secret stays restorable. */
  public static final Duration PREVIOUS_KEPT = Duration.ofDays(7);
  private static final SecureRandom VALUES = new SecureRandom();

  private static final Logger log = LoggerFactory.getLogger(SecretService.class);

  private final SecretRepository secrets;
  private final SecretVersionRepository versions;
  private final MasterKeyService masterKey;
  private final Map<SecretOwner.Kind, SecretOwners> owners;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final WalCheckpoint checkpoint;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final Clock clock;
  private final StoreBinding binding;
  private final StoreResolution resolution;
  private final SecretCache cache;
  private final LeaseManager leases;

  /** The outcome of Replace. */
  public record ReplaceResult(String id, int version, String state, Boolean tested, String testMessage) {}

  /** Thrown for an id that doesn't exist (404). */
  public static final class NoSuchSecretException extends NoSuchElementException {
    private static final long serialVersionUID = 1L;

    NoSuchSecretException(String id) {
      super("No secret " + id);
    }
  }

  /** A replace the rules don't allow (422): a missing comment on prod, an empty value, a reference. */
  public static final class SecretRuleException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String field;

    SecretRuleException(String field, String message) {
      super(message);
      this.field = field;
    }

    /** The JSON pointer of the request field at fault. */
    public String field() {
      return field;
    }
  }

  public SecretService(SecretRepository secrets, SecretVersionRepository versions, MasterKeyService masterKey,
      List<SecretOwners> owners, ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher,
      WalCheckpoint checkpoint, PlatformTransactionManager transactions, Clock clock, StoreBinding binding,
      StoreResolution resolution, SecretCache cache, LeaseManager leases) {
    this.secrets = secrets;
    this.versions = versions;
    this.masterKey = masterKey;
    Map<SecretOwner.Kind, SecretOwners> byKind = new EnumMap<>(SecretOwner.Kind.class);
    owners.forEach(o -> byKind.put(o.kind(), o));
    this.owners = Map.copyOf(byKind);
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.checkpoint = checkpoint;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
    this.clock = clock;
    this.binding = binding;
    this.resolution = resolution;
    this.cache = cache;
    this.leases = leases;
  }

  // ── Listing ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The rows of Secrets: values stored here and references. Vault dynamic credentials aren't secrets of the table
   * (BLUEPRINT §9: Secrets › Short-lived lists them).
   */
  public List<SecretInfo> list() {
    return readTx.execute(s -> secrets.findAllByOrderByNameAsc().stream().filter(e -> e.getKind() != SecretKind.DYNAMIC)
        .map(this::info).toList());
  }

  /**
   * One local secret the master key protects: whose it is ({@code owner}) and its name ({@code github-mcp/token},
   * whose part before the slash names the owner).
   */
  public record Protected(SecretOwner owner, String name) {}

  /**
   * What the master key protects and what doesn't need it, for the locked screen ("The 8 secrets encrypted here become
   * unreadable (6 connections, the github-mcp server token and the Vault sign-in) … The 3 secret references to Vault,
   * GCP and AWS keep working"): the local secrets with a value, and the source of each reference ({@code vault},
   * {@code gcp-sm}, {@code aws-sm}; Vault dynamic credentials are short-lived, not references).
   */
  public record Protection(List<Protected> local, List<String> referenceSources) {}

  /** {@link Protection}: rows only, no key and no MAC, so it answers while the core is locked. */
  public Protection protection() {
    return readTx.execute(s -> {
      List<Protected> local = secrets.findByKindAndCurrentVersionIsNotNull(SecretKind.LOCAL).stream()
          .sorted(java.util.Comparator.comparing(SecretEntity::getName))
          .map(e -> new Protected(owner(e), e.getName())).toList();
      List<String> references = secrets.findAllByOrderByNameAsc().stream()
          .filter(e -> e.getKind() == SecretKind.REFERENCE).map(SecretService::referenceSource).toList();
      return new Protection(local, references);
    });
  }

  /** The secrets of one owner (its connection's rows on Secrets, the secret source column of Connections). */
  public List<SecretInfo> listForOwner(SecretOwner owner) {
    return readTx.execute(s -> secrets.findByOwnerKindAndOwnerId(kind(owner), owner.id()).stream()
        .sorted(java.util.Comparator.comparing(SecretEntity::getName)).map(this::info).toList());
  }

  public SecretInfo get(String id) {
    return readTx.execute(s -> secrets.findById(id).map(this::info).orElseThrow(() -> new NoSuchSecretException(id)));
  }

  // ── Writing ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Stores a new local secret for {@code owner}'s {@code field}, version 1 ({@code POST /api/connections} with a
   * value). The owner records its own Setup change.
   *
   * @param value zeroed before this returns
   */
  public SecretInfo createLocal(SecretOwner owner, String field, String name, char[] value) {
    try {
      requireValue(value);
      return masterKey.sealing(() -> writeTx.execute(s -> {
        SecretEntity secret = secrets.save(new SecretEntity(name, SecretKind.LOCAL, kind(owner), owner.id(), field,
            clock.instant()));
        addVersion(secret, 1, SecretVersionState.CURRENT, value);
        secret.setCurrentVersion(1);
        return info(secret);
      }));
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  /**
   * Stores a reference for {@code owner}'s {@code field}: where the value lives, never the value
   * ({@code POST /api/connections} with {@code {source: "reference", ref}} or {@code "vault-dynamic"}). It is bound
   * to the connected store of its kind ({@link StoreBinding}), or to none yet: then it shows "not connected" and
   * calls fail until a store is connected. Nothing is read from the store here. Joins the owner's transaction.
   *
   * @param dynamic   Vault dynamic credentials ({@code vault://<mount>/creds/<role>}) rather than a stored value
   * @param storeName the store the person chose, or {@code null}
   * @param pointer   JSON pointer of the request field, for a 422
   */
  public SecretInfo createReference(SecretOwner owner, String field, String name, String uri, boolean dynamic,
      String storeName, String pointer) {
    SecretRef ref = StoreBinding.parse(uri, pointer);
    boolean creds = ref instanceof SecretRef.Vault v && v.credsPath();
    if (dynamic && !creds) {
      throw ai.msrv.gaterook.core.platform.ProblemException.unprocessable("Not dynamic credentials", "Short-lived "
          + "credentials come from vault://<mount>/creds/<role>, like vault://database/creds/prod-replica-readonly.",
          pointer);
    }
    if (!dynamic && creds) {
      throw ai.msrv.gaterook.core.platform.ProblemException.unprocessable("Invalid secret reference", ref.uri()
          + " cannot be used as a secret reference. Use a Vault KV path and key, such as "
          + "vault://kv/dev/db#password.", pointer);
    }
    return writeTx.execute(s -> {
      ExternalStoreEntity store = binding.storeFor(ref, storeName, pointer);
      SecretEntity secret = new SecretEntity(name, dynamic ? SecretKind.DYNAMIC : SecretKind.REFERENCE, kind(owner),
          owner.id(), field, clock.instant());
      binding.point(secret, dynamic, ref, store);
      return info(secrets.save(secret));
    });
  }

  /**
   * Replace: a new version N+1 from {@code value}. With {@code testBefore} and an owner that can test, the new version
   * is tried first and becomes current only if the test passes ("Version 3 stays in use until then"); without one it
   * becomes current at once ({@code tested = null}).
   *
   * @param value  zeroed before this returns
   * @param reason the person's comment; required when the owner is on prod
   */
  public ReplaceResult replace(String id, char[] value, boolean testBefore, String reason) {
    try {
      requireValue(value);
      SecretEntity secret = readTx.execute(s -> secrets.findById(id).orElseThrow(() -> new NoSuchSecretException(id)));
      if (secret.getKind() != SecretKind.LOCAL) {
        throw new SecretRuleException("/value", secret.getName() + " is a reference: replace the reference instead");
      }
      SecretOwner owner = owner(secret);
      Optional<SecretOwners.Owner> described = describe(owner);
      if (described.map(SecretOwners.Owner::env).orElse(null) == Env.PROD && (reason == null || reason.isBlank())) {
        throw new SecretRuleException("/reason", "Add a comment: it is required to change a secret on prod");
      }
      SecretOwners support = owners.get(owner.kind());
      boolean canTest = testBefore && support != null;
      int next = masterKey.sealing(() -> writeTx.execute(s -> {
        SecretEntity locked = secrets.findById(id).orElseThrow(() -> new NoSuchSecretException(id));
        int number = nextVersion(locked);
        addVersion(locked, number, canTest ? SecretVersionState.PENDING : SecretVersionState.CURRENT, value);
        if (!canTest) {
          promote(locked, number, reason, described, "replaced");
        }
        return number;
      }));
      if (!canTest) {
        afterChange();
        return new ReplaceResult(id, next, "stored", null, null);
      }
      Optional<SecretOwners.TestOutcome> outcome = support.testWithVersion(owner.id(), secret.getField(), next);
      if (outcome.isEmpty() || outcome.get().ok()) {
        writeTx.executeWithoutResult(s -> promote(secrets.findById(id).orElseThrow(), next, reason, described,
            "replaced"));
        afterChange();
        return new ReplaceResult(id, next, "stored", outcome.isPresent() ? Boolean.TRUE : null,
            outcome.map(SecretOwners.TestOutcome::message).orElse(null));
      }
      // The test failed: the new version stays pending, the current one stays in use.
      return new ReplaceResult(id, next, "testing", Boolean.FALSE, outcome.get().message());
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  /**
   * Rotate at the source ({@code POST /api/secrets/{id}/rotate}): the gateway makes a new value, stores it as pending
   * version N+1, has the owner's connector make it the value at the source, and only then makes it current — so the
   * value the source accepts is always one the gateway holds. Nobody sees the new value, the person included. When the
   * source refuses, the pending version is destroyed and version N stays in use.
   *
   * @param reason the person's comment; required when the owner is on prod
   * @throws ProblemException 409 when the secret can't be rotated or the source refused
   */
  public ReplaceResult rotate(String id, String reason) {
    SecretEntity secret = readTx.execute(s -> secrets.findById(id).orElseThrow(() -> new NoSuchSecretException(id)));
    SecretOwner owner = owner(secret);
    SecretOwners support = owners.get(owner.kind());
    Optional<String> unavailable = secret.getKind() != SecretKind.LOCAL ? Optional.of("References rotate in their "
        + "store.") : support == null ? Optional.of("This connector can't issue a new value for itself. Replace it "
        + "with a new value.") : support.rotateUnavailable(owner.id(), secret.getField());
    if (unavailable.isPresent()) {
      throw ProblemException.conflict("Can't rotate " + secret.getName(), unavailable.get());
    }
    Optional<SecretOwners.Owner> described = describe(owner);
    if (described.map(SecretOwners.Owner::env).orElse(null) == Env.PROD && (reason == null || reason.isBlank())) {
      throw new SecretRuleException("/reason", "Add a comment: it is required to change a secret on prod");
    }
    if (secret.getCurrentVersion() == null) {
      throw ProblemException.conflict("Can't rotate " + secret.getName(), "It has no value yet: the connector signs "
          + "in with the current value to set a new one. Replace it with a value first.");
    }
    char[] next = newValue();
    try {
      int number = masterKey.sealing(() -> writeTx.execute(s -> {
        SecretEntity locked = secrets.findById(id).orElseThrow(() -> new NoSuchSecretException(id));
        if (versions.findFirstBySecretIdAndState(id, SecretVersionState.PENDING).isPresent()) {
          throw ProblemException.conflict("Can't rotate " + secret.getName(), "A new version is being tested. Wait "
              + "for its test, or replace the value.");
        }
        int n = nextVersion(locked);
        addVersion(locked, n, SecretVersionState.PENDING, next);
        return n;
      }));
      try {
        support.rotate(owner.id(), secret.getField(), next);
      } catch (RuntimeException e) {
        // The source kept version N: the value it never accepted goes.
        writeTx.executeWithoutResult(s -> versions.findBySecretIdOrderBySecretVersionDesc(id).stream()
            .filter(v -> v.getSecretVersion() == number).forEach(v -> v.destroy(clock.instant())));
        throw e;
      }
      writeTx.executeWithoutResult(s -> promote(secrets.findById(id).orElseThrow(), number, reason, described,
          "rotated at the source"));
      afterChange();
      return new ReplaceResult(id, number, "stored", null, null);
    } finally {
      Arrays.fill(next, '\0');
    }
  }

  /** 32 letters and digits from {@code SecureRandom}: about 190 bits, nothing a source's quoting could trip on. */
  private static char[] newValue() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    char[] value = new char[32];
    for (int i = 0; i < value.length; i++) {
      value[i] = alphabet.charAt(VALUES.nextInt(alphabet.length()));
    }
    return value;
  }

  /**
   * Destroys every secret of {@code owner} (its connection was deleted): versions crypto-shredded, rows gone. Call in
   * the owner's transaction.
   *
   * @return the names destroyed, for the deletion summary
   */
  public List<String> deleteForOwner(SecretOwner owner) {
    return destroy(secrets.findByOwnerKindAndOwnerId(kind(owner), owner.id()));
  }

  /**
   * Destroys the secrets of {@code owner} stored for {@code fields} only (a field its new config no longer has). Call
   * in the owner's transaction.
   */
  public List<String> deleteForOwnerFields(SecretOwner owner, Collection<String> fields) {
    return destroy(secrets.findByOwnerKindAndOwnerId(kind(owner), owner.id()).stream()
        .filter(e -> fields.contains(e.getField())).toList());
  }

  /**
   * Shreds the versions first (the UPDATE overwrites ciphertext and data key in place), then deletes the versions
   * and the secret. The versions go explicitly although the schema cascades: a version still managed in this session
   * would otherwise point at a removed secret when the transaction flushes.
   */
  private List<String> destroy(List<SecretEntity> owned) {
    Instant now = clock.instant();
    for (SecretEntity secret : owned) {
      if (secret.getKind() != SecretKind.LOCAL) {
        binding.integrity().forgetReference(secret.getId());
        String id = secret.getId();
        String storeId = secret.getStore() == null ? null : secret.getStore().getId();
        String uri = secret.getRefUri();
        // After the commit: the lease ends at Vault (its row goes with the secret), the cached value is zeroed.
        afterCommit(() -> {
          leases.revoke(id, "its secret was deleted");
          if (storeId != null && uri != null) {
            cache.evict(storeId, uri);
          }
        });
      }
      List<SecretVersionEntity> all = versions.findBySecretIdOrderBySecretVersionDesc(secret.getId());
      all.forEach(v -> v.destroy(now));
      versions.flush();
      versions.deleteAll(all);
      secrets.delete(secret);
    }
    return owned.stream().map(SecretEntity::getName).sorted().toList();
  }

  /**
   * Crypto-shreds previous versions whose 7 days are over; run after each replace and by the secrets module's hourly
   * job (from start), so a core left running from the tray doesn't keep them.
   */
  public int shredExpiredPrevious() {
    Instant cutoff = clock.instant().minus(PREVIOUS_KEPT);
    Integer shredded = writeTx.execute(s -> {
      List<SecretVersionEntity> expired = versions.findByStateAndReplacedBefore(SecretVersionState.PREVIOUS, cutoff);
      Instant now = clock.instant();
      expired.forEach(v -> v.destroy(now));
      return expired.size();
    });
    if (shredded != null && shredded > 0) {
      checkpoint.truncate();
      log.info("{} previous secret version(s) destroyed after {} days", shredded, PREVIOUS_KEPT.toDays());
    }
    return shredded == null ? 0 : shredded;
  }

  // ── Resolving ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A resolver for one call of {@code owner}: only {@code fields} (its writeOnly fields), every value handed out added
   * to {@code taint}. The caller closes it when the call ends.
   */
  public ScopedSecretResolver resolver(SecretOwner owner, Collection<String> fields, Taint taint) {
    return new ScopedSecretResolver(this, owner, Set.copyOf(fields), taint, ScopedSecretResolver.Scope.NONE);
  }

  /**
   * A resolver for one call of connection {@code cfg}: its secret fields; its environment caps how long a reference
   * stays cached (prod: 15 s), its config names the Vault that signs SSH certificates.
   */
  public ScopedSecretResolver resolver(ConnectionConfig cfg, Taint taint) {
    return new ScopedSecretResolver(this, SecretOwner.connection(cfg.id()), cfg.secretFields(), taint,
        new ScopedSecretResolver.Scope(Map.of(), Map.of(), cfg.config(), cfg.env()));
  }

  /** The same, with {@code field} at {@code version} instead of the current one (test before Replace or Rotate). */
  public ScopedSecretResolver resolverWithVersion(SecretOwner owner, Collection<String> fields, Taint taint,
      String field, int version) {
    return new ScopedSecretResolver(this, owner, Set.copyOf(fields), taint, new ScopedSecretResolver.Scope(
        Map.of(field, version), Map.of(), null, null));
  }

  /** {@link #resolverWithVersion} for a connection. */
  public ScopedSecretResolver resolverWithVersion(ConnectionConfig cfg, Taint taint, String field, int version) {
    return new ScopedSecretResolver(this, SecretOwner.connection(cfg.id()), cfg.secretFields(), taint,
        new ScopedSecretResolver.Scope(Map.of(field, version), Map.of(), cfg.config(), cfg.env()));
  }

  /**
   * The resolver of a Test connection draft: {@code references} typed into the form are read for this one test (a
   * dynamic one leases credentials that are revoked when the resolver closes); every other field is the stored one
   * of {@code cfg.id()}, if that connection exists.
   */
  public ScopedSecretResolver draftResolver(ConnectionConfig cfg, Map<String, DraftReference> references,
      Taint taint) {
    return new ScopedSecretResolver(this, SecretOwner.connection(cfg.id()), cfg.secretFields(), taint,
        new ScopedSecretResolver.Scope(Map.of(), Map.copyOf(references), cfg.config(), cfg.env()));
  }

  /** A reference typed into a draft: not stored. */
  public record DraftReference(String uri, boolean dynamic, String store) {}

  /** What a resolver gets for one field: the plaintext of a local version or a reference's value, or why none. */
  record Resolved(SecretKind kind, String name, String version, byte[] plaintext, String unavailable) {}

  Resolved resolve(SecretOwner owner, String field, Integer versionOverride, Env env, Taint taint) {
    Object found = readTx.execute(s -> {
      SecretEntity secret = secrets.findByOwnerKindAndOwnerIdAndField(kind(owner), owner.id(), field).orElse(null);
      if (secret == null) {
        return new Resolved(null, field, null, null, field + " is not set");
      }
      if (secret.getKind() != SecretKind.LOCAL) {
        // Read in this short transaction; the store answers after it, with no connection held.
        return binding.pointer(secret);
      }
      Integer number = versionOverride != null ? versionOverride : secret.getCurrentVersion();
      if (number == null) {
        return new Resolved(SecretKind.LOCAL, secret.getName(), null, null, secret.getName()
            + " needs a value: enter it again under Secrets.");
      }
      if (masterKey.locked()) {
        return new Resolved(SecretKind.LOCAL, secret.getName(), "v" + number, null, masterKey.lockedMessage());
      }
      SecretVersionEntity version = versions.findBySecretIdAndSecretVersion(secret.getId(), number).orElse(null);
      if (version == null || version.getWrappedDek() == null) {
        return new Resolved(SecretKind.LOCAL, secret.getName(), "v" + number, null, secret.getName() + " version "
            + number + " was destroyed");
      }
      try {
        byte[] plain = masterKey.open(secret.getId(), number, version.getCiphertext(), version.getWrappedDek());
        return new Resolved(SecretKind.LOCAL, secret.getName(), "v" + number, plain, null);
      } catch (GeneralSecurityException e) {
        log.warn("Secret {} version {} doesn't decrypt: {}", secret.getName(), number, e.getMessage());
        return new Resolved(SecretKind.LOCAL, secret.getName(), "v" + number, null, secret.getName()
            + " can't be decrypted: replace it with a new value.");
      }
    });
    if (found instanceof Resolved r) {
      return r;
    }
    StoreResolution.Pointer p = (StoreResolution.Pointer) found;
    if (p.dynamic()) {
      return new Resolved(SecretKind.DYNAMIC, p.name(), null, null, p.name() + " is short-lived Vault credentials: "
          + "the connector reads it as a login.");
    }
    try {
      RemoteValue v = resolution.value(p, env, call(taint));
      return new Resolved(SecretKind.REFERENCE, p.name(), label(v.version()), v.value(), null);
    } catch (StoreException e) {
      return new Resolved(SecretKind.REFERENCE, p.name(), null, null, e.getMessage());
    }
  }

  /** A draft's reference, read now through the store it binds to. */
  Resolved resolveDraft(String field, DraftReference d, Taint taint) {
    try {
      SecretRef ref = StoreBinding.parse(d.uri(), "/secrets");
      String storeId = readTx.execute(s -> {
        ExternalStoreEntity store = binding.storeFor(ref, d.store(), "/secrets");
        return store == null ? null : store.getId();
      });
      if (storeId == null) {
        return new Resolved(SecretKind.REFERENCE, field, null, null, field + " can't be resolved: "
            + StoreResolution.title(ref.kind()) + " isn't connected.");
      }
      RemoteValue v = resolution.readFresh(storeId, ref, call(taint));
      return new Resolved(SecretKind.REFERENCE, field, label(v.version()), v.value(), null);
    } catch (StoreException | ai.msrv.gaterook.core.platform.ProblemException e) {
      return new Resolved(SecretKind.REFERENCE, field, null, null, field + " can't be resolved: " + e.getMessage());
    }
  }

  /** The login of a dynamic secret (its lease is the core's), or why there is none. */
  LeaseManager.Login dynamicLogin(SecretOwner owner, String field, Taint taint)
      throws ai.msrv.gaterook.connector.api.SecretUnavailableException {
    Object found = readTx.execute(s -> secrets.findByOwnerKindAndOwnerIdAndField(kind(owner), owner.id(), field)
        .map(e -> e.getKind() == SecretKind.DYNAMIC ? (Object) binding.pointer(e) : e.getName()).orElse(null));
    if (!(found instanceof StoreResolution.Pointer p)) {
      throw new ai.msrv.gaterook.connector.api.SecretUnavailableException(found == null ? field + " is not set"
          : found + " isn't short-lived credentials", null);
    }
    try {
      LeaseManager.Login login = resolution.login(p, call(taint));
      taint.addLogin(login.username(), java.nio.CharBuffer.wrap(login.password()));
      return login;
    } catch (StoreException e) {
      throw new ai.msrv.gaterook.connector.api.SecretUnavailableException(e.getMessage(), null);
    }
  }

  /** Credentials of a draft's dynamic reference for one test; revoked by the caller when the test ends. */
  StoreResolution.DraftLease draftLogin(String field, DraftReference d, Taint taint)
      throws ai.msrv.gaterook.connector.api.SecretUnavailableException {
    try {
      SecretRef ref = StoreBinding.parse(d.uri(), "/secrets");
      if (!(ref instanceof SecretRef.Vault v) || !v.credsPath()) {
        throw new ai.msrv.gaterook.connector.api.SecretUnavailableException(field + " isn't Vault dynamic credentials "
            + "(vault://<mount>/creds/<role>)", null);
      }
      String storeId = readTx.execute(s -> {
        ExternalStoreEntity store = binding.storeFor(ref, d.store(), "/secrets");
        return store == null ? null : store.getId();
      });
      if (storeId == null) {
        throw new ai.msrv.gaterook.connector.api.SecretUnavailableException(field + " can't be resolved: HashiCorp "
            + "Vault isn't connected.", null);
      }
      StoreResolution.DraftLease lease = resolution.issueDraft(storeId, v, call(taint));
      taint.addLogin(lease.username(), java.nio.CharBuffer.wrap(lease.password()));
      return lease;
    } catch (StoreException | ai.msrv.gaterook.core.platform.ProblemException e) {
      throw new ai.msrv.gaterook.connector.api.SecretUnavailableException(field + " can't be resolved: "
          + e.getMessage(), null);
    }
  }

  void revokeDraft(StoreResolution.DraftLease lease) {
    resolution.revokeDraft(lease);
  }

  /**
   * An SSH certificate for {@code key}, signed by the Vault the connection's config names at {@code field}
   * ({@code {store, mount, role, ttl, principal}}, report 06).
   */
  SecretResolver.SshCertificate sshCertificate(SecretOwner owner, JsonNode at, PublicKey key,
      SecretResolver.SshCertRequest request, Taint taint) throws ai.msrv.gaterook.connector.api.SecretUnavailableException {
    if (at == null || !at.isObject() || !at.path("store").isString() || at.path("store").asString().isBlank()) {
      throw new ai.msrv.gaterook.connector.api.SecretUnavailableException("Choose the Vault that signs the "
          + "certificates.", null);
    }
    String store = at.path("store").asString();
    String mount = at.path("mount").asString("ssh-client-signer");
    String role = at.path("role").asString("gw-runbooks");
    java.time.Duration ttl = request.ttl() == null ? java.time.Duration.ofMinutes(5) : request.ttl();
    if (ttl.compareTo(java.time.Duration.ofMinutes(30)) > 0 || ttl.isNegative() || ttl.isZero()) {
      throw new ai.msrv.gaterook.connector.api.SecretUnavailableException("An SSH certificate lives 1 to 30 minutes.",
          null);
    }
    try {
      var signed = resolution.sign(owner.id(), store, mount, role, key, request.principal(), ttl, request.keyId(),
          request.extensions(), call(taint));
      return new SecretResolver.SshCertificate(signed.openSsh(), signed.serial(), signed.notAfter());
    } catch (StoreException e) {
      throw new ai.msrv.gaterook.connector.api.SecretUnavailableException("No SSH certificate from " + store + ": "
          + e.getMessage(), null);
    }
  }

  /** {@code isSet} and {@code meta} of a resolver: no value read. */
  record Described(SecretKind kind, Integer version, String remoteVersion, Instant notAfter) {}

  Described describeField(SecretOwner owner, String field) {
    Described d = readTx.execute(s -> secrets.findByOwnerKindAndOwnerIdAndField(kind(owner), owner.id(), field)
        .map(e -> new Described(e.getKind(), e.getCurrentVersion(), e.getKind() == SecretKind.DYNAMIC
            ? leases.active(e.getId()).map(a -> "lease").orElse(null) : e.getRemoteVersion(),
            e.getKind() == SecretKind.DYNAMIC ? leases.active(e.getId()).map(LeaseManager.Active::notAfter)
                .orElse(null) : null))
        .orElse(new Described(null, null, null, null)));
    return d;
  }

  private StoreCall call(Taint taint) {
    return StoreCall.within(taint::add, StoreCall.MAX_REQUEST, clock);
  }

  /** {@code 7} → {@code v7}; an AWS version id stays as it is. */
  private static String label(String remote) {
    if (remote == null) {
      return "remote";
    }
    return remote.chars().allMatch(Character::isDigit) ? "v" + remote : remote;
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          action.run();
        }
      });
    } else {
      action.run();
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private SecretInfo info(SecretEntity secret) {
    SecretOwner owner = owner(secret);
    Optional<SecretOwners.Owner> described = describe(owner);
    SecretOwners support = owners.get(owner.kind());
    String rotateReason;
    if (secret.getKind() != SecretKind.LOCAL) {
      rotateReason = "References rotate in their store.";
    } else if (support == null) {
      rotateReason = "This connector can't issue a new value for itself. Replace it with a new value.";
    } else {
      rotateReason = support.rotateUnavailable(owner.id(), secret.getField()).orElse(null);
    }
    String source = switch (secret.getKind()) {
      case LOCAL -> "local";
      case REFERENCE, DYNAMIC -> referenceSource(secret);
    };
    String state;
    if (secret.getKind() != SecretKind.LOCAL) {
      // "AWS SM · not connected" (BLUEPRINT §9): a reference whose kind of store isn't connected.
      state = !binding.integrity().verifyReference(secret) ? "changed-outside" : secret.getStore() == null
          ? "not-connected" : secret.getKind() == SecretKind.DYNAMIC ? "short-lived" : "reference";
    } else if (secret.getCurrentVersion() == null) {
      state = "needs-value";
    } else if (versions.findFirstBySecretIdAndState(secret.getId(), SecretVersionState.PENDING).isPresent()) {
      state = "testing";
    } else {
      state = "stored";
    }
    return new SecretInfo(secret.getId(), secret.getName(), owner, secret.getField(),
        described.map(SecretOwners.Owner::label).orElse(null),
        described.map(SecretOwners.Owner::usedBy).orElse(List.of()), source, secret.getCurrentVersion(),
        secret.getRemoteVersion(), secret.getRotatedAt(), state, rotateReason == null, rotateReason,
        secret.getKind() == SecretKind.LOCAL ? null : secret.getRefUri(),
        secret.getStore() == null ? null : secret.getStore().getName());
  }

  /** {@code vault}, {@code gcp-sm}, {@code aws-sm} by the reference's scheme, connected or not. */
  private static String referenceSource(SecretEntity secret) {
    if (secret.getStore() != null) {
      return secret.getStore().getKind().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
    try {
      return SecretRef.parse(secret.getRefUri()).kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    } catch (IllegalArgumentException | NullPointerException e) {
      return "reference";
    }
  }

  private void addVersion(SecretEntity secret, int number, SecretVersionState state, char[] value) {
    byte[] plain = utf8(value);
    try {
      Envelope.Sealed sealed = masterKey.seal(secret.getId(), number, plain);
      SecretVersionEntity version = new SecretVersionEntity(secret, number, state, Envelope.FORMAT, clock.instant());
      version.seal(sealed.ciphertext(), sealed.wrappedDek(), sealed.keyId());
      versions.save(version);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  /** {@code number} becomes current; the old current becomes previous, an older previous is destroyed. */
  private void promote(SecretEntity secret, int number, String reason, Optional<SecretOwners.Owner> described,
      String how) {
    Instant now = clock.instant();
    Integer before = secret.getCurrentVersion();
    for (SecretVersionEntity v : versions.findBySecretIdOrderBySecretVersionDesc(secret.getId())) {
      if (v.getSecretVersion() == number) {
        v.setState(SecretVersionState.CURRENT);
      } else if (before != null && v.getSecretVersion() == before) {
        v.setState(SecretVersionState.PREVIOUS);
      } else if (v.getState() == SecretVersionState.PREVIOUS || v.getState() == SecretVersionState.PENDING
          || v.getState() == SecretVersionState.CURRENT) {
        v.destroy(now);
      }
    }
    secret.setCurrentVersion(number);
    secret.setRotatedAt(now);
    SetupChangeRecorder recorder = setupChanges.getIfAvailable();
    if (recorder == null) {
      throw new IllegalStateException("Setup changes can't be recorded: no recorder");
    }
    String label = described.map(SecretOwners.Owner::label).orElse(null);
    recorder.record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET, secret.getName(),
        before == null ? "needs a value" : "v" + before, "v" + number + " (" + how + ")",
        reason == null || reason.isBlank() ? null : reason, described.map(SecretOwners.Owner::env).orElse(null),
        null, secret.getOwnerKind() == SecretOwnerKind.CONNECTION ? label : null, null));
    // In this transaction: AFTER_COMMIT listeners (pools, ConnectionConfig.version) hear of it once it commits.
    publisher.publishEvent(new SecretVersionChanged(secret.getId(), owner(secret), secret.getField(), number));
  }

  /** After a committed Replace: what the promotion left behind is cleaned up; failures don't undo the Replace. */
  private void afterChange() {
    try {
      shredExpiredPrevious();
    } catch (RuntimeException e) {
      // The replace has committed; the hourly job shreds them instead.
      log.debug("Previous secret versions not cleaned up: {}", e.getMessage());
    }
    checkpoint.truncate();
  }

  private int nextVersion(SecretEntity secret) {
    return versions.findBySecretIdOrderBySecretVersionDesc(secret.getId()).stream()
        .mapToInt(SecretVersionEntity::getSecretVersion).max().orElse(0) + 1;
  }

  private Optional<SecretOwners.Owner> describe(SecretOwner owner) {
    SecretOwners support = owners.get(owner.kind());
    return support == null ? Optional.empty() : support.describe(owner.id());
  }

  private static void requireValue(char[] value) {
    if (value == null || value.length == 0) {
      throw new SecretRuleException("/value", "Enter a value");
    }
    if (value.length > 64 * 1024) {
      throw new SecretRuleException("/value", "A secret can have at most 64 KB");
    }
  }

  private static SecretOwner owner(SecretEntity secret) {
    return new SecretOwner(SecretOwner.Kind.valueOf(secret.getOwnerKind().name()), secret.getOwnerId());
  }

  private static SecretOwnerKind kind(SecretOwner owner) {
    return SecretOwnerKind.valueOf(owner.kind().name());
  }

  private static byte[] utf8(char[] value) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
    byte[] out = new byte[encoded.remaining()];
    encoded.get(out);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    return out;
  }
}
