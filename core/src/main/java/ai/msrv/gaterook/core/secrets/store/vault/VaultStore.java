package ai.msrv.gaterook.core.secrets.store.vault;

import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.RemoteValue;
import ai.msrv.gaterook.core.secrets.store.SecretJson;
import ai.msrv.gaterook.core.secrets.store.SecretRef;
import ai.msrv.gaterook.core.secrets.store.SecretStore;
import ai.msrv.gaterook.core.secrets.store.SshPublicKeys;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Vault (or OpenBao) store (report 12 §5, §6, §7.5; checked against Vault 2.2.1): references into KV v1 and v2,
 * dynamic database credentials with leases, the SSH CA, and Test connection.
 *
 * <p>The token lives in memory only (never in the database): AppRole signs in with the secret ID stored here, a stored
 * token or the {@code vault login} token is looked up. It is renewed after two thirds of its TTL while in use; one
 * that won't renew or reached its maximum TTL is replaced by a new sign-in ahead of time. Vault answers 403 both to a
 * bad token and to a missing permission, so a 403 is followed by {@code lookup-self}: if that fails too, the gateway
 * signs in again and repeats the request once; otherwise the answer is "permission denied".
 *
 * <p>Where a mount ends isn't visible in a reference ({@code vault://team/kv/app/x}): {@code sys/internal/ui/mounts}
 * says, and the answer is cached per path. Every token used for a call goes to the call's taint.
 */
public final class VaultStore implements SecretStore {

  /** A dynamic login of a {@code …/creds/<role>} path: the lease is the core's to renew and revoke. */
  public record Lease(String leaseId, String username, char[] password, Duration ttl, boolean renewable) {
    public void clear() {
      Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
      return "Lease[" + leaseId + ", user=" + username + ", password=‹secret›]";
    }
  }

  /** A signed SSH certificate. */
  public record Signed(String openSsh, String serial, Instant notAfter) {}

  private record Mount(String path, String type, String kvVersion) {}

  private static final JsonFactory JSON = JsonFactory.builder().build();
  private static final int MAX_BROWSE_ENTRIES = 1000;
  private static final int MAX_DISCOVERY_PATHS = 1000;
  private static final int MAX_DISCOVERY_REFERENCES = 5000;
  private static final int MAX_DISCOVERY_DEPTH = 64;
  private static final int MAX_DISCOVERY_ISSUES = 100;

  private final VaultSettings settings;
  private final SignIn signIn;
  private final Clock clock;
  private final VaultHttp http;
  private final ReentrantLock tokenLock = new ReentrantLock();
  private final Map<String, Mount> mounts = new ConcurrentHashMap<>();
  private char[] token;
  private Instant tokenAt;
  private Duration tokenTtl;
  private boolean renewable;

  public VaultStore(VaultSettings settings, SignIn signIn, Clock clock) {
    this.settings = settings;
    this.signIn = signIn;
    this.clock = clock;
    this.http = new VaultHttp(settings);
  }

  @Override
  public Duration cacheTtl() {
    return Duration.ofSeconds(settings.cacheTtlSeconds());
  }

  @Override
  public StoreKind kind() {
    return StoreKind.VAULT;
  }

  public VaultSettings settings() {
    return settings;
  }

  // ── References ──────────────────────────────────────────────────────────────────────────────────────────────

  /** Lists only immediate child names; it never reads secret payloads or issues dynamic credentials. */
  public SecretStores.VaultBrowse browse(String path, StoreCall call) {
    if (path == null || !path.isEmpty() && !browsePath(path)) {
      throw StoreException.invalid("Enter a Vault folder without leading or trailing slashes, spaces or query "
          + "parameters, or leave it empty to list secrets engines.");
    }
    if (path.isEmpty()) {
      return browseMounts(call);
    }
    Mount mount = mount(path + "/", call);
    if (!mount.type().equals("kv")) {
      throw StoreException.invalid("Choose a KV secrets engine to browse stored secrets.");
    }
    String rest = (path + "/").substring(mount.path().length());
    if (rest.endsWith("/")) {
      rest = rest.substring(0, rest.length() - 1);
    }
    String apiPath = mount.path() + ("2".equals(mount.kvVersion()) ? "metadata/" : "") + rest;
    // Use the same normalized root path as `vault kv list`; folders below it already have no trailing slash.
    if (apiPath.endsWith("/")) {
      apiPath = apiPath.substring(0, apiPath.length() - 1);
    }
    String listPath = apiPath;
    try (VaultHttp.Response r = authorized(t -> http.send("LIST", listPath, null, t, call), call)) {
      if (r.status() == 403) {
        throw StoreException.denied("The gateway's token can't list " + apiPath + ". Grant list permission on "
            + "this path, or enter the secret reference manually.");
      }
      if (r.status() == 404) {
        // Vault uses 404 for an empty folder as well as an absent one.
        return new SecretStores.VaultBrowse(path, List.of(), 0);
      }
      if (!r.ok()) {
        throw failure("list " + apiPath, r);
      }
      JsonNode keys = r.tree("data", "keys");
      if (!keys.isArray()) {
        throw StoreException.unavailable("Vault returned an invalid folder listing.", null);
      }
      checkBrowseSize(keys.size());
      List<SecretStores.VaultEntry> entries = new ArrayList<>();
      int unsupported = 0;
      for (JsonNode key : keys) {
        if (!key.isString()) {
          throw StoreException.unavailable("Vault returned an invalid folder listing.", null);
        }
        String name = key.asString();
        boolean directory = name.endsWith("/");
        if (directory) {
          name = name.substring(0, name.length() - 1);
        }
        String child = path + "/" + name;
        if (!SecretRef.VAULT_SEGMENT.matcher(name).matches() || !browsePath(child)) {
          unsupported++;
          continue;
        }
        entries.add(new SecretStores.VaultEntry(name, child, directory));
      }
      return browseResult(path, entries, unsupported);
    }
  }

