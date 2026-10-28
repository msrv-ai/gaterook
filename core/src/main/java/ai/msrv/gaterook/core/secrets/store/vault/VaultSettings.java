package ai.msrv.gaterook.core.secrets.store.vault;

import ai.msrv.gaterook.core.secrets.store.StoreAddress;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The settings of a Vault/OpenBao store (Secrets › External stores, report 12 §4.3): address, namespace, a private CA,
 * how the gateway signs in, how long references stay cached. The sign-in secrets ({@code /auth/secretId} of AppRole,
 * {@code /auth/token}) are secret fields of the store, never here.
 *
 * @param address    {@code https://vault.acme.internal}, checked by {@link StoreAddress}
 * @param namespace  {@code X-Vault-Namespace} (Enterprise, HCP, OpenBao namespaces), or {@code null}
 * @param caCert     PEM of a private CA, or {@code null} for the system's
 * @param authMount  mount of the AppRole method ({@code approle})
 * @param tokenFile  {@code ~/.vault-token} for {@link Auth#CLI_TOKEN}
 * @param renewIncrement what {@code renew-self} asks for ({@code 1h}: "renewed every hour")
 */
public record VaultSettings(URI address, String namespace, String caCert, Auth auth, String authMount, String roleId,
    Path tokenFile, Duration renewIncrement, int cacheTtlSeconds, boolean useSystemProxy) {

  /** How the gateway gets its token. */
  public enum Auth {
    /** {@code auth/<mount>/login} with role ID and the secret ID stored here: the recommended way. */
    APPROLE,
    /** A token stored here. */
    TOKEN,
    /** The token of {@code vault login} on this computer, like gcloud's ADC (usually broader than an AppRole). */
    CLI_TOKEN
  }

  /** The secret field of the AppRole secret ID. */
  public static final String SECRET_ID = "/auth/secretId";
  /** The secret field of a stored token. */
  public static final String TOKEN = "/auth/token";

  private static final Pattern MOUNT = Pattern.compile("[A-Za-z0-9._-]{1,64}(?:/[A-Za-z0-9._-]{1,64}){0,3}");
  private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9._/-]{1,256}");
  private static final Pattern ROLE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
  private static final Pattern INCREMENT = Pattern.compile("([1-9][0-9]{0,4})([smh])");

  /**
   * Reads the store's config as the form writes it.
   *
   * @throws StoreException {@code INVALID} naming the field to fix
   */
  public static VaultSettings of(JsonNode config, Set<Integer> corePorts) {
    URI address = StoreAddress.check(text(config, "address"), corePorts, config.path("allowPlainHttp").asBoolean(false));
    String namespace = text(config, "namespace");
    if (namespace != null && !NAMESPACE.matcher(namespace).matches()) {
      throw StoreException.invalid("The namespace has characters Vault doesn't accept.");
    }
    String ca = text(config, "caCert");
    JsonNode auth = config.path("auth");
    String method = auth.path("method").asString("approle");
    Auth kind = switch (method) {
      case "approle" -> Auth.APPROLE;
      case "token" -> Auth.TOKEN;
      case "cli-token" -> Auth.CLI_TOKEN;
      default -> throw StoreException.invalid("Sign in with approle, token or cli-token.");
    };
    String mount = text(auth, "mount") == null ? "approle" : text(auth, "mount");
    if (!MOUNT.matcher(mount).matches()) {
      throw StoreException.invalid("The AppRole mount is a path like approle.");
    }
    String roleId = text(auth, "roleId");
    if (kind == Auth.APPROLE && (roleId == null || !ROLE_ID.matcher(roleId).matches())) {
      throw StoreException.invalid("Enter the role ID of the AppRole (vault read auth/approle/role/<name>/role-id).");
    }
    Path file = null;
    if (kind == Auth.CLI_TOKEN) {
      String f = text(auth, "file") == null ? "~/.vault-token" : text(auth, "file");
      file = f.startsWith("~/") ? Path.of(System.getProperty("user.home"), f.substring(2)) : Path.of(f);
    }
    Duration increment = duration(text(config, "renewIncrement") == null ? "1h" : text(config, "renewIncrement"));
    int ttl = config.path("cacheTtlSeconds").asInt(60);
    if (ttl < 0 || ttl > 300) {
      throw StoreException.invalid("Keep a resolved value for 0 to 300 seconds.");
    }
    return new VaultSettings(address, namespace, ca, kind, mount, roleId, file, increment, ttl,
        config.path("useSystemProxy").asBoolean(false));
  }

  /** {@code 90s}, {@code 30m}, {@code 1h} → a duration of 1 minute to 24 hours. */
  static Duration duration(String text) {
    var m = INCREMENT.matcher(text);
    if (!m.matches()) {
      throw StoreException.invalid("Write the renewal as a number and s, m or h, like 1h.");
    }
    long n = Long.parseLong(m.group(1));
    Duration d = switch (m.group(2)) {
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      default -> Duration.ofHours(n);
    };
    if (d.compareTo(Duration.ofMinutes(1)) < 0 || d.compareTo(Duration.ofHours(24)) > 0) {
      throw StoreException.invalid("Renew the token for 1 minute to 24 hours at a time.");
    }
    return d;
  }

  /** The secret fields this sign-in needs. */
  public Set<String> secretFields() {
    return switch (auth) {
      case APPROLE -> Set.of(SECRET_ID);
      case TOKEN -> Set.of(TOKEN);
      case CLI_TOKEN -> Set.of();
    };
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || n.asString().isBlank() ? null : n.asString().strip();
  }
}
