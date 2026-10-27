package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.SecretVersionChanged;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretLeaseRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretOwnerKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.gcp.GcpSmStore;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Secrets › External stores (ARCHITECTURE.md §6 secrets; report 12 §3–§5): connect, edit and disconnect a store, test
 * it, test and replace references, and what the stores issue for Secrets › Short-lived. Every change is one
 * transaction with its Setup change; a store's own sign-in values are local secrets of owner kind {@code store}
 * ({@code vault/approle-secret-id}), never part of its settings.
 */
public final class StoreAdmin implements SecretOwners {

  /** {@code vault}, {@code gcp-acme-stage}: lower-case letters, digits and hyphens. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

  /** How long a store test or a Test reference may take. */
  static final Duration TEST_BUDGET = Duration.ofSeconds(15);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final ExternalStoreRepository stores;
  private final SecretRepository secrets;
  private final SecretLeaseRepository leaseRows;
  private final StoreClients clients;
  private final StoreBinding binding;
  private final StoreResolution resolution;
  private final SecretCache cache;
  private final LeaseManager leases;
  private final StoreSchemas schemas;
  private final SchemaValidator validator;
  private final ObjectProvider<SecretService> secretService;
  private final ObjectProvider<SecretOwners> owners;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final IntegrityAlerts alerts;
  private final ApplicationEventPublisher publisher;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  /** Latency and error of each store's last test since the core started: "Connected · 18 ms". */
  private final Map<String, SecretStores.TestResult> lastTests = new ConcurrentHashMap<>();

