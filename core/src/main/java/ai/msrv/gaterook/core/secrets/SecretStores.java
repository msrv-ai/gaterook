package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.core.secrets.store.StoreAdmin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * External secret stores (ARCHITECTURE.md §6 secrets, report 12 §3–§5): Vault/OpenBao, GCP Secret Manager and AWS
 * Secrets Manager under Secrets › External stores, Test connection of a store, Test reference, Replace reference and
 * the short-lived credentials they issue. The public face of {@code secrets.store}; nothing here returns a value — a
 * reference is resolved only for a call, by its connection's {@link ScopedSecretResolver}.
 */
@Service
public class SecretStores {

  /**
   * One store.
   *
   * @param kind       {@code vault}, {@code gcp-sm}, {@code aws-sm}
   * @param config     the non-secret settings, as stored
   * @param state      {@code connected} (its last test passed), {@code failing} or {@code not-checked}
   * @param latencyMs  of the last test since the core started, or {@code null}
   * @param signIn     its own secret fields (AppRole secret ID, token, key) without values
   * @param verified   {@code false}: the row was changed outside GateRook and the store isn't used
   */
  public record StoreInfo(String id, String kind, String name, JsonNode config, String state, Instant lastCheckedAt,
      Long latencyMs, String lastError, List<Reference> references, List<SignInSecret> signIn, int issues,
      boolean verified, long version) {}

  /** A secret that points into a store: a reference or dynamic credentials. */
  public record Reference(String secretId, String name, String ref, String kind, String owner) {}

  /** A secret field of the store's own sign-in, never its value. */
  public record SignInSecret(String field, String secretId, String name, String state, Integer version) {}

  /** What a store test found (report 12 §5): no values, never the length, hash or prefix of one. */
  public record TestResult(boolean ok, long latencyMs, JsonNode server, JsonNode identity, List<Permission> permissions,
      List<Hint> hints, List<Check> checks, String error, String reason) {}

  public record Permission(String ref, List<String> capabilities) {}

  /** {@code severity}: {@code info}, {@code warning}, {@code danger}. */
  public record Hint(String severity, String code, String text) {}

  public record Check(String name, boolean ok, long ms, String detail) {}

  /**
   * Test reference: "Found · latest → v4 · created Oct 16", the type and the key names of a JSON secret.
   *
   * @param reason {@code not-found}, {@code gone}, {@code denied}, {@code unavailable}, {@code invalid},
   *               {@code not-connected}
   */
  public record RefTest(boolean found, String uri, String store, String resolvedVersion, Instant created, String type,
      List<String> keys, long latencyMs, String error, String reason, List<Hint> hints) {}

  /** What disconnecting a store left behind. */
  public record Disconnected(List<String> referencesNotConnected, int leasesRevoked, List<String> secretsDestroyed) {}

  /**
   * A row of Secrets › Short-lived from a store: a Vault database user, an SSH certificate.
   *
   * @param principal the user an SSH certificate is signed for (kit: "Principal deploy"), {@code null} otherwise
   */
  public record Issued(String connection, String mechanism, String identity, Instant lastIssuedAt,
      long lifetimeSeconds, String principal) {}

  /** A source of a connection's secret field in the form, and why it can't be chosen yet. */
  public record Source(String id, String title, boolean available, String reason) {}

  private final StoreAdmin admin;

  public SecretStores(StoreAdmin admin) {
    this.admin = admin;
  }

  public List<StoreInfo> list() {
    return admin.list();
  }

  /** @param idOrName the store's id or its name */
  public StoreInfo get(String idOrName) {
    return admin.get(idOrName);
  }

  /** The form of a kind: its JSON Schema and secret fields. */
  public JsonNode schema(String kind) {
    return admin.schema(kind);
  }

  /**
   * Connects a store; references of its kind that had no store are bound to it.
   *
   * @param secrets values of its sign-in secret fields, zeroed here
   */
  public StoreInfo create(String kind, String name, JsonNode config, Map<String, char[]> secrets, String reason) {
    return admin.create(kind, name, config, secrets, reason);
  }

  /** New settings (and optionally new sign-in values); the store's clients and cached values are dropped. */
  public StoreInfo update(String idOrName, String name, JsonNode config, Map<String, char[]> secrets, long version,
      String reason) {
    return admin.update(idOrName, name, config, secrets, version, reason);
  }

  /** Disconnect: its leases are revoked, its sign-in secrets destroyed, its references left "not connected". */
  public Disconnected delete(String idOrName, String confirmName, String reason) {
    return admin.delete(idOrName, confirmName, reason);
  }

  public TestResult test(String idOrName) {
    return admin.test(idOrName);
  }

  /** The person reviewed a store (and its references) changed outside GateRook and keeps it as stored. */
  public StoreInfo confirmIntegrity(String idOrName, String reason) {
    return admin.confirmIntegrity(idOrName, reason);
  }

  /** Test reference: resolves {@code uri} now, keeps nothing. */
  public RefTest testReference(String uri, String store) {
    return admin.testReference(uri, store);
  }

  /** Replace reference of a stored reference (after its test). */
  public SecretInfo replaceReference(String secretId, String uri, String store, String reason) {
    return admin.replaceReference(secretId, uri, store, reason);
  }

  /** Short-lived credentials the stores issued: Vault database users and SSH certificates. */
  public List<Issued> issued() {
    return admin.issued();
  }

  /** The sources of a secret field: encrypted here, each store kind, Vault dynamic credentials for a login. */
  public List<Source> sources() {
    return admin.sources();
  }
}
