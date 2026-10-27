package ai.msrv.gaterook.core.secrets;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.subtle.Hkdf;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.WalCheckpoint;
import ai.msrv.gaterook.core.secrets.internal.KeysetLoader;
import ai.msrv.gaterook.core.secrets.internal.crypto.BackupCodec;
import ai.msrv.gaterook.core.secrets.internal.crypto.Envelope;
import ai.msrv.gaterook.core.secrets.internal.crypto.KeysetFile;
import ai.msrv.gaterook.core.secrets.internal.crypto.Keysets;
import ai.msrv.gaterook.core.secrets.internal.crypto.SyncedFolders;
import ai.msrv.gaterook.core.secrets.internal.persistence.MasterKeyStateEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.MasterKeyStateRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The master key of the gateway (ARCHITECTURE.md A14, §7.1; report 12 §7.3): loads or creates it at start, runs the
 * core locked when it doesn't open the stored secrets, rotates it without downtime, backs it up and restores it, and
 * derives the purpose keys of {@link HmacKeys} from it.
 *
 * <ul>
 *   <li><b>Start.</b> {@code KeysetLoader} read the file before the migrations; here, with the database: a keyset
 *       that holds every key id the stored data keys need and opens the purpose root is used; no keyset and nothing
 *       stored → a new one; otherwise {@link MasterKeyLock} is set ({@code MASTER_KEY_MISSING}/
 *       {@code MASTER_KEY_INVALID}) and the core starts locked.</li>
 *   <li><b>Rotation.</b> A new primary is written to the file at once (new secrets use it), in the transaction that
 *       stores the target in {@code master_key_state} and records the Setup change; then a virtual thread re-wraps
 *       every data key (ciphertexts stay), then the old keys are disabled once no data key needs them (counted again
 *       after the secrets being written while the primary changed have committed, see {@link #sealing}); they are
 *       deleted after the next backup. After a crash the job resumes at start.</li>
 *   <li><b>Every change of the key file</b> (create, restore, rotate) commits with its Setup change or not at all:
 *       a failed transaction puts the previous file back.</li>
 *   <li><b>Purpose keys.</b> A random 32-byte root sealed like a secret ({@code setting secrets.mac-root}); HKDF with
 *       the purpose's {@code info}. They survive rotation (the root is re-wrapped like any data key) and change only
 *       when a new key is created.</li>
 *   <li><b>Announcements.</b> {@link MasterKeyChanged} goes out in a transaction once the change has committed and
 *       the new keys are in use, so that AFTER_COMMIT listeners (§2.2) hear it; changes made before the application
 *       is ready wait for it.</li>
 * </ul>
 * One writer at a time ({@link ReentrantLock}); reads of the keyset are lock-free.
 */
@Service
public class MasterKeyService implements HmacKeys, InitializingBean, DisposableBean {

  static final String MAC_ROOT_SETTING = "secrets.mac-root";
  static final String MAC_ROOT_ID = "mac-root";
  private static final int REWRAP_BATCH = 50;
  /** How long a rotation waits for secrets still being written before it gives up (and resumes at the next start). */
  private static final Duration SEALS_WAIT = Duration.ofSeconds(60);
  /** The {@link #sealing} depth of this thread: {@link #seal} refuses to run outside one. */
  private static final ThreadLocal<int[]> SEALING = new ThreadLocal<>();
  private static final int MAX_BACKUP_BYTES = 1 << 20;

  private static final Logger log = LoggerFactory.getLogger(MasterKeyService.class);

  private final KeysetLoader loader;
  private final MasterKeyLock lock;
  private final SecretRepository secrets;
  private final SecretVersionRepository versions;
  private final MasterKeyStateRepository states;
  private final SettingStore settings;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final EventBus events;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final GatewayPaths paths;
  private final WalCheckpoint checkpoint;
  private final Clock clock;

  private final AtomicReference<KeysetHandle> keyset = new AtomicReference<>();
  private final ReentrantLock writes = new ReentrantLock();
  /**
   * Transactions inside {@link #sealing} that haven't completed: one may hold a value sealed with the primary key a
   * rotation is about to disable, so the rotation counts what is left to re-wrap only once this is zero.
   */
  private final AtomicInteger sealsInFlight = new AtomicInteger();
  private final List<String> warnings = new CopyOnWriteArrayList<>();
  /**
   * Changes made before the application was ready, announced once it is: {@code @EventListener} methods (and with them
   * every {@code @TransactionalEventListener}) are registered only after all singletons exist, and a lost key replaced
   * at start or a rotation resumed at start happen earlier. Guarded by itself, together with {@link #ready}.
   */
  private final List<MasterKeyChanged.Kind> unannounced = new ArrayList<>();
  private boolean ready;
  private volatile Map<Purpose, byte[]> purposeKeys = Map.of();
  private volatile MasterKeyStatus.Rotation rotation = MasterKeyStatus.Rotation.IDLE;
  private volatile Thread rotationThread;
  /** Test hook: the rotation job stops (as if killed) after this many re-wraps; negative: never. */
  volatile int stopRotationAfter = -1;

  /** A backup written: where, when, which key ids it holds, what to tell the person. */
  public record BackupResult(Path path, Instant at, List<Long> keyIds, boolean passphrase, List<String> warnings) {}

  /** A key restored from a backup. */
  public record RestoreResult(int secretsReadable) {}

  /** A new key created in locked mode: the secrets that now need a value. */
  public record CreateResult(List<String> needValue) {}

  public MasterKeyService(KeysetLoader loader, MasterKeyLock lock, SecretRepository secrets,
      SecretVersionRepository versions, MasterKeyStateRepository states, SettingStore settings,
      ObjectProvider<SetupChangeRecorder> setupChanges, EventBus events, ApplicationEventPublisher publisher,
      PlatformTransactionManager transactions, GatewayPaths paths, WalCheckpoint checkpoint, Clock clock) {
    this.loader = loader;
    this.lock = lock;
    this.secrets = secrets;
    this.versions = versions;
    this.states = states;
    this.settings = settings;
    this.setupChanges = setupChanges;
    this.events = events;
    this.publisher = publisher;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
    this.paths = paths;
    this.checkpoint = checkpoint;
    this.clock = clock;
  }

  // ── Start ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public void afterPropertiesSet() {
    KeysetFile.Loaded loaded = loader.loaded();
    Set<Long> required = requiredKeyIds();
    switch (loaded.state()) {
      case LOADED -> {
        if (!Keysets.covers(loaded.handle(), required)) {
          log.warn("The master key doesn't open the stored secrets (needs key ids {}, has {}): locked", required,
              Keysets.enabledKeyIds(loaded.handle()));
          lock.lock(MasterKeyLock.Reason.MASTER_KEY_INVALID);
          return;
        }
        Optional<byte[]> root;
        try {
          root = openPurposeRoot(loaded.handle());
        } catch (GeneralSecurityException e) {
          // The key ids match but the stored root doesn't decrypt (a damaged row, another installation's key): the
          // same locked start as any key that doesn't open the stored data (§7.1 b), never a failed start.
          log.warn("The master key doesn't open its purpose keys ({}): locked", e.getMessage());
          lock.lock(MasterKeyLock.Reason.MASTER_KEY_INVALID);
          return;
        }
        keyset.set(loaded.handle());
        if (loader.fileKey() != null && !loaded.encrypted()) {
          // OS protection became available: the cleartext file is replaced by the encrypted form.
          writeFile(loaded.handle());
          log.info("Master key file now OS-protected");
        }
        if (root.isPresent()) {
          derive(root.get());
        } else {
          newPurposeKeys();
        }
        resumeRotation();
      }
      case MISSING -> {
        Set<Long> forSecrets = secretKeyIds();
        if (!forSecrets.isEmpty()) {
          log.warn("No master key, but {} key id(s) are needed by stored secrets: locked", forSecrets.size());
          lock.lock(MasterKeyLock.Reason.MASTER_KEY_MISSING);
          return;
        }
        // No secret to lose (§7.1, case b): a new key, silently but with a warning. Only the purpose keys change:
        // the handles and approval states they signed expire; when the database had a key before, MAC'd rows stay
        // unverified until the person reviews them (MasterKeyChanged.REPLACED_LOST: RowMac doesn't re-sign them).
        boolean replaced = purposeRoot().isPresent();
        KeysetHandle first = generate();
        byte[] root = newRoot();
        Envelope.Sealed sealedRoot = sealRoot(first, root);
        replaceFile(first, () -> {
          storePurposeRoot(sealedRoot);
          state().keyCreated(clock.instant());
          if (replaced) {
            record("master key", "missing", "new key (no secret needed the old one)", null);
          }
        }, root);
        keyset.set(first);
        derive(root);
        if (replaced) {
          announce(MasterKeyChanged.Kind.REPLACED_LOST);
        }
        if (replaced || paths.databaseExisted()) {
          warnings.add("new-key-created");
          log.warn("No master key was found and no secret needed one: a new key was created at {}",
              loader.file().path());
        } else {
          log.info("Master key created at {}", loader.file().path());
        }
      }
      case INVALID -> {
        log.warn("Master key unusable, core locked: {}", loaded.problem());
        lock.lock(MasterKeyLock.Reason.MASTER_KEY_INVALID);
      }
    }
  }

  /** Announces what changed before the listeners existed (a key replaced at start, a resumed rotation). */
  @EventListener(ApplicationReadyEvent.class)
  void announceStartupChanges() {
    List<MasterKeyChanged.Kind> kinds;
    synchronized (unannounced) {
      ready = true;
      kinds = List.copyOf(unannounced);
      unannounced.clear();
    }
    kinds.forEach(this::publish);
  }

  /**
   * Tells the users of the master key and its purpose keys (RowMac, the audit head, pools) about a change that has
   * committed and is in use: call it after the database commit and after {@link #keyset} and {@link #purposeKeys} are
   * switched, so that a listener can use the new keys at once.
   */
  private void announce(MasterKeyChanged.Kind kind) {
    synchronized (unannounced) {
      if (!ready) {
        unannounced.add(kind);
        return;
      }
    }
    publish(kind);
  }

  /**
   * In a transaction: modules listen with {@code @TransactionalEventListener(AFTER_COMMIT)} (§2.2), which hears
   * nothing published outside one. Not in the transaction of the change itself: its AFTER_COMMIT callbacks would run
   * before this service switches to the new keyset and purpose keys, and a listener signing at once would get the old
   * ones, or none in locked mode. Inside a caller's transaction, the event follows that one's commit.
   */
  private void publish(MasterKeyChanged.Kind kind) {
    try {
      writeTx.executeWithoutResult(s -> publisher.publishEvent(new MasterKeyChanged(kind)));
    } catch (RuntimeException e) {
      // The change itself has committed and is in use; failing it now would only misreport it. Listeners that miss
      // the news fail closed: a MAC made with the old keys doesn't verify.
      log.warn("The master key change {} wasn't announced: {}", kind, e.getMessage());
    }
  }

  @Override
  public void destroy() {
    Thread job = rotationThread;
    if (job != null) {
      job.interrupt();
    }
  }

  // ── HmacKeys ────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public byte[] mac(Purpose purpose, byte[] data) {
    byte[] key = purposeKeys.get(purpose);
    if (key == null) {
      throw new IllegalStateException(lock.message());
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── Envelope for SecretService ──────────────────────────────────────────────────────────────────────────────

  /**
   * Runs {@code work}, the transaction that stores what {@link #seal} returns, so that a rotation can't disable the key
   * it sealed with before it commits: the primary may change between the seal and the commit, and a data key written
   * after the rotation's last read would stay wrapped by a disabled key. Inside a caller's transaction the seal counts
   * until that transaction completes.
   */
  <T> T sealing(Supplier<T> work) {
    int[] depth = SEALING.get();
    if (depth == null) {
      depth = new int[1];
      SEALING.set(depth);
    }
    depth[0]++;
    sealsInFlight.incrementAndGet();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      int[] held = depth;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          sealed(held);
        }
      });
      return work.get();
    }
    try {
      return work.get();
    } finally {
      sealed(depth);
    }
  }

  private void sealed(int[] depth) {
    if (--depth[0] == 0) {
      SEALING.remove();
    }
    sealsInFlight.decrementAndGet();
  }

  Envelope.Sealed seal(String id, int version, byte[] value) {
    int[] depth = SEALING.get();
    if (depth == null || depth[0] == 0) {
      throw new IllegalStateException("A secret is sealed outside sealing(): a rotation could disable its key");
    }
    try {
      return Envelope.seal(current(), id, version, value);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't encrypt a secret", e);
    }
  }

  byte[] open(String id, int version, byte[] ciphertext, byte[] wrappedDek) throws GeneralSecurityException {
    return Envelope.open(current(), id, version, ciphertext, wrappedDek);
  }

  boolean locked() {
    return lock.locked();
  }

  String lockedMessage() {
    return lock.message();
  }

  // ── Rotation ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Starts a rotation: a new primary key now, re-wrapping of every data key in the background ({@code GET
   * /api/master-key/rotation}, SSE {@code master-key.rotation}).
   */
  public MasterKeyStatus.Rotation rotate(String reason) {
    requireUnlocked();
    writes.lock();
    try {
      if ("running".equals(rotation.state())) {
        throw new MasterKeyException(MasterKeyException.Problem.ROTATION_RUNNING, "The master key is already rotating");
      }
      KeysetHandle before = current();
      KeysetHandle next;
      try {
        next = Keysets.withNewPrimary(before);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Can't add a key to the master keyset", e);
      }
      long target = Envelope.primaryKeyId(next);
      // The target and the Setup change commit together with the new file, or nothing changes: a rotation the
      // database doesn't know about would never finish and never disable the old key.
      boolean[] written = {false};
      try {
        writeTx.executeWithoutResult(status -> {
          state().setRotationTargetKeyId(target);
          // New secrets use the new primary from now on: the key is rotated, re-wrapping finishes the job.
          state().keyRotated(clock.instant());
          record("master key", "key " + Envelope.primaryKeyId(before), "key " + target + " (rotating)", reason);
          writeFile(next);
          written[0] = true;
        });
      } catch (RuntimeException e) {
        if (written[0]) {
          // The commit failed after the file was written: back to the keyset still in use (next only adds a key).
          try {
            writeFile(before);
            log.warn("The rotation didn't commit; the key file was put back: {}", e.getMessage());
          } catch (MasterKeyException undo) {
            e.addSuppressed(undo);
          }
        }
        throw e;
      }
      keyset.set(next);
      log.info("Master key rotation started: new primary key {}", target);
      startRotation(target);
      return rotation;
    } finally {
      writes.unlock();
    }
  }

  public MasterKeyStatus.Rotation rotation() {
    return rotation;
  }

  /**
   * Finishes a rotation that didn't: its target is stored, or the keyset still has enabled keys besides the primary
   * (the core stopped between writing the file and committing the target, or a backup taken mid-rotation was
   * restored).
   */
  private void resumeRotation() {
    MasterKeyStateEntity state = readTx.execute(s -> states.findById(MasterKeyStateEntity.ID).orElse(null));
    Long stored = state == null ? null : state.getRotationTargetKeyId();
    long primary = Envelope.primaryKeyId(handle());
    if (stored == null) {
      if (Keysets.enabledKeyIds(handle()).size() <= 1) {
        return;
      }
      log.warn("The master keyset has old keys still enabled: finishing the rotation to key {}", primary);
      writeTx.executeWithoutResult(s -> {
        state().setRotationTargetKeyId(primary);
        record("master key", "older keys enabled", "key " + primary + " (finishing a rotation)", null);
      });
    } else if (stored != primary) {
      writeTx.executeWithoutResult(s -> state().setRotationTargetKeyId(primary));
    }
    log.info("Resuming the master key rotation to key {}", primary);
    startRotation(primary);
  }

  private void startRotation(long target) {
    long total = readTx.execute(s -> versions.countWrappedByOtherKeys(target));
    rotation = new MasterKeyStatus.Rotation("running", 0, (int) total, target, null);
    publishRotation();
    rotationThread = Thread.ofVirtual().name("master-key-rotation").start(() -> runRotation(target));
  }

  private void runRotation(long target) {
    int done = 0;
    try {
      for (;;) {
        for (;;) {
          if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("the core is stopping");
          }
          List<String> batch = readTx.execute(s -> versions.findWrappedByOtherKeys(target,
              PageRequest.of(0, REWRAP_BATCH)).stream().map(SecretVersionEntity::getId).toList());
          if (batch == null || batch.isEmpty()) {
            break;
          }
          for (String id : batch) {
            if (stopRotationAfter >= 0 && done >= stopRotationAfter) {
              throw new IllegalStateException("rotation stopped after " + done + " re-wraps (test)");
            }
            writeTx.executeWithoutResult(s -> rewrap(id, target));
            done++;
            rotation = new MasterKeyStatus.Rotation("running", done, Math.max(done, rotation.total()), target, null);
            publishRotation();
          }
        }
        rewrapPurposeRoot(target);
        // A secret sealed with the old primary just before the switch may commit after the last batch was read:
        // count again once those writes are done, and only disable the old keys when nothing needs them.
        awaitSealsInFlight();
        long left;
        writes.lock();
        try {
          left = readTx.execute(s -> versions.countWrappedByOtherKeys(target));
          if (left == 0) {
            KeysetHandle disabled = Keysets.disableAllButPrimary(handle());
            writeFile(disabled);
            keyset.set(disabled);
            writeTx.executeWithoutResult(s -> state().setRotationTargetKeyId(null));
            break;
          }
        } finally {
          writes.unlock();
        }
        log.info("{} data key(s) were sealed with the old key during the rotation; re-wrapping them", left);
      }
      rotation = new MasterKeyStatus.Rotation("done", done, done, target, null);
      publishRotation();
      announce(MasterKeyChanged.Kind.ROTATED);
      log.info("Master key rotation done: {} data key(s) re-wrapped to key {}", done, target);
    } catch (RuntimeException | GeneralSecurityException e) {
      rotation = new MasterKeyStatus.Rotation("failed", done, rotation.total(), target, e.getMessage());
      publishRotation();
      log.warn("Master key rotation stopped after {} re-wrap(s); it resumes at the next start: {}", done,
          e.getMessage());
    } finally {
      rotationThread = null;
    }
  }

  /** Waits, holding no lock, until every {@link #sealing} transaction that started before has completed. */
  private void awaitSealsInFlight() {
    long deadline = System.nanoTime() + SEALS_WAIT.toNanos();
    while (sealsInFlight.get() > 0) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("a secret is still being written");
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("the core is stopping", e);
      }
    }
  }

  private void rewrap(String versionId, long target) {
    SecretVersionEntity v = versions.findById(versionId).orElse(null);
    if (v == null || v.getWrappedDek() == null || v.getKeyId() == null || v.getKeyId() == target) {
      return;
    }
    try {
      Envelope.Sealed sealed = Envelope.rewrap(handle(), v.getSecret().getId(), v.getSecretVersion(),
          v.getCiphertext(), v.getWrappedDek());
      v.rewrap(sealed.wrappedDek(), sealed.keyId());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't re-wrap the data key of " + v.getSecret().getName(), e);
    }
  }

  private void publishRotation() {
    MasterKeyStatus.Rotation r = rotation;
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("state", r.state());
    data.put("done", r.done());
    data.put("total", r.total());
    events.publish("master-key.rotation", data);
  }

  // ── Backup, restore, create ─────────────────────────────────────────────────────────────────────────────────

  /**
   * Writes a backup of the current keyset to {@code target} (0600), protected by {@code passphrase} when given. After
   * a rotation, the backup is what lets the old, disabled keys go.
   */
  public BackupResult backup(Path target, char[] passphrase) {
    requireUnlocked();
    writes.lock();
    try {
      KeysetHandle current = current();
      byte[] bytes;
      try {
        bytes = BackupCodec.write(current, passphrase);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Can't write the backup", e);
      }
      try {
        KeysetFile.writeAtomically(target, bytes, false);
      } catch (IOException e) {
        throw new MasterKeyException(MasterKeyException.Problem.IO, "Can't write " + target + ": " + e.getMessage(), e);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
      Instant now = clock.instant();
      List<Long> ids = Keysets.describe(current).stream().map(Keysets.KeyInfo::id).toList();
      writeTx.executeWithoutResult(s -> state().backedUp(now, ids));
      if (Keysets.hasDisabled(current) && !"running".equals(rotation.state())) {
        try {
          KeysetHandle trimmed = Keysets.deleteDisabled(current);
          writeFile(trimmed);
          keyset.set(trimmed);
        } catch (GeneralSecurityException e) {
          log.warn("Old master keys stay in the keyset: {}", e.getMessage());
        }
      }
      List<String> notes = new ArrayList<>();
      if (SyncedFolders.isSynced(target)) {
        notes.add("synced-folder");
      }
      boolean protectedByPassphrase = passphrase != null && passphrase.length > 0;
      if (!protectedByPassphrase) {
        notes.add("no-passphrase");
      }
      log.info("Master key backed up to {}{}", target, protectedByPassphrase ? " (passphrase)" : "");
      return new BackupResult(target, now, ids, protectedByPassphrase, notes);
    } finally {
      writes.unlock();
    }
  }

  /**
   * The demo world's key (GW_DEMO=1, design/BLUEPRINT.md §9: "last backup 12 days ago", Secrets "Created Jul 14"): the
   * keys in use are recorded as backed up, and the key as made, on the world's dates. No copy of the key is written: a
   * demo home is a normal home afterwards, and a backup without a passphrase lying in it would hand the key to anyone
   * who can read the file, past whatever guards the keyset (the Keychain on macOS) — review API #7. Never in a release
   * build.
   *
   * @return the ids of the keys recorded as backed up
   */
  public List<Long> seedDemo(Instant backedUpAt, Instant createdAt) {
    if (ai.msrv.gaterook.core.platform.BuildFlags.RELEASE) {
      throw new IllegalStateException("A release build has no demo world");
    }
    requireUnlocked();
    writes.lock();
    try {
      List<Long> ids = Keysets.describe(current()).stream().map(Keysets.KeyInfo::id).toList();
      writeTx.executeWithoutResult(s -> {
        MasterKeyStateEntity state = state();
        state.backedUp(backedUpAt, ids);
        state.keyCreated(createdAt);
      });
      return ids;
    } finally {
      writes.unlock();
    }
  }

  /** Locked mode: puts the key from a backup in place after checking it opens every stored secret; no restart. */
  public RestoreResult restore(Path source, char[] passphrase, String reason) {
    requireLocked();
    writes.lock();
    try {
      byte[] bytes;
      try {
        if (Files.size(source) > MAX_BACKUP_BYTES) {
          throw new MasterKeyException(MasterKeyException.Problem.BAD_BACKUP, "This file is too large to be a backup");
        }
        bytes = Files.readAllBytes(source);
      } catch (IOException e) {
        throw new MasterKeyException(MasterKeyException.Problem.IO, "Can't read " + source + ": " + e.getMessage(), e);
      }
      KeysetHandle restored;
      try {
        restored = BackupCodec.read(bytes, passphrase);
      } catch (BackupCodec.BackupException e) {
        throw new MasterKeyException(MasterKeyException.Problem.BAD_BACKUP, e.getMessage());
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
      Set<Long> required = requiredKeyIds();
      if (!Keysets.covers(restored, required)) {
        Set<Long> missing = new HashSet<>(required);
        missing.removeAll(Keysets.enabledKeyIds(restored));
        throw new MasterKeyException(MasterKeyException.Problem.MISSING_KEYS, "This backup doesn't hold the key of "
            + "the stored secrets (" + missing.size() + " key id(s) missing). Was it made before the last rotation?");
      }
      Optional<byte[]> root;
      try {
        // Key ids can match by chance; the purpose root really decrypting proves it is this installation's key.
        root = openPurposeRoot(restored);
      } catch (GeneralSecurityException e) {
        throw new MasterKeyException(MasterKeyException.Problem.MISSING_KEYS, "This backup doesn't open the stored "
            + "data: it is another MCP Gateway's key, or the stored data is damaged.");
      }
      replaceFile(restored, () -> record("master key", "missing", "restored from a backup", reason),
          root.orElse(null));
      keyset.set(restored);
      if (root.isPresent()) {
        derive(root.get());
      } else {
        newPurposeKeys();
      }
      lock.unlock();
      int readable = readTx.execute(s -> (int) secrets.countByKindAndCurrentVersionIsNotNull(SecretKind.LOCAL));
      announce(MasterKeyChanged.Kind.RESTORED);
      log.info("Master key restored from a backup; the core is unlocked");
      resumeRotation();
      return new RestoreResult(readable);
    } finally {
      writes.unlock();
    }
  }

  /**
   * Locked mode, the danger path: a new key; every local secret encrypted with the lost one is destroyed and needs a
   * value again (references and short-lived credentials keep working).
   */
  public CreateResult create(String reason) {
    requireLocked();
    writes.lock();
    try {
      KeysetHandle fresh = generate();
      byte[] root = newRoot();
      Envelope.Sealed sealedRoot = sealRoot(fresh, root);
      Instant now = clock.instant();
      List<String> names = readTx.execute(s -> secrets.findByKindAndCurrentVersionIsNotNull(SecretKind.LOCAL)
          .stream().map(secret -> secret.getName()).sorted().toList());
      List<String> needValue = names == null ? List.of() : names;
      // Destroying the old data keys, the Setup change, the new purpose root and the new file: all or nothing.
      replaceFile(fresh, () -> {
        versions.destroyAllWrapped(SecretVersionState.DESTROYED, now);
        secrets.clearCurrentVersions(SecretKind.LOCAL);
        record("master key", "missing", "new key; " + needValue.size() + " secret(s) need a value", reason);
        storePurposeRoot(sealedRoot);
        state().setRotationTargetKeyId(null);
        state().keyCreated(now);
      }, root);
      keyset.set(fresh);
      derive(root);
      lock.unlock();
      checkpoint.truncate();
      announce(MasterKeyChanged.Kind.CREATED);
      log.warn("A new master key was created; {} local secret(s) were destroyed and need a value", needValue.size());
      return new CreateResult(needValue);
    } finally {
      writes.unlock();
    }
  }

  /** {@code Fix permissions}: the key file 0400, the gateway folder 0700. */
  public void fixPermissions() {
    try {
      loader.file().fixPermissions();
    } catch (IOException e) {
      throw new MasterKeyException(MasterKeyException.Problem.IO, "Can't change the permissions: " + e.getMessage(), e);
    }
  }

  /**
   * {@code POST /internal/keyset/protect}: OS protection switched on ({@code fileKey} from Electron's
   * {@code safeStorage}) or off ({@code null}); the key file is rewritten in the new form.
   */
  public void protect(byte[] fileKey) {
    requireUnlocked();
    writes.lock();
    try {
      try {
        loader.fileKey(fileKey);
      } catch (GeneralSecurityException e) {
        throw new MasterKeyException(MasterKeyException.Problem.IO, "The file key isn't a 32-byte AES key", e);
      } finally {
        if (fileKey != null) {
          Arrays.fill(fileKey, (byte) 0);
        }
      }
      writeFile(current());
      announce(MasterKeyChanged.Kind.PROTECTION_CHANGED);
      log.info("Master key file rewritten {}", loader.fileKey() != null ? "with OS protection" : "without OS protection");
    } finally {
      writes.unlock();
    }
  }

  // ── Status ──────────────────────────────────────────────────────────────────────────────────────────────────

  public MasterKeyStatus status() {
    KeysetFile file = loader.file();
    String mode = null;
    String owner = null;
    try {
      if (file.exists()) {
        mode = file.mode();
        owner = file.owner();
      }
    } catch (IOException e) {
      log.debug("Can't read the key file's attributes: {}", e.getMessage());
    }
    KeysetHandle current = keyset.get();
    List<MasterKeyStatus.Key> keys = current == null || lock.locked() ? List.of()
        : Keysets.describe(current).stream().map(k -> new MasterKeyStatus.Key(k.id(), k.status(), k.primary())).toList();
    MasterKeyStateEntity state = readTx.execute(s -> states.findById(MasterKeyStateEntity.ID).orElse(null));
    int protects = readTx.execute(s -> (int) secrets.countByKindAndCurrentVersionIsNotNull(SecretKind.LOCAL));
    List<String> problems = new ArrayList<>(file.problems());
    String protection = loader.osProtection();
    if (protection.equals("off") || protection.equals("unavailable")) {
      problems.add("no-os-protection");
    }
    lock.reason().ifPresent(r -> problems.add(r == MasterKeyLock.Reason.MASTER_KEY_MISSING ? "key-missing"
        : "key-invalid"));
    if (current != null && !lock.locked()) {
      long primary = Envelope.primaryKeyId(current);
      if (state == null || state.getLastBackupKeyIds() == null || !state.getLastBackupKeyIds().contains(primary)) {
        problems.add("backup-needed");
      }
    }
    return new MasterKeyStatus(file.path().toString(), mode, owner, keys, protection, protects,
        state == null ? null : state.getLastBackupAt(), List.copyOf(problems), List.copyOf(warnings),
        lock.reason().map(Enum::name).orElse(null), rotation, state == null ? null : state.getKeyCreatedAt(),
        state == null ? null : state.getKeyRotatedAt());
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /** The keyset for callers outside this class: refused while locked. */
  private KeysetHandle current() {
    if (lock.locked()) {
      throw new MasterKeyException(MasterKeyException.Problem.LOCKED, lock.message());
    }
    return handle();
  }

  /** The keyset as loaded, also during restore and create (still locked then). */
  private KeysetHandle handle() {
    KeysetHandle handle = keyset.get();
    if (handle == null) {
      throw new MasterKeyException(MasterKeyException.Problem.LOCKED, lock.message());
    }
    return handle;
  }

  private void requireUnlocked() {
    if (lock.locked()) {
      throw new MasterKeyException(MasterKeyException.Problem.LOCKED, lock.message()
          + ". Restore the key from your backup or create a new one.");
    }
  }

  private void requireLocked() {
    if (!lock.locked()) {
      throw new MasterKeyException(MasterKeyException.Problem.NOT_LOCKED, "The master key works: there is nothing to "
          + "restore. Rotate it instead.");
    }
  }

  /** Every master key id some stored data key or the purpose root needs. */
  private Set<Long> requiredKeyIds() {
    Set<Long> ids = secretKeyIds();
    purposeRoot().ifPresent(root -> ids.add(root.path("keyId").asLong()));
    return ids;
  }

  /** The master key ids the stored data keys of secrets need: what a lost key would destroy. */
  private Set<Long> secretKeyIds() {
    Set<Long> ids = new HashSet<>(readTx.execute(s -> versions.findKeyIdsInUse()));
    ids.remove(null);
    return ids;
  }

  private MasterKeyStateEntity state() {
    return states.findById(MasterKeyStateEntity.ID).orElseGet(() -> states.save(new MasterKeyStateEntity()));
  }

  private void record(String subject, String from, String to, String reason) {
    SetupChangeRecorder recorder = setupChanges.getIfAvailable();
    if (recorder == null) {
      throw new IllegalStateException("Setup changes can't be recorded: no recorder");
    }
    recorder.record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET, subject, from, to,
        reason == null || reason.isBlank() ? null : reason, null, null, null, null));
  }

  private KeysetHandle generate() {
    try {
      return Keysets.generate();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't generate a master key", e);
    }
  }

  private void writeFile(KeysetHandle handle) {
    try {
      loader.file().write(handle, loader.fileKey());
    } catch (IOException | GeneralSecurityException e) {
      throw new MasterKeyException(MasterKeyException.Problem.IO, "Can't write " + loader.file().path() + ": "
          + e.getMessage(), e);
    }
  }

  /**
   * Puts {@code next} in place of the key file inside one write transaction with {@code work}, the file last. When
   * anything fails — the Setup change can't be recorded, the database is busy, the commit itself — the database rolls
   * back and the previous file is put back (it was only moved aside), so the key in use, the file and the database
   * never disagree (§9, fail-closed). {@code zeroOnFailure} is a purpose root the caller won't use then.
   */
  private void replaceFile(KeysetHandle next, Runnable work, byte[] zeroOnFailure) {
    Path[] aside = {null};
    boolean[] written = {false};
    try {
      writeTx.executeWithoutResult(status -> {
        work.run();
        aside[0] = moveAsideIfPresent();
        writeFile(next);
        written[0] = true;
      });
    } catch (RuntimeException e) {
      try {
        if (aside[0] != null) {
          loader.file().moveBack(aside[0]);
        } else if (written[0]) {
          loader.file().delete();
        }
      } catch (IOException undo) {
        e.addSuppressed(undo);
        log.error("The key file couldn't be put back after a failed change: {}", undo.getMessage());
      }
      if (zeroOnFailure != null) {
        Arrays.fill(zeroOnFailure, (byte) 0);
      }
      throw e;
    }
    if (aside[0] != null) {
      log.info("The previous key file was kept as {}", aside[0]);
    }
  }

  /** Renames the key file aside (kept, 0400) and returns where it went, or {@code null} when there is none. */
  private Path moveAsideIfPresent() {
    if (!loader.file().exists()) {
      return null;
    }
    try {
      return loader.file().moveAside(clock.instant());
    } catch (IOException e) {
      throw new MasterKeyException(MasterKeyException.Problem.IO, "Can't move the old key file aside: "
          + e.getMessage(), e);
    }
  }

  private Optional<JsonNode> purposeRoot() {
    return readTx.execute(s -> settings.find(MAC_ROOT_SETTING)).map(SettingStore.Setting::value);
  }

  /**
   * The stored purpose root opened with {@code handle}, or empty when none is stored yet.
   *
   * @throws GeneralSecurityException when the stored root doesn't decrypt with {@code handle} or is damaged
   */
  private Optional<byte[]> openPurposeRoot(KeysetHandle handle) throws GeneralSecurityException {
    Optional<JsonNode> stored = purposeRoot();
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    JsonNode root = stored.get();
    try {
      return Optional.of(Envelope.open(handle, MAC_ROOT_ID, root.path("v").asInt(1),
          Base64.getDecoder().decode(root.path("ct").asString()), Base64.getDecoder().decode(root.path("dek").asString())));
    } catch (RuntimeException e) {
      // Not Base64, a truncated keyset: damaged the same way a wrong key is.
      throw new GeneralSecurityException("The stored purpose root is damaged", e);
    }
  }

  private void newPurposeKeys() {
    byte[] plain = newRoot();
    storePurposeRoot(sealRoot(handle(), plain));
    derive(plain);
  }

  private static byte[] newRoot() {
    byte[] root = new byte[32];
    new SecureRandom().nextBytes(root);
    return root;
  }

  private static Envelope.Sealed sealRoot(KeysetHandle handle, byte[] root) {
    try {
      return Envelope.seal(handle, MAC_ROOT_ID, 1, root);
    } catch (GeneralSecurityException e) {
      Arrays.fill(root, (byte) 0);
      throw new IllegalStateException("Can't seal the purpose root", e);
    }
  }

  private void rewrapPurposeRoot(long target) throws GeneralSecurityException {
    Optional<JsonNode> stored = purposeRoot();
    if (stored.isEmpty() || stored.get().path("keyId").asLong() == target) {
      return;
    }
    JsonNode root = stored.get();
    Envelope.Sealed sealed = Envelope.rewrap(handle(), MAC_ROOT_ID, root.path("v").asInt(1),
        Base64.getDecoder().decode(root.path("ct").asString()), Base64.getDecoder().decode(root.path("dek").asString()));
    storePurposeRoot(sealed);
  }

  private void storePurposeRoot(Envelope.Sealed sealed) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("v", 1);
    root.put("keyId", sealed.keyId());
    root.put("dek", Base64.getEncoder().encodeToString(sealed.wrappedDek()));
    root.put("ct", Base64.getEncoder().encodeToString(sealed.ciphertext()));
    writeTx.executeWithoutResult(s -> settings.put(MAC_ROOT_SETTING, root, null));
  }

  private void derive(byte[] root) {
    Map<Purpose, byte[]> keys = new EnumMap<>(Purpose.class);
    try {
      for (Purpose purpose : Purpose.values()) {
        keys.put(purpose, Hkdf.computeHkdf("HMACSHA256", root, new byte[0],
            purpose.info().getBytes(StandardCharsets.UTF_8), 32));
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't derive the purpose keys", e);
    } finally {
      Arrays.fill(root, (byte) 0);
    }
    purposeKeys = Map.copyOf(keys);
  }
}