  public StoreAdmin(ExternalStoreRepository stores, SecretRepository secrets, SecretLeaseRepository leaseRows,
      StoreClients clients, StoreBinding binding, StoreResolution resolution, SecretCache cache, LeaseManager leases,
      StoreSchemas schemas, SchemaValidator validator, ObjectProvider<SecretService> secretService,
      ObjectProvider<SecretOwners> owners, ObjectProvider<SetupChangeRecorder> setupChanges, IntegrityAlerts alerts,
      ApplicationEventPublisher publisher, JsonMapper json, Clock clock, PlatformTransactionManager tx) {
    this.stores = stores;
    this.secrets = secrets;
    this.leaseRows = leaseRows;
    this.clients = clients;
    this.binding = binding;
    this.resolution = resolution;
    this.cache = cache;
    this.leases = leases;
    this.schemas = schemas;
    this.validator = validator;
    this.secretService = secretService;
    this.owners = owners;
    this.setupChanges = setupChanges;
    this.alerts = alerts;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(tx);
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  public List<SecretStores.StoreInfo> list() {
    return readTx.execute(s -> stores.findAll().stream().sorted(Comparator.comparing(ExternalStoreEntity::getName))
        .map(this::info).toList());
  }

  public SecretStores.StoreInfo get(String idOrName) {
    return readTx.execute(s -> info(find(idOrName)));
  }

  public JsonNode schema(String kind) {
    StoreKind k = kind(kind, "/kind");
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("kind", StoreSchemas.id(k));
    out.put("title", StoreResolution.title(k));
    out.set("schema", schemas.schema(k).deepCopy());
    var fields = out.putArray("secretFields");
    StoreSchemas.secretFields(k).forEach(fields::add);
    return out;
  }

  private SecretStores.StoreInfo info(ExternalStoreEntity e) {
    List<SecretStores.Reference> refs = secrets.findByStoreId(e.getId()).stream()
        .sorted(Comparator.comparing(SecretEntity::getName))
        .map(r -> new SecretStores.Reference(r.getId(), r.getName(), r.getRefUri(), r.getKind() == SecretKind.DYNAMIC
            ? "dynamic" : "reference", ownerLabel(r))).toList();
    List<SecretStores.SignInSecret> signIn = secrets.findByOwnerKindAndOwnerId(SecretOwnerKind.STORE, e.getId())
        .stream().sorted(Comparator.comparing(SecretEntity::getName))
        .map(r -> new SecretStores.SignInSecret(r.getField(), r.getId(), r.getName(), r.getCurrentVersion() == null
            ? "needs-value" : "stored", r.getCurrentVersion())).toList();
    SecretStores.TestResult last = lastTests.get(e.getId());
    // Connections the store issues short-lived credentials for: dynamic references, SSH certificates since start.
    Set<String> issuedFor = new java.util.HashSet<>();
    secrets.findByStoreId(e.getId()).stream().filter(r -> r.getKind() == SecretKind.DYNAMIC)
        .forEach(r -> issuedFor.add(r.getOwnerId()));
    resolution.signed().stream().filter(x -> x.storeId().equals(e.getId())).forEach(x -> issuedFor.add(x.ownerId()));
    int issues = issuedFor.size();
    return new SecretStores.StoreInfo(e.getId(), StoreSchemas.id(e.getKind()), e.getName(),
        json.valueToTree(e.getConfig()), e.getState(), e.getLastCheckedAt(), last == null || !last.ok() ? null
        : last.latencyMs(), last == null || last.ok() ? null : last.error(), refs, signIn, issues,
        binding.integrity().verifyStore(e), e.getVersion());
  }

  // ── Connect, edit, disconnect ───────────────────────────────────────────────────────────────────────────────

  public SecretStores.StoreInfo create(String kindId, String name, JsonNode config, Map<String, char[]> values,
      String reason) {
    try {
      StoreKind kind = kind(kindId, "/kind");
      checkName(name);
      ObjectNode cleaned = settings(kind, config);
      generateExternalId(kind, cleaned);
      Map<String, char[]> given = signInValues(kind, values);
      requiredSignIn(kind, cleaned, given.keySet(), Set.of());
      String id = writeTx.execute(s -> {
        if (stores.findByName(name).isPresent()) {
          throw ProblemException.conflict("Name in use", "A store named " + name + " already exists.");
        }
        ExternalStoreEntity row = stores.saveAndFlush(new ExternalStoreEntity(kind, name, map(cleaned),
            "not-checked", clock.instant()));
        SecretOwner owner = new SecretOwner(SecretOwner.Kind.STORE, row.getId());
        for (Map.Entry<String, char[]> v : given.entrySet()) {
          SecretInfo info = secretService.getObject().createLocal(owner, v.getKey(), uniqueName(name, v.getKey()),
              v.getValue().clone());
          if (row.getAuthSecretId() == null) {
            row.setAuthSecretId(info.id());
          }
        }
        row = stores.saveAndFlush(row);
        binding.integrity().signStore(row);
        List<String> bound = bindDangling(row);
        setupChanges.getObject().record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET,
            "External store " + name + " connected", null, StoreResolution.title(kind) + " · " + describe(kind,
            cleaned), reason, null, null, null, bound.isEmpty() ? null : JsonNodeFactory.instance.objectNode()
            .set("references", json.valueToTree(bound))));
        return row.getId();
      });
      return get(id);
    } finally {
      if (values != null) {
        values.values().forEach(v -> Arrays.fill(v, '\0'));
      }
    }
  }

  public SecretStores.StoreInfo update(String idOrName, String newName, JsonNode config, Map<String, char[]> values,
      long version, String reason) {
    try {
      ExternalStoreEntity current = readTx.execute(s -> find(idOrName));
      StoreKind kind = current.getKind();
      ObjectNode cleaned = settings(kind, config);
      JsonNode before = json.valueToTree(current.getConfig());
      if (!cleaned.has("credentials") || !cleaned.path("credentials").has("externalId")) {
        // An ExternalId the gateway made stays: the role's trust policy names it.
        JsonNode old = before.path("credentials").path("externalId");
        if (old.isString() && cleaned.path("credentials").isObject()) {
          ((ObjectNode) cleaned.path("credentials")).put("externalId", old.asString());
        }
      }
      generateExternalId(kind, cleaned);
      Map<String, char[]> given = signInValues(kind, values);
      String name = newName == null || newName.isBlank() ? current.getName() : newName.strip();
      checkName(name);
      writeTx.executeWithoutResult(s -> {
        ExternalStoreEntity row = stores.findById(current.getId()).orElseThrow(() -> notFound(idOrName));
        if (row.getVersion() != version) {
          throw new OptimisticLockingFailureException("Store " + row.getName() + " is at version "
              + row.getVersion());
        }
        if (!binding.integrity().verifyStore(row)) {
          throw ProblemException.conflict("Changed outside MCP Gateway", row.getName() + " was changed outside MCP "
              + "Gateway. Review it first.");
        }
        if (!name.equals(row.getName()) && stores.findByName(name).isPresent()) {
          throw ProblemException.conflict("Name in use", "A store named " + name + " already exists.");
        }
        SecretOwner owner = new SecretOwner(SecretOwner.Kind.STORE, row.getId());
        Map<String, SecretEntity> stored = new LinkedHashMap<>();
        secrets.findByOwnerKindAndOwnerId(SecretOwnerKind.STORE, row.getId()).forEach(e -> stored.put(e.getField(),
            e));
        requiredSignIn(kind, cleaned, given.keySet(), stored.keySet());
        for (Map.Entry<String, char[]> v : given.entrySet()) {
          SecretEntity existing = stored.get(v.getKey());
          if (existing == null) {
            SecretInfo info = secretService.getObject().createLocal(owner, v.getKey(), uniqueName(name, v.getKey()),
                v.getValue().clone());
            stored.put(v.getKey(), secrets.findById(info.id()).orElseThrow());
          } else {
            secretService.getObject().replace(existing.getId(), v.getValue().clone(), false, reason);
          }
        }
        List<String> unused = stored.keySet().stream().filter(f -> !needed(kind, cleaned).contains(f)).toList();
        if (!unused.isEmpty()) {
          secretService.getObject().deleteForOwnerFields(owner, unused);
        }
        row.setName(name);
        row.setConfig(map(cleaned));
        row.setAuthSecretId(stored.entrySet().stream().filter(e -> !unused.contains(e.getKey())).map(e -> e
            .getValue().getId()).findFirst().orElse(null));
        row.setState("not-checked", row.getLastCheckedAt());
        row = stores.saveAndFlush(row);
        binding.integrity().signStore(row);
        setupChanges.getObject().record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET,
            "External store " + name + " edited", describe(kind, before), describe(kind, cleaned), reason, null,
            null, null, null));
        String id = row.getId();
        afterCommit(() -> {
          clients.drop(id);
          cache.evictStore(id);
          lastTests.remove(id);
        });
      });
      return get(current.getId());
    } finally {
      if (values != null) {
        values.values().forEach(v -> Arrays.fill(v, '\0'));
      }
    }
  }

  public SecretStores.Disconnected delete(String idOrName, String confirmName, String reason) {
    ExternalStoreEntity current = readTx.execute(s -> find(idOrName));
    if (!current.getName().equals(confirmName)) {
      throw ProblemException.unprocessable("Type the name to disconnect", "Type " + current.getName()
          + " to confirm.", "/confirmName");
    }
    // Leases end at Vault first: the store is still there to revoke them.
    int revoked = leases.revokeStore(current.getId());
    SecretStores.Disconnected out = writeTx.execute(s -> {
      ExternalStoreEntity row = stores.findById(current.getId()).orElseThrow(() -> notFound(idOrName));
      leaseRows.deleteAll(leaseRows.findAll().stream().filter(l -> l.getStore().getId().equals(row.getId()))
          .toList());
      leaseRows.flush();
      List<String> dangling = new ArrayList<>();
      for (SecretEntity ref : secrets.findByStoreId(row.getId())) {
        SecretRef parsed = SecretRef.parse(ref.getRefUri());
        binding.point(ref, ref.getKind() == SecretKind.DYNAMIC, parsed, null);
        dangling.add(ref.getName());
        publisher.publishEvent(new SecretVersionChanged(ref.getId(), new SecretOwner(SecretOwner.Kind.valueOf(
            ref.getOwnerKind().name()), ref.getOwnerId()), ref.getField(), null));
      }
      secrets.flush();
      List<String> destroyed = secretService.getObject().deleteForOwner(new SecretOwner(SecretOwner.Kind.STORE,
          row.getId()));
      stores.delete(row);
      stores.flush();
      binding.integrity().forgetStore(row.getId());
      alerts.resolve("secret-store", row.getId());
      setupChanges.getObject().record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET,
          "External store " + row.getName() + " disconnected", StoreResolution.title(row.getKind()), null, reason,
          null, null, null, dangling.isEmpty() ? null : JsonNodeFactory.instance.objectNode()
          .set("notConnected", json.valueToTree(dangling))));
      return new SecretStores.Disconnected(List.copyOf(dangling), revoked, destroyed);
    });
    clients.drop(current.getId());
    cache.evictStore(current.getId());
    lastTests.remove(current.getId());
    return out;
  }

  /** The person reviewed a store changed outside MCP Gateway: it and its references are signed as they are. */
  public SecretStores.StoreInfo confirmIntegrity(String idOrName, String reason) {
    String id = writeTx.execute(s -> {
      ExternalStoreEntity row = find(idOrName);
      boolean storeOk = binding.integrity().verifyStore(row);
      List<SecretEntity> unverified = secrets.findByStoreId(row.getId()).stream()
          .filter(r -> !binding.integrity().verifyReference(r)).toList();
      if (storeOk && unverified.isEmpty()) {
        return row.getId();
      }
      binding.integrity().signStore(row);
      unverified.forEach(r -> binding.integrity().signReference(r));
      alerts.resolve("secret-store", row.getId());
      setupChanges.getObject().record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET,
          "External store " + row.getName() + " changed outside MCP Gateway", "Unverified", "Kept as stored", reason,
          null, null, null, null));
      return row.getId();
    });
    clients.drop(id);
    return get(id);
  }

  // ── Tests ───────────────────────────────────────────────────────────────────────────────────────────────────

  public SecretStores.TestResult test(String idOrName) {
    ExternalStoreEntity row = readTx.execute(s -> find(idOrName));
    StoreClients.Row r = readTx.execute(s -> clients.row(stores.findById(row.getId()).orElseThrow()));
    List<SecretRef> refs = clients.references(row.getId());
    SecretStores.TestResult result;
    Taint scratch = new Taint();
    try {
      result = clients.client(r).test(refs, StoreCall.within(scratch::add, TEST_BUDGET, clock));
    } catch (StoreException e) {
      result = new SecretStores.TestResult(false, 0, null, null, List.of(), List.of(), List.of(), e.getMessage(),
          e.reason().name().toLowerCase(java.util.Locale.ROOT));
    } finally {
      scratch.clear();
    }
    SecretStores.TestResult done = result;
    lastTests.put(row.getId(), done);
    writeTx.executeWithoutResult(s -> stores.findById(row.getId()).ifPresent(e -> e.setState(done.ok()
        ? "connected" : "failing", clock.instant())));
    return done;
  }

  /** Test with a pending version of a sign-in secret (Replace with "test before"). */
  SecretStores.TestResult testPending(String storeId, String field, int version) {
    StoreClients.Row r = clients.row(storeId);
    Taint scratch = new Taint();
    try (SecretStore store = clients.build(r, Map.of(field, version))) {
      return store.test(clients.references(storeId), StoreCall.within(scratch::add, TEST_BUDGET, clock));
    } catch (StoreException e) {
      return new SecretStores.TestResult(false, 0, null, null, List.of(), List.of(), List.of(), e.getMessage(),
          e.reason().name().toLowerCase(java.util.Locale.ROOT));
    } finally {
      scratch.clear();
    }
  }

  /**
   * Test reference (report 12 §5): found, which version "latest" is, when it was made, its type and key names — never
   * its length, hash or prefix. The value is zeroed at once and not cached.
   */
  public SecretStores.RefTest testReference(String uri, String storeName) {
    SecretRef ref = StoreBinding.parse(uri, "/uri");
    ExternalStoreEntity store = readTx.execute(s -> binding.storeFor(ref, storeName, "/store"));
    List<SecretStores.Hint> hints = new ArrayList<>();
    if (!ref.pinned() && !(ref instanceof SecretRef.Vault v && v.credsPath())) {
      hints.add(new SecretStores.Hint("info", "ref.unpinned", "This follows the newest version. Pin a version for "
          + "prod, so a change in the store needs a new reference."));
    }
    if (store == null) {
      String text = StoreResolution.title(ref.kind()) + " isn't connected: connect it in Secrets › External stores.";
      return new SecretStores.RefTest(false, ref.uri(), null, null, null, null, List.of(), 0, text, "not-connected",
          hints);
    }
    long t0 = System.nanoTime();
    Taint scratch = new Taint();
    StoreCall call = StoreCall.within(scratch::add, TEST_BUDGET, clock);
    try {
      SecretStore client = clients.client(store.getId());
      if (ref instanceof SecretRef.Vault v && v.credsPath() && client instanceof VaultStore vault) {
        // Issuing a lease just to test would create a database user; Vault says whether the role exists instead.
        String engine = vault.probe(v, call);
        hints.add(new SecretStores.Hint("info", "ref.dynamic", "Short-lived database users from " + v.path()
            + " (" + engine + "), issued when a call needs one and renewed while in use."));
        return new SecretStores.RefTest(true, ref.uri(), store.getName(), null, null, "dynamic", List.of(), ms(t0),
            null, null, hints);
      }
      try (RemoteValue value = resolution.readFresh(store.getId(), ref, call)) {
        Instant created = value.created();
        if (created == null && ref instanceof SecretRef.GcpSm g && client instanceof GcpSmStore gcp) {
          created = gcp.created(g, call);
        }
        return new SecretStores.RefTest(true, ref.uri(), store.getName(), value.version(), created, value.type(),
            value.keys(), ms(t0), null, null, hints);
      }
    } catch (StoreException e) {
      return new SecretStores.RefTest(false, ref.uri(), store.getName(), null, null, null, List.of(), ms(t0),
          e.getMessage(), e.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), hints);
    } finally {
      scratch.clear();
    }
  }

  /** Replace reference ({@code PUT /api/secrets/{id}/reference}). */
  public SecretInfo replaceReference(String secretId, String uri, String storeName, String reason) {
    SecretRef ref = StoreBinding.parse(uri, "/uri");
    String[] old = new String[2];
    writeTx.executeWithoutResult(s -> {
      SecretEntity secret = secrets.findById(secretId).orElseThrow(() -> ProblemException.notFound("Secret not found",
          "There is no secret " + secretId + "."));
      if (secret.getKind() == SecretKind.LOCAL) {
        throw ProblemException.unprocessable("Stored here", secret.getName() + " is stored here: replace its value "
            + "instead.", "/uri");
      }
      boolean dynamic = secret.getKind() == SecretKind.DYNAMIC;
      boolean creds = ref instanceof SecretRef.Vault v && v.credsPath();
      if (dynamic != creds) {
        throw ProblemException.unprocessable("Another kind of secret", dynamic ? secret.getName() + " is Vault "
            + "dynamic credentials: point it to vault://<mount>/creds/<role>." : "vault://…/creds/… issues short-lived "
            + "credentials; " + secret.getName() + " is a reference.", "/uri");
      }
      SecretOwner owner = new SecretOwner(SecretOwner.Kind.valueOf(secret.getOwnerKind().name()),
          secret.getOwnerId());
      Optional<SecretOwners.Owner> described = describeOwner(owner);
      if (described.map(SecretOwners.Owner::env).orElse(null) == Env.PROD && (reason == null || reason.isBlank())) {
        throw ProblemException.unprocessable("Comment required", "Add a comment: it is required to change a secret "
            + "on prod.", "/reason");
      }
      ExternalStoreEntity store = binding.storeFor(ref, storeName, "/store");
      old[0] = secret.getRefUri();
      old[1] = secret.getStore() == null ? null : secret.getStore().getId();
      binding.point(secret, dynamic, ref, store);
      secret.remoteChecked(null, clock.instant());
      secret.setRotatedAt(clock.instant());
      setupChanges.getObject().record(new SetupChangeRecorder.SetupChange(SetupChangeRecorder.What.SECRET,
          secret.getName(), old[0], ref.uri(), reason, described.map(SecretOwners.Owner::env).orElse(null), null,
          secret.getOwnerKind() == SecretOwnerKind.CONNECTION ? described.map(SecretOwners.Owner::label).orElse(null)
              : null, null));
      publisher.publishEvent(new SecretVersionChanged(secret.getId(), owner, secret.getField(), null));
    });
    if (old[1] != null) {
      cache.evict(old[1], old[0]);
    }
    leases.revoke(secretId, "its reference was replaced");
    return secretService.getObject().get(secretId);
  }

  // ── Short-lived, sources ────────────────────────────────────────────────────────────────────────────────────

  public List<SecretStores.Issued> issued() {
    List<SecretStores.Issued> out = new ArrayList<>();
    for (LeaseManager.Active a : leases.all()) {
      String label = readTx.execute(s -> secrets.findById(a.secretId()).map(this::ownerLabel).orElse(null));
      out.add(new SecretStores.Issued(label, "Vault dynamic database user", a.ref(), a.issuedAt(),
          a.ttl().toSeconds(), null));
    }
    for (StoreResolution.SignedFor sf : resolution.signed()) {
      String label = describeOwner(SecretOwner.connection(sf.ownerId())).map(SecretOwners.Owner::label)
          .orElse(sf.ownerId());
      out.add(new SecretStores.Issued(label, "SSH certificate from Vault CA", sf.signer(), sf.at(),
          sf.ttl().toSeconds(), sf.principal()));
    }
    out.sort(Comparator.comparing(SecretStores.Issued::connection, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(SecretStores.Issued::mechanism));
    return out;
  }

  public List<SecretStores.Source> sources() {
    Set<StoreKind> connected = readTx.execute(s -> {
      Set<StoreKind> k = java.util.EnumSet.noneOf(StoreKind.class);
      stores.findAll().forEach(e -> k.add(e.getKind()));
      return k;
    });
    List<SecretStores.Source> out = new ArrayList<>();
    out.add(new SecretStores.Source("local", "Encrypted here", true, null));
    for (StoreKind k : StoreKind.values()) {
      boolean on = connected.contains(k);
      out.add(new SecretStores.Source(StoreSchemas.id(k), StoreResolution.title(k), on, on ? null : "Connect "
          + StoreResolution.title(k) + " in Secrets first."));
    }
    boolean vault = connected.contains(StoreKind.VAULT);
    out.add(new SecretStores.Source("vault-dynamic", "Short-lived (Vault dynamic credentials)", vault, vault ? null
        : "Connect HashiCorp Vault in Secrets first."));
    return out;
  }

  // ── SecretOwners (the store's own sign-in secrets on Secrets) ───────────────────────────────────────────────

  @Override
  public SecretOwner.Kind kind() {
    return SecretOwner.Kind.STORE;
  }

  @Override
  public Optional<Owner> describe(String storeId) {
    return readTx.execute(s -> stores.findById(storeId).map(e -> new Owner(e.getName(), null, secrets
        .findByStoreId(storeId).stream().map(this::ownerLabel).filter(java.util.Objects::nonNull).distinct().sorted()
        .toList())));
  }

  @Override
  public Optional<TestOutcome> testWithVersion(String storeId, String field, int version) {
    SecretStores.TestResult r = testPending(storeId, field, version);
    return Optional.of(new TestOutcome(r.ok(), r.ok() ? "Signed in with the new value" : r.error()));
  }

  @Override
  public Optional<String> rotateUnavailable(String storeId, String field) {
    return Optional.of("Make a new value in the store's own console, then replace it here.");
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /** References of the new store's kind that had no store: bound now when the rules pick it. */
  private List<String> bindDangling(ExternalStoreEntity store) {
    List<String> bound = new ArrayList<>();
    for (SecretEntity s : secrets.findAll()) {
      if (s.getKind() == SecretKind.LOCAL || s.getStore() != null || s.getRefUri() == null) {
        continue;
      }
      SecretRef ref;
      try {
        ref = SecretRef.parse(s.getRefUri());
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (ref.kind() != store.getKind() || !binding.integrity().verifyReference(s)) {
        continue;
      }
      ExternalStoreEntity chosen;
      try {
        chosen = binding.storeFor(ref, null, "/store");
      } catch (ProblemException ambiguous) {
        continue;
      }
      if (chosen != null && chosen.getId().equals(store.getId())) {
        binding.point(s, s.getKind() == SecretKind.DYNAMIC, ref, store);
        bound.add(s.getName());
        publisher.publishEvent(new SecretVersionChanged(s.getId(), new SecretOwner(SecretOwner.Kind.valueOf(
            s.getOwnerKind().name()), s.getOwnerId()), s.getField(), null));
      }
    }
    return bound;
  }

  /** The settings as stored: validated by the kind's schema and its own reader, without secret values. */
  private ObjectNode settings(StoreKind kind, JsonNode config) {
    if (config == null || !config.isObject()) {
      throw ProblemException.unprocessable("Invalid settings", "The settings must be an object.", "/config");
    }
    ObjectNode cleaned = (ObjectNode) config.deepCopy();
    for (String field : StoreSchemas.secretFields(kind)) {
      JsonNode at = cleaned.at(field);
      if (!at.isMissingNode()) {
        throw ProblemException.unprocessable("Not in the settings", "Send " + field.substring(field.lastIndexOf('/')
            + 1) + " as a secret, not in the settings: it is encrypted and never shown again.", "/config" + field);
      }
    }
    for (SchemaValidator.Violation v : validator.validate(schemas.schema(kind), cleaned)) {
      throw ProblemException.unprocessable("Check the settings", v.message(), "/config" + v.pointer());
    }
    try {
      switch (kind) {
        case VAULT -> ai.msrv.gaterook.core.secrets.store.vault.VaultSettings.of(cleaned, clients.corePorts());
        case GCP_SM -> GcpSmStore.Settings.of(cleaned, null);
        case AWS_SM -> ai.msrv.gaterook.core.secrets.store.aws.AwsSmStore.Settings.of(cleaned, null);
      }
    } catch (StoreException e) {
      throw ProblemException.unprocessable("Check the settings", e.getMessage(), "/config");
    }
    return cleaned;
  }

  /** The gateway makes the ExternalId of a role (report 09: {@code gw-ext-} and 12 hex digits). */
  private static void generateExternalId(StoreKind kind, ObjectNode config) {
    if (kind != StoreKind.AWS_SM || !(config.path("credentials") instanceof ObjectNode c)) {
      return;
    }
    if (c.path("roleArn").isString() && !c.path("roleArn").asString().isBlank() && !c.path("externalId").isString()) {
      byte[] b = new byte[6];
      RANDOM.nextBytes(b);
      c.put("externalId", "gw-ext-" + HexFormat.of().formatHex(b));
    }
  }

  /** The sign-in secret fields the settings need. */
  private static Set<String> needed(StoreKind kind, JsonNode config) {
    return switch (kind) {
      case VAULT -> switch (config.path("auth").path("method").asString("approle")) {
        case "approle" -> Set.of(ai.msrv.gaterook.core.secrets.store.vault.VaultSettings.SECRET_ID);
        case "token" -> Set.of(ai.msrv.gaterook.core.secrets.store.vault.VaultSettings.TOKEN);
        default -> Set.of();
      };
      case GCP_SM -> config.path("credentials").path("source").asString("adc").equals("serviceAccountKey")
          ? Set.of(GcpSmStore.KEY) : Set.of();
      case AWS_SM -> config.path("credentials").path("source").asString("accessKey").equals("accessKey")
          ? Set.of(ai.msrv.gaterook.core.secrets.store.aws.AwsSmStore.SECRET_KEY) : Set.of();
    };
  }

  private static void requiredSignIn(StoreKind kind, JsonNode config, Set<String> given, Set<String> stored) {
    for (String field : needed(kind, config)) {
      if (!given.contains(field) && !stored.contains(field)) {
        throw ProblemException.unprocessable("Secret missing", "Enter the " + field.substring(field.lastIndexOf('/')
            + 1) + " the gateway signs in with.", "/secrets/" + field.replace("~", "~0").replace("/", "~1"));
      }
    }
  }

  private static Map<String, char[]> signInValues(StoreKind kind, Map<String, char[]> values) {
    Map<String, char[]> out = new LinkedHashMap<>();
    if (values == null) {
      return out;
    }
    List<String> allowed = StoreSchemas.secretFields(kind);
    for (Map.Entry<String, char[]> e : values.entrySet()) {
      String pointer = "/secrets/" + e.getKey().replace("~", "~0").replace("/", "~1");
      if (!allowed.contains(e.getKey())) {
        throw ProblemException.unprocessable("Not a secret field", e.getKey() + " is not a secret field of "
            + StoreResolution.title(kind) + ".", pointer);
      }
      if (e.getValue() == null || e.getValue().length == 0) {
        throw ProblemException.unprocessable("Secret missing", "Enter a value for " + e.getKey() + ".", pointer);
      }
      out.put(e.getKey(), e.getValue());
    }
    return out;
  }

  private String uniqueName(String store, String field) {
    String display = switch (field) {
      case "/auth/secretId" -> "approle-secret-id";
      case "/auth/token" -> "token";
      case "/credentials/serviceAccountKey" -> "service-account-key";
      case "/credentials/secretAccessKey" -> "access-key";
      default -> "secret";
    };
    String base = store + "/" + display;
    String name = base;
    for (int i = 2; secrets.findByName(name).isPresent(); i++) {
      name = base + "-" + i;
    }
    return name;
  }

  private static String describe(StoreKind kind, JsonNode config) {
    return switch (kind) {
      case VAULT -> config.path("address").asString("") + (config.path("namespace").isString() ? " · namespace "
          + config.path("namespace").asString() : "") + " · " + config.path("auth").path("method").asString("approle");
      case GCP_SM -> "project " + config.path("project").asString("") + (config.path("credentials")
          .path("impersonate").isString() ? " · impersonates " + config.path("credentials").path("impersonate")
          .asString() : "");
      case AWS_SM -> config.path("region").asString("") + (config.path("credentials").path("roleArn").isString()
          ? " · " + config.path("credentials").path("roleArn").asString() : "");
    };
  }

  private String ownerLabel(SecretEntity s) {
    return describeOwner(new SecretOwner(SecretOwner.Kind.valueOf(s.getOwnerKind().name()), s.getOwnerId()))
        .map(SecretOwners.Owner::label).orElse(null);
  }

  private Optional<SecretOwners.Owner> describeOwner(SecretOwner owner) {
    if (owner.kind() == SecretOwner.Kind.STORE) {
      return stores.findById(owner.id()).map(e -> new SecretOwners.Owner(e.getName(), null, List.of()));
    }
    return owners.orderedStream().filter(o -> o.kind() == owner.kind()).findFirst()
        .flatMap(o -> o.describe(owner.id()));
  }

  private ExternalStoreEntity find(String idOrName) {
    if (idOrName == null) {
      throw notFound(null);
    }
    return stores.findById(idOrName).or(() -> stores.findByName(idOrName)).orElseThrow(() -> notFound(idOrName));
  }

  private static ProblemException notFound(String what) {
    return ProblemException.notFound("Store not found", "There is no external store " + what + ".");
  }

  private static StoreKind kind(String id, String pointer) {
    StoreKind k = id == null ? null : StoreSchemas.kind(id);
    if (k == null) {
      throw ProblemException.unprocessable("Unknown store", "A store is vault, gcp-sm or aws-sm.", pointer);
    }
    return k;
  }

  private static void checkName(String name) {
    if (name == null || name.length() > 64 || !NAME.matcher(name).matches()) {
      throw ProblemException.unprocessable("Invalid name", "Use lower-case letters, digits and hyphens, like vault.",
          "/name");
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(JsonNode tree) {
    return json.treeToValue(tree, Map.class);
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

  private static long ms(long since) {
    return (System.nanoTime() - since) / 1_000_000;
  }
}
