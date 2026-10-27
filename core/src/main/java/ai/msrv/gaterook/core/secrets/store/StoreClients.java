package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretOwnerKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.aws.AwsSmStore;
import ai.msrv.gaterook.core.secrets.store.gcp.GcpSmStore;
import ai.msrv.gaterook.core.secrets.store.vault.VaultSettings;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live clients of the connected stores: one {@link SecretStore} per store, built from its row and sign-in secrets
 * when first needed and replaced when either changes (the key is the config's hash and the sign-in versions, not the
 * row version, which also moves with each test). A row that doesn't verify ({@link StoreIntegrity}) is never used.
 */
public final class StoreClients implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(StoreClients.class);

  /** What a client was built from: the store's row as read and checked. */
  public record Row(String id, StoreKind kind, String name, JsonNode config, boolean verified) {}

  private record Live(String key, SecretStore store) {}

  /**
   * Test endpoints of the cloud stores ({@code gateway.cloud.gcp.secretmanager-endpoint},
   * {@code gateway.cloud.aws.secretsmanager-endpoint}); never set in a release.
   */
  public record Endpoints(URI gcpSecretManager, URI awsSecretsManager) {}

  private final ExternalStoreRepository stores;
  private final SecretRepository secrets;
  private final StoreIntegrity integrity;
  private final CloudCredentials cloud;
  private final ObjectProvider<SecretService> secretService;
  private final IntegrityAlerts alerts;
  private final Bootstrap bootstrap;
  private final LocalListener listener;
  private final Endpoints endpoints;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate readTx;
  private final Map<String, Live> live = new ConcurrentHashMap<>();

  public StoreClients(ExternalStoreRepository stores, SecretRepository secrets, StoreIntegrity integrity,
      CloudCredentials cloud, ObjectProvider<SecretService> secretService, IntegrityAlerts alerts, Bootstrap bootstrap,
      LocalListener listener, Endpoints endpoints, JsonMapper json, Clock clock, PlatformTransactionManager tx) {
    this.stores = stores;
    this.secrets = secrets;
    this.integrity = integrity;
    this.cloud = cloud;
    this.secretService = secretService;
    this.alerts = alerts;
    this.bootstrap = bootstrap;
    this.listener = listener;
    this.endpoints = endpoints;
    this.json = json;
    this.clock = clock;
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  /** The core's own ports: a store on this computer may not be the core. */
  public Set<Integer> corePorts() {
    Set<Integer> ports = new HashSet<>();
    for (int p : new int[] {bootstrap.port(), bootstrap.publicPort(), listener.port()}) {
      if (p > 0) {
        ports.add(p);
      }
    }
    return ports;
  }

  /**
   * The client of store {@code id}.
   *
   * @throws StoreException {@code NOT_FOUND} when the store is gone, {@code INVALID} when its row was changed outside
   *                        MCP Gateway or its settings can't be used
   */
  public SecretStore client(String id) {
    Row row = row(id);
    return client(row);
  }

  public SecretStore client(Row row) {
    if (!row.verified()) {
      alerts.report("secret-store", row.id(), row.name(), "external store row");
      throw StoreException.invalid(row.name() + " was changed outside MCP Gateway. Review it in Secrets › External "
          + "stores before it is used again.");
    }
    String key = CanonicalJson.sha256Hex(row.config()) + "|" + row.name() + "|" + signInVersions(row.id());
    Live current = live.get(row.id());
    if (current != null && current.key().equals(key)) {
      return current.store();
    }
    SecretStore built = build(row);
    Live raced = live.compute(row.id(), (k, old) -> {
      if (old != null && old.key().equals(key)) {
        return old;
      }
      if (old != null) {
        close(old.store());
      }
      return new Live(key, built);
    });
    if (raced.store() != built) {
      close(built);
    }
    return raced.store();
  }

  /** The row of store {@code id}, read and checked in one short transaction. */
  public Row row(String id) {
    Row row = readTx.execute(s -> stores.findById(id).map(this::row).orElse(null));
    if (row == null) {
      throw StoreException.notFound("The store was disconnected.");
    }
    return row;
  }

  /** In the caller's transaction. */
  public Row row(ExternalStoreEntity e) {
    return new Row(e.getId(), e.getKind(), e.getName(), json.valueToTree(e.getConfig()), integrity.verifyStore(e));
  }

  /** A new client, not kept: the one {@link #client} keeps, or one of a sign-in version being tested. */
  public SecretStore build(Row row) {
    return build(row, Map.of());
  }

  /**
   * @param versions sign-in fields read at another version than the current one (Replace with "test before"); the
   *                 client's cloud credentials are cached under their own owner key then
   */
  public SecretStore build(Row row, Map<String, Integer> versions) {
    Set<Integer> ports = corePorts();
    String ownerKey = versions.isEmpty() ? row.id() : row.id() + "|test";
    return switch (row.kind()) {
      case VAULT -> new VaultStore(VaultSettings.of(row.config(), ports), field -> signInSecret(row.id(), field,
          versions), clock);
      case GCP_SM -> new GcpSmStore(GcpSmStore.Settings.of(row.config(), endpoints.gcpSecretManager()), cloud,
          new CloudCredentials.Owner(ownerKey, 0, row.name()), () -> resolver(row.id(), Set.of(GcpSmStore.KEY),
          versions));
      case AWS_SM -> new AwsSmStore(AwsSmStore.Settings.of(row.config(), endpoints.awsSecretsManager()), cloud,
          new CloudCredentials.Owner(ownerKey, 0, row.name()), () -> resolver(row.id(), Set.of(AwsSmStore.SECRET_KEY),
          versions), () -> references(row.id()));
    };
  }

  /** Forgets the client of a store (new settings, new sign-in, disconnected). */
  public void drop(String id) {
    Live old = live.remove(id);
    if (old != null) {
      close(old.store());
    }
  }

  /** The references bound to a store now, for the session policy of AWS. */
  List<SecretRef> references(String storeId) {
    List<String> uris = readTx.execute(s -> secrets.findByStoreId(storeId).stream().map(SecretEntity::getRefUri)
        .toList());
    List<SecretRef> out = new ArrayList<>();
    for (String uri : uris == null ? List.<String>of() : uris) {
      try {
        out.add(SecretRef.parse(uri));
      } catch (IllegalArgumentException e) {
        // A reference that no longer parses resolves nothing; it isn't granted either.
      }
    }
    return out;
  }

  private String signInVersions(String storeId) {
    List<SecretEntity> own = readTx.execute(s -> secrets.findByOwnerKindAndOwnerId(SecretOwnerKind.STORE, storeId));
    StringBuilder b = new StringBuilder();
    for (SecretEntity e : own == null ? List.<SecretEntity>of() : own) {
      b.append(e.getField()).append('=').append(e.getCurrentVersion()).append(';');
    }
    return b.toString();
  }

  private ScopedSecretResolver resolver(String storeId, Set<String> fields, Map<String, Integer> versions) {
    // The store's own secrets taint a scratch set: no connector ever sees them, so no call's result can echo them.
    SecretOwner owner = new SecretOwner(SecretOwner.Kind.STORE, storeId);
    if (versions.isEmpty()) {
      return secretService.getObject().resolver(owner, fields, new Taint());
    }
    Map.Entry<String, Integer> v = versions.entrySet().iterator().next();
    return secretService.getObject().resolverWithVersion(owner, fields, new Taint(), v.getKey(), v.getValue());
  }

  private char[] signInSecret(String storeId, String field, Map<String, Integer> versions) {
    try (ScopedSecretResolver r = resolver(storeId, Set.of(field), versions); SecretResolver.Secret s = r.secret(field)) {
      return s.chars().clone();
    } catch (SecretUnavailableException e) {
      throw StoreException.unavailable("The store's sign-in secret can't be used: " + e.getMessage(), e);
    }
  }

  private static void close(SecretStore store) {
    try {
      store.close();
    } catch (RuntimeException e) {
      log.debug("Closing a store client failed: {}", e.getMessage());
    }
  }

  @Override
  public void close() {
    live.values().forEach(l -> close(l.store()));
    live.clear();
  }
}