  private SecretStores.VaultBrowse browseMounts(StoreCall call) {
    try (VaultHttp.Response r = authorized(t -> http.send("GET", "sys/internal/ui/mounts", null, t, call), call)) {
      if (r.status() == 403) {
        throw StoreException.denied("The gateway's token can't list Vault secrets engines. Enter a known folder "
            + "or secret reference manually.");
      }
      if (!r.ok()) {
        throw failure("list secrets engines", r);
      }
      JsonNode engines = r.tree("data", "secret");
      if (!engines.isObject()) {
        throw StoreException.unavailable("Vault returned an invalid secrets engine listing.", null);
      }
      checkBrowseSize(engines.size());
      List<SecretStores.VaultEntry> entries = new ArrayList<>();
      int unsupported = 0;
      for (Map.Entry<String, JsonNode> engine : engines.properties()) {
        if (!engine.getValue().path("type").asString("").equals("kv")) {
          continue;
        }
        String path = engine.getKey();
        if (path.endsWith("/")) {
          path = path.substring(0, path.length() - 1);
        }
        if (!browsePath(path)) {
          unsupported++;
          continue;
        }
        entries.add(new SecretStores.VaultEntry(path, path, true));
      }
      return browseResult("", entries, unsupported);
    }
  }

  private static boolean browsePath(String path) {
    if (path.isEmpty() || path.length() + "vault://".length() > SecretRef.MAX_LENGTH) {
      return false;
    }
    for (String segment : path.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..") || !SecretRef.VAULT_SEGMENT.matcher(segment).matches()) {
        return false;
      }
    }
    return true;
  }

  private static void checkBrowseSize(int size) {
    if (size > MAX_BROWSE_ENTRIES) {
      throw StoreException.invalid("Vault returned more than " + MAX_BROWSE_ENTRIES + " entries. Enter a narrower "
          + "folder or the secret reference manually.");
    }
  }

  private static SecretStores.VaultBrowse browseResult(String path, List<SecretStores.VaultEntry> entries,
      int unsupported) {
    return new SecretStores.VaultBrowse(path, entries.stream().distinct()
        .sorted(Comparator.comparing(SecretStores.VaultEntry::directory).reversed()
            .thenComparing(SecretStores.VaultEntry::name)).toList(), unsupported);
  }

  /**
   * Enumerates the accessible KV tree for autocomplete. Values exist only in a short-lived {@link RemoteValue}
   * whose bytes are cleared before another path is processed; neither values nor a value cache are returned.
   */
  public SecretStores.VaultReferences references(StoreCall call) {
    record Path(String path, boolean directory, int depth) {}
    var pending = new ArrayDeque<Path>();
    var scheduled = new HashSet<String>();
    var references = new TreeSet<String>();
    var issues = new ArrayList<SecretStores.VaultDiscoveryIssue>();
    pending.add(new Path("", true, 0));
    scheduled.add("D:");
    boolean pathLimit = false;
    while (!pending.isEmpty()) {
      Path next = pending.removeFirst();
      if (issues.size() >= MAX_DISCOVERY_ISSUES) {
        issues.add(new SecretStores.VaultDiscoveryIssue(next.path(), "limit", "Discovery stopped after "
            + MAX_DISCOVERY_ISSUES + " inaccessible or unsupported paths. The suggestions are incomplete."));
        break;
      }
      try {
        call.requestTimeout();
        if (next.directory()) {
          SecretStores.VaultBrowse folder = browse(next.path(), call);
          if (folder.unsupportedEntries() > 0) {
            issues.add(new SecretStores.VaultDiscoveryIssue(next.path(), "unsupported", "Some names in this "
                + "folder cannot be used in GateRook references and were omitted."));
          }
          for (SecretStores.VaultEntry entry : folder.entries()) {
            String identity = (entry.directory() ? "D:" : "S:") + entry.path();
            if (scheduled.contains(identity)) {
              continue;
            }
            if (next.depth() >= MAX_DISCOVERY_DEPTH || scheduled.size() >= MAX_DISCOVERY_PATHS) {
              if (!pathLimit) {
                issues.add(new SecretStores.VaultDiscoveryIssue(next.path(), "limit", "The Vault tree exceeds "
                    + "the discovery limit of " + MAX_DISCOVERY_PATHS + " paths or " + MAX_DISCOVERY_DEPTH
                    + " folder levels. The suggestions are incomplete."));
                pathLimit = true;
              }
              continue;
            }
            scheduled.add(identity);
            pending.addLast(new Path(entry.path(), entry.directory(), next.depth() + 1));
          }
        } else {
          if (!mount(next.path(), call).type().equals("kv")) {
            throw StoreException.invalid("Only Vault KV paths can supply reference suggestions.");
          }
          try (RemoteValue value = read(new SecretRef.Vault(next.path(), null, null), call)) {
            boolean unsupported = false;
            for (String key : value.keys()) {
              String reference;
              try {
                SecretRef.Vault parsed = (SecretRef.Vault) SecretRef.parse("vault://" + next.path() + "#" + key);
                if (!key.equals(parsed.key()) || parsed.version() != null) {
                  unsupported = true;
                  continue;
                }
                reference = parsed.uri();
              } catch (IllegalArgumentException invalid) {
                unsupported = true;
                continue;
              }
              if (references.size() >= MAX_DISCOVERY_REFERENCES && !references.contains(reference)) {
                issues.add(new SecretStores.VaultDiscoveryIssue(next.path(), "limit", "Vault contains more than "
                    + MAX_DISCOVERY_REFERENCES + " selectable keys. The suggestions are incomplete."));
                return new SecretStores.VaultReferences(List.copyOf(references), false, List.copyOf(issues));
              }
              references.add(reference);
            }
            if (unsupported) {
              issues.add(new SecretStores.VaultDiscoveryIssue(next.path(), "unsupported", "Some key names in "
                  + "this secret cannot be used in GateRook references and were omitted."));
            }
          }
        }
      } catch (StoreException error) {
        issues.add(new SecretStores.VaultDiscoveryIssue(next.path(),
            error.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), error.getMessage()));
        if (error.reason() == StoreException.Reason.UNAVAILABLE) {
          break;
        }
      } catch (IllegalArgumentException invalid) {
        issues.add(new SecretStores.VaultDiscoveryIssue(next.path(), "invalid",
            "Vault returned a secret response that GateRook could not read."));
      }
    }
    return new SecretStores.VaultReferences(List.copyOf(references), issues.isEmpty(), List.copyOf(issues));
  }

  @Override
  public RemoteValue read(SecretRef reference, StoreCall call) {
    SecretRef.Vault ref = (SecretRef.Vault) reference;
    Mount mount = mount(ref.path(), call);
    String rest = ref.path().substring(mount.path().length());
    if (rest.isEmpty()) {
      throw StoreException.invalid("vault://" + ref.path() + " names a secrets engine, not a secret in it.");
    }
    if (mount.type().equals("database") || ref.credsPath() && !mount.type().equals("kv")) {
      throw StoreException.invalid("vault://" + ref.path() + " cannot be used as a secret reference. "
          + "Use a Vault KV path and key, such as vault://kv/dev/db#password.");
    }
    boolean kv2 = mount.type().equals("kv") && "2".equals(mount.kvVersion());
    if (!kv2 && ref.version() != null) {
      throw StoreException.invalid(mount.path() + " is a KV version 1 engine: its secrets have no versions. Remove "
          + "?version= from the reference.");
    }
    String apiPath = kv2 ? mount.path() + "data/" + rest + (ref.version() == null ? "" : "?version=" + ref.version())
        : ref.path();
    String readPath = kv2 ? mount.path() + "data/" + rest : ref.path();
    try (VaultHttp.Response r = authorized(t -> http.send("GET", apiPath, null, t, call), call)) {
      if (r.status() == 404) {
        JsonNode meta = r.tree("data", "metadata");
        String which = (meta.path("version").isNumber() ? "Version " + meta.path("version").asInt() : ref.version()
            != null ? "Version " + ref.version() : "The latest version") + " of " + ref.path();
        if (meta.path("destroyed").asBoolean(false)) {
          throw StoreException.gone(which + " is destroyed.");
        }
        if (!meta.path("deletion_time").asString("").isEmpty()) {
          throw StoreException.gone(which + " is deleted.");
        }
        throw StoreException.notFound("No secret at " + ref.path() + ".");
      }
      if (r.status() == 403) {
        throw StoreException.denied("The gateway's token can't read " + readPath + ".");
      }
      if (!r.ok()) {
        throw failure("read " + readPath, r);
      }
      JsonNode meta = kv2 ? r.tree("data", "metadata") : JsonNodeFactory.instance.missingNode();
      List<String> path = kv2 ? List.of("data", "data") : List.of("data");
      SecretJson.Picked picked = SecretJson.pick(r.body(), path, ref.key());
      if (!picked.present()) {
        if (kv2 && !meta.path("deletion_time").asString("").isEmpty()) {
          throw StoreException.gone("Version " + meta.path("version").asInt() + " of " + ref.path() + " is deleted.");
        }
        throw StoreException.notFound("No secret at " + ref.path() + ".");
      }
      if (picked.value() == null) {
        throw StoreException.notFound(ref.path() + " has no key \"" + ref.key() + "\". Keys: "
            + String.join(", ", picked.keys()));
      }
      String version = kv2 && meta.path("version").isNumber() ? String.valueOf(meta.path("version").asInt()) : null;
      return new RemoteValue(picked.value(), version, instant(meta.path("created_time").asString(null)),
          ref.key() == null ? "json" : picked.string() ? "string" : "json", picked.keys());
    }
  }

  /** The engine an API path belongs to, from Vault ({@code sys/internal/ui/mounts}), cached per mount. */
  private Mount mount(String path, StoreCall call) {
    for (Mount m : mounts.values()) {
      if (path.startsWith(m.path())) {
        return m;
      }
    }
    try (VaultHttp.Response r = authorized(t -> http.send("GET", "sys/internal/ui/mounts/" + path, null, t, call),
        call)) {
      if (r.status() == 403) {
        // No capability on any path inside the mount: the reference can't be read, whatever the engine is.
        throw StoreException.denied("The gateway's token can't read " + path + ".");
      }
      if (r.status() == 400 || r.status() == 404) {
        throw StoreException.notFound("Vault has no secrets engine at " + path + ".");
      }
      if (!r.ok()) {
        throw failure("look up the engine of " + path, r);
      }
      JsonNode data = r.tree("data");
      String mountPath = data.path("path").asString("");
      if (mountPath.isEmpty() || !path.startsWith(mountPath)) {
        throw StoreException.notFound("Vault has no secrets engine at " + path + ".");
      }
      Mount m = new Mount(mountPath, data.path("type").asString(""), data.path("options").path("version")
          .asString("1"));
      mounts.put(mountPath, m);
      return m;
    }
  }

  // ── Dynamic credentials ─────────────────────────────────────────────────────────────────────────────────────

  /**
   * New credentials from {@code vault://<mount>/creds/<role>} (report 12 §6, report 04 §6.7); the caller keeps the
   * lease and the password in memory and zeroes the password.
   */
  public Lease issue(SecretRef.Vault ref, StoreCall call) {
    Mount mount = mount(ref.path(), call);
    if (!mount.type().equals("database")) {
      throw StoreException.invalid("vault://" + ref.path() + " isn't a database secrets engine (it is "
          + (mount.type().isEmpty() ? "unknown" : mount.type()) + "): short-lived logins come from database/creds.");
    }
    try (VaultHttp.Response r = authorized(t -> http.send("GET", ref.path(), null, t, call), call)) {
      if (r.status() == 403) {
        throw StoreException.denied("The gateway's token can't read " + ref.path() + ".");
      }
      if (r.status() == 400 || r.status() == 404) {
        throw StoreException.notFound("Vault has no role at " + ref.path() + ".");
      }
      if (!r.ok()) {
        throw failure("issue credentials from " + ref.path(), r);
      }
      JsonNode root = r.tree();
      String leaseId = root.path("lease_id").asString("");
      SecretJson.Picked user = SecretJson.pick(r.body(), List.of("data"), "username");
      SecretJson.Picked pass = SecretJson.pick(r.body(), List.of("data"), "password");
      if (leaseId.isEmpty() || user.value() == null || pass.value() == null) {
        user.clear();
        pass.clear();
        throw StoreException.invalid(ref.path() + " didn't return a lease with a username and password.");
      }
      String username = new String(user.value(), java.nio.charset.StandardCharsets.UTF_8);
      char[] password = chars(pass.value());
      pass.clear();
      user.clear();
      call.taint().accept(CharBuffer.wrap(password));
      return new Lease(leaseId, username, password, Duration.ofSeconds(root.path("lease_duration").asLong(0)),
          root.path("renewable").asBoolean(false));
    }
  }

  /**
   * Whether {@code ref} is a database role the gateway's token may read, without issuing credentials (Test reference
   * would otherwise create a database user).
   *
   * @return what the role is, for the hint
   */
  public String probe(SecretRef.Vault ref, StoreCall call) {
    Mount mount = mount(ref.path(), call);
    if (!mount.type().equals("database")) {
      throw StoreException.invalid("vault://" + ref.path() + " isn't a database secrets engine (it is "
          + (mount.type().isEmpty() ? "unknown" : mount.type()) + ").");
    }
    List<String> caps = capabilities(List.of(ref.path()), call).getOrDefault(ref.path(), List.of());
    if (!caps.contains("read") && !caps.contains("root")) {
      throw StoreException.denied("The gateway's token can't read " + ref.path() + ".");
    }
    return "database engine " + mount.path();
  }

  /** Renews a lease by {@code increment}; returns the lease's new duration. */
  public Duration renew(String leaseId, Duration increment, StoreCall call) {
    byte[] body = json(g -> {
      g.writeStringProperty("lease_id", leaseId);
      g.writeNumberProperty("increment", increment.toSeconds());
    });
    try (VaultHttp.Response r = authorized(t -> http.send("PUT", "sys/leases/renew", body, t, call), call)) {
      if (!r.ok()) {
        throw failure("renew the lease of " + leasePath(leaseId), r);
      }
      return Duration.ofSeconds(r.tree().path("lease_duration").asLong(0));
    }
  }

  /**
   * Revokes a lease in the form with the id in the path: a narrow rule ({@code sys/leases/revoke/<role path>/*})
   * allows it, while the form with a body needs broad access (report 12 §6, checked on 2.2.1).
   */
  public void revoke(String leaseId, StoreCall call) {
    try (VaultHttp.Response r = authorized(t -> http.send("PUT", "sys/leases/revoke/" + leaseId, null, t, call),
        call)) {
      if (r.status() == 403) {
        throw StoreException.denied("The gateway's token can't revoke leases of " + leasePath(leaseId) + ": allow "
            + "update on sys/leases/revoke/" + leasePath(leaseId) + "/*.");
      }
      if (!r.ok() && r.status() != 404) {
        throw failure("revoke a lease of " + leasePath(leaseId), r);
      }
    }
  }

  private static String leasePath(String leaseId) {
    int last = leaseId.lastIndexOf('/');
    return last > 0 ? leaseId.substring(0, last) : leaseId;
  }

  // ── SSH CA ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Signs {@code key} with {@code <mount>/sign/<role>} for one call (report 06, report 12 §7.5). A role without
   * {@code allow_user_key_ids} refuses a key ID; then it is signed without one, and the certificate's serial stays the
   * link to the call.
   */
  public Signed sign(String mount, String role, PublicKey key, String principal, Duration ttl, String keyId,
      Map<String, String> extensions, StoreCall call) {
    String openSsh;
    try {
      openSsh = SshPublicKeys.openSsh(key);
    } catch (IllegalArgumentException e) {
      throw StoreException.invalid(e.getMessage());
    }
    String path = mount + "/sign/" + role;
    VaultHttp.Response r = signOnce(path, openSsh, principal, ttl, keyId, extensions, call);
    try {
      if (r.status() == 400 && keyId != null && r.errors().contains("key_id")) {
        r.close();
        r = signOnce(path, openSsh, principal, ttl, null, extensions, call);
      }
      if (r.status() == 403) {
        throw StoreException.denied("The gateway's token can't sign with " + path + ".");
      }
      if (r.status() == 404) {
        throw StoreException.notFound("Vault has no SSH role at " + path + ".");
      }
      if (!r.ok()) {
        throw failure("sign a certificate with " + path, r);
      }
      JsonNode data = r.tree("data");
      String cert = data.path("signed_key").asString("");
      if (cert.isEmpty()) {
        throw StoreException.invalid(path + " returned no certificate.");
      }
      return new Signed(cert.strip(), data.path("serial_number").asString(null), clock.instant().plus(ttl));
    } finally {
      r.close();
    }
  }

  private VaultHttp.Response signOnce(String path, String openSsh, String principal, Duration ttl, String keyId,
      Map<String, String> extensions, StoreCall call) {
    byte[] body = json(g -> {
      g.writeStringProperty("public_key", openSsh);
      g.writeStringProperty("valid_principals", principal);
      g.writeStringProperty("ttl", ttl.toSeconds() + "s");
      g.writeStringProperty("cert_type", "user");
      if (keyId != null) {
        g.writeStringProperty("key_id", keyId);
      }
      if (extensions != null && !extensions.isEmpty()) {
        g.writeObjectPropertyStart("extensions");
        for (Map.Entry<String, String> e : extensions.entrySet()) {
          g.writeStringProperty(e.getKey(), e.getValue());
        }
        g.writeEndObject();
      }
    });
    return authorized(t -> http.send("POST", path, body, t, call), call);
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public SecretStores.TestResult test(List<SecretRef> refs, StoreCall call) {
    long t0 = System.nanoTime();
    List<SecretStores.Check> checks = new ArrayList<>();
    List<SecretStores.Hint> hints = new ArrayList<>();
    ObjectNode server = JsonNodeFactory.instance.objectNode();
    ObjectNode identity = JsonNodeFactory.instance.objectNode();
    List<SecretStores.Permission> permissions = new ArrayList<>();
    try {
      long s = System.nanoTime();
      try (VaultHttp.Response h = http.send("GET", "sys/health", null, null, call)) {
        JsonNode health = h.tree();
        server.put("version", health.path("version").asString(null));
        server.put("sealed", health.path("sealed").asBoolean(false));
        server.put("standby", health.path("standby").asBoolean(false));
        if (h.status() == 503 || health.path("sealed").asBoolean(false)) {
          throw StoreException.unavailable("Vault is sealed: unseal it, then test again.", null);
        }
        if (h.status() == 501) {
          throw StoreException.unavailable("Vault isn't initialized yet.", null);
        }
        checks.add(new SecretStores.Check("health", true, ms(s), "Vault-compatible · "
            + health.path("version").asString("unknown")));
      }
      s = System.nanoTime();
      JsonNode self = lookupSelf(call);
      identity.put("auth", switch (settings.auth()) {
        case APPROLE -> "approle";
        case TOKEN -> "token";
        case CLI_TOKEN -> "cli-token";
      });
      if (self.path("meta").path("role_name").isString()) {
        identity.put("role", self.path("meta").path("role_name").asString());
      }
      identity.set("policies", self.path("policies").deepCopy());
      long ttl = self.path("ttl").asLong(0);
      identity.put("ttl", ttl == 0 ? "never expires" : humanize(Duration.ofSeconds(ttl)));
      identity.put("renewable", self.path("renewable").asBoolean(false));
      checks.add(new SecretStores.Check("sign-in", true, ms(s), switch (settings.auth()) {
        case APPROLE -> "Signed in with AppRole " + identity.path("role").asString(settings.roleId());
        case TOKEN -> "The stored token works";
        case CLI_TOKEN -> "The token of vault login works";
      }));
      if (settings.auth() == VaultSettings.Auth.CLI_TOKEN) {
        hints.add(new SecretStores.Hint("warning", "store.personal-token", "This is your own Vault token, usually "
            + "broader than the gateway needs. Prefer an AppRole with a read-only policy."));
      }
      if (ttl == 0 && self.path("policies").toString().contains("\"root\"")) {
        hints.add(new SecretStores.Hint("danger", "store.root-token", "This is a root token: the gateway could do "
            + "anything in Vault. Give it an AppRole with a read-only policy."));
      }
      List<String> paths = new ArrayList<>();
      Map<String, SecretRef.Vault> byPath = new LinkedHashMap<>();
      for (SecretRef r : refs) {
        if (r instanceof SecretRef.Vault v) {
          String p = apiPath(v, call);
          paths.add(p);
          byPath.put(p, v);
        }
      }
      if (!paths.isEmpty()) {
        s = System.nanoTime();
        Map<String, List<String>> caps = capabilities(paths, call);
        for (Map.Entry<String, SecretRef.Vault> e : byPath.entrySet()) {
          List<String> c = caps.getOrDefault(e.getKey(), List.of());
          permissions.add(new SecretStores.Permission(e.getValue().uri(), c));
          if (c.stream().anyMatch(Set.of("create", "update", "delete", "sudo", "root")::contains)) {
            hints.add(new SecretStores.Hint("warning", "store.can-write", "This token can write " + e.getKey()
                + ". Give the gateway a read-only policy."));
          }
          if (c.contains("deny") || c.isEmpty()) {
            hints.add(new SecretStores.Hint("danger", "store.cant-read", "This token can't read " + e.getKey() + "."));
          }
        }
        checks.add(new SecretStores.Check("capabilities", true, ms(s), paths.size() + " reference(s) checked"));
      }
      return new SecretStores.TestResult(true, ms(t0), server, identity, permissions, hints, checks, null, null);
    } catch (StoreException e) {
      checks.add(new SecretStores.Check("failed", false, ms(t0), e.getMessage()));
      return new SecretStores.TestResult(false, ms(t0), server, identity, permissions, hints, checks, e.getMessage(),
          e.reason().name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  /** The API path Vault checks capabilities on: KV v2 secrets live under {@code <mount>data/}. */
  private String apiPath(SecretRef.Vault ref, StoreCall call) {
    Mount m;
    try {
      m = mount(ref.path(), call);
    } catch (StoreException e) {
      return ref.path();
    }
    if (m.type().equals("kv") && "2".equals(m.kvVersion())) {
      return m.path() + "data/" + ref.path().substring(m.path().length());
    }
    return ref.path();
  }

  private Map<String, List<String>> capabilities(List<String> paths, StoreCall call) {
    byte[] body = json(g -> {
      g.writeArrayPropertyStart("paths");
      for (String p : paths) {
        g.writeString(p);
      }
      g.writeEndArray();
    });
    try (VaultHttp.Response r = authorized(t -> http.send("POST", "sys/capabilities-self", body, t, call), call)) {
      if (!r.ok()) {
        throw failure("check the token's capabilities", r);
      }
      JsonNode tree = r.tree();
      JsonNode data = tree.path("data").isObject() ? tree.path("data") : tree;
      Map<String, List<String>> out = new LinkedHashMap<>();
      for (String p : paths) {
        JsonNode c = data.path(p);
        if (c.isMissingNode() && paths.size() == 1) {
          c = data.path("capabilities");
        }
        List<String> list = new ArrayList<>();
        c.forEach(x -> list.add(x.asString()));
        out.put(p, List.copyOf(list));
      }
      return out;
    }
  }

  private JsonNode lookupSelf(StoreCall call) {
    try (VaultHttp.Response r = authorized(t -> http.send("GET", "auth/token/lookup-self", null, t, call), call)) {
      if (!r.ok()) {
        throw failure("look up the gateway's token", r);
      }
      return r.tree("data");
    }
  }

  // ── Token ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Runs {@code request} with the current token (signing in or renewing first when due); a 403 that also fails
   * {@code lookup-self} means the token went bad: sign in again and repeat once.
   */
  private VaultHttp.Response authorized(Function<char[], VaultHttp.Response> request, StoreCall call) {
    char[] t = current(call);
    VaultHttp.Response r = request.apply(t);
    if (r.status() != 403) {
      return r;
    }
    boolean tokenWorks;
    try (VaultHttp.Response self = http.send("GET", "auth/token/lookup-self", null, t, call)) {
      tokenWorks = self.ok();
    }
    if (tokenWorks || settings.auth() == VaultSettings.Auth.TOKEN) {
      if (!tokenWorks) {
        r.close();
        throw StoreException.unavailable("Vault refused the stored token: it expired or was revoked. Replace it under "
            + "the store's sign-in.", null);
      }
      return r;
    }
    r.close();
    forget(t);
    return request.apply(current(call));
  }

  private char[] current(StoreCall call) {
    tokenLock.lock();
    try {
      Instant now = clock.instant();
      if (token != null && tokenTtl != null && !tokenTtl.isZero()) {
        Duration age = Duration.between(tokenAt, now);
        if (age.compareTo(tokenTtl.minus(tokenTtl.dividedBy(10))) >= 0) {
          drop();
        } else if (renewable && age.compareTo(tokenTtl.multipliedBy(2).dividedBy(3)) >= 0) {
          renewSelf(call);
        }
      }
      if (token == null) {
        login(call);
      }
      call.taint().accept(CharBuffer.wrap(token));
      return token;
    } finally {
      tokenLock.unlock();
    }
  }

  private void renewSelf(StoreCall call) {
    byte[] body = json(g -> g.writeStringProperty("increment", settings.renewIncrement().toSeconds() + "s"));
    try (VaultHttp.Response r = http.send("POST", "auth/token/renew-self", body, token, call)) {
      if (!r.ok()) {
        drop();
        return;
      }
      Duration granted = Duration.ofSeconds(r.tree("auth").path("lease_duration").asLong(0));
      tokenAt = clock.instant();
      tokenTtl = granted;
      // A token capped by its max TTL gets less than it asked for: sign in again once that runs low.
      renewable = r.tree("auth").path("renewable").asBoolean(false) && granted.compareTo(Duration.ofMinutes(1)) > 0;
    } catch (StoreException e) {
      drop();
    }
  }

  private void login(StoreCall call) {
    switch (settings.auth()) {
      case APPROLE -> {
        char[] secretId = signIn.secret(VaultSettings.SECRET_ID);
        byte[] body;
        try {
          body = json(g -> {
            g.writeStringProperty("role_id", settings.roleId());
            g.writeName("secret_id");
            g.writeString(secretId, 0, secretId.length);
          });
        } finally {
          Arrays.fill(secretId, '\0');
        }
        try (VaultHttp.Response r = http.send("POST", "auth/" + settings.authMount() + "/login", body, null, call)) {
          if (r.status() == 400 || r.status() == 403) {
            throw StoreException.unavailable("Vault refused the AppRole sign-in: check the role ID and the secret ID "
                + "(it may have expired or used up its uses).", null);
          }
          if (!r.ok()) {
            throw failure("sign in with AppRole", r);
          }
          SecretJson.Picked picked = SecretJson.pick(r.body(), List.of("auth"), "client_token");
          if (picked.value() == null) {
            throw StoreException.unavailable("Vault's AppRole sign-in returned no token.", null);
          }
          token = chars(picked.value());
          picked.clear();
          JsonNode auth = r.tree("auth");
          tokenAt = clock.instant();
          tokenTtl = Duration.ofSeconds(auth.path("lease_duration").asLong(0));
          renewable = auth.path("renewable").asBoolean(false);
        } finally {
          Arrays.fill(body, (byte) 0);
        }
      }
      case TOKEN -> adopt(signIn.secret(VaultSettings.TOKEN), call);
      case CLI_TOKEN -> {
        byte[] file;
        try {
          file = Files.readAllBytes(settings.tokenFile());
        } catch (NoSuchFileException e) {
          throw StoreException.unavailable("There is no " + settings.tokenFile() + " on this computer: run vault login, "
              + "then test again.", null);
        } catch (IOException e) {
          throw StoreException.unavailable("Can't read " + settings.tokenFile() + ": " + e.getMessage(), e);
        }
        int len = file.length;
        while (len > 0 && Character.isWhitespace(file[len - 1])) {
          len--;
        }
        char[] t = chars(Arrays.copyOf(file, len));
        Arrays.fill(file, (byte) 0);
        adopt(t, call);
      }
      default -> throw new IllegalStateException();
    }
  }

  /** A token given to the gateway: its TTL comes from {@code lookup-self}. */
  private void adopt(char[] given, StoreCall call) {
    try (VaultHttp.Response r = http.send("GET", "auth/token/lookup-self", null, given, call)) {
      if (r.status() == 403) {
        Arrays.fill(given, '\0');
        throw StoreException.unavailable("Vault refused the token: it expired or was revoked.", null);
      }
      if (!r.ok()) {
        Arrays.fill(given, '\0');
        throw failure("look up the token", r);
      }
      JsonNode data = r.tree("data");
      token = given;
      tokenAt = clock.instant();
      tokenTtl = Duration.ofSeconds(data.path("ttl").asLong(0));
      renewable = data.path("renewable").asBoolean(false);
    }
  }

  private void forget(char[] stale) {
    tokenLock.lock();
    try {
      if (token == stale) {
        drop();
      }
    } finally {
      tokenLock.unlock();
    }
  }

  private void drop() {
    if (token != null) {
      Arrays.fill(token, '\0');
    }
    token = null;
    tokenAt = null;
    tokenTtl = null;
  }

  @Override
  public void close() {
    tokenLock.lock();
    try {
      drop();
    } finally {
      tokenLock.unlock();
    }
    http.close();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static StoreException failure(String what, VaultHttp.Response r) {
    String errors = r.errors();
    if (r.status() == 503) {
      return StoreException.unavailable("Vault is sealed or unavailable: it couldn't " + what + ".", null);
    }
    if (r.status() == 429) {
      return StoreException.unavailable("Vault is rate limiting the gateway: it couldn't " + what + ".", null);
    }
    return new StoreException(r.status() >= 500 ? StoreException.Reason.UNAVAILABLE : StoreException.Reason.INVALID,
        "Vault couldn't " + what + " (HTTP " + r.status() + (errors.isEmpty() ? "" : ": " + errors) + ").");
  }

  private static byte[] json(java.util.function.Consumer<JsonGenerator> fields) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator g = JSON.createGenerator(ObjectWriteContext.empty(), out)) {
      g.writeStartObject();
      fields.accept(g);
      g.writeEndObject();
    }
    return out.toByteArray();
  }

  private static char[] chars(byte[] utf8) {
    CharBuffer decoded = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(utf8));
    char[] out = new char[decoded.remaining()];
    decoded.get(out);
    if (decoded.hasArray()) {
      Arrays.fill(decoded.array(), '\0');
    }
    return out;
  }

  private static Instant instant(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static long ms(long since) {
    return (System.nanoTime() - since) / 1_000_000;
  }

  static String humanize(Duration d) {
    if (d.toHours() > 0 && d.toMinutesPart() == 0) {
      return d.toHours() + "h";
    }
    if (d.toMinutes() > 0 && d.toSecondsPart() == 0) {
      return d.toMinutes() + "m";
    }
    return d.toSeconds() + "s";
  }
}
