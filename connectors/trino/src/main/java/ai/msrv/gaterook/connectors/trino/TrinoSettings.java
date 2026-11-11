package ai.msrv.gaterook.connectors.trino;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.Glob;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * The settings of one Trino connection ({@code config.schema.json}).
 *
 * @param base          the coordinator, {@code https://trino.example.com:8443}, without a path
 * @param engine        Trino's {@code X-Trino-*} headers, or Presto's {@code X-Presto-*}
 * @param auth          how the coordinator knows the caller
 * @param user          the session user ({@code X-Trino-User}); {@code null} with a JWT that names it
 * @param catalog       the session catalog, or {@code null}
 * @param schema        the session schema, or {@code null}
 * @param catalogs      globs of the catalogs agents may use; empty: every catalog the user may read
 * @param caCertificate a private CA (PEM), or {@code null}
 * @param maxScanBytes  Trino's {@code query_max_scan_physical_bytes} of each statement, or {@code null}
 * @param loopback      whether the coordinator runs on this computer
 */
record TrinoSettings(URI base, Engine engine, Auth auth, String user, String catalog, String schema,
    List<String> catalogs, String caCertificate, Long maxScanBytes, boolean loopback) {

  enum Engine {
    TRINO("X-Trino-", "Trino"), PRESTO("X-Presto-", "Presto");

    final String prefix;
    final String title;

    Engine(String prefix, String title) {
      this.prefix = prefix;
      this.title = title;
    }
  }

  enum Auth { NONE, BASIC, JWT }

  static final String PASSWORD = "/auth/password";
  static final String TOKEN = "/auth/token";
  /** A catalog name as Trino keeps it; a quoted name in SQL may hold more, which no list names. */
  static final Pattern CATALOG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]{0,127}");
  private static final Pattern CATALOG_GLOB = Pattern.compile("[A-Za-z0-9_*?-]{1,128}");
  /** Printable ASCII: the user and the schema travel as header values. */
  private static final Pattern USER = Pattern.compile("[\\x20-\\x7E]{1,255}");

  static TrinoSettings of(ConnectionConfig cfg) throws ConnectorException {
    String url = cfg.string("/url");
    if (url == null || url.isBlank()) {
      throw ConnectorException.badInput("Enter the coordinator's URL, such as https://trino.example.com:8443.");
    }
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url.strip());
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput(e.getMessage() + ".");
    }
    if (!parsed.encodedPath().equals("/") || parsed.query() != null) {
      throw ConnectorException.badInput("Give the coordinator's address only, such as https://trino.example.com:8443: "
          + "Trino's client protocol has no path.");
    }
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(parsed.host());
    if (literal.isPresent()) {
      AddressPolicy.Verdict v = AddressPolicy.builder().build().check(literal.get(), parsed.port());
      if (v.reason() != AddressPolicy.Reason.PUBLIC && v.reason() != AddressPolicy.Reason.PRIVATE
          && v.reason() != AddressPolicy.Reason.ALLOWED_PRIVATE) {
        throw ConnectorException.badInput(v.text() + ".");
      }
    }
    boolean loopback = parsed.host().equalsIgnoreCase("localhost")
        || literal.map(InetAddress::isLoopbackAddress).orElse(false);
    boolean tls = parsed.isHttps();
    String base = parsed.toString();
    URI uri = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);

    Engine engine = "presto".equals(cfg.string("/engine")) ? Engine.PRESTO : Engine.TRINO;
    String mode = cfg.string("/auth/mode");
    Auth auth = switch (mode == null ? "basic" : mode) {
      case "none" -> Auth.NONE;
      case "basic" -> Auth.BASIC;
      case "jwt" -> Auth.JWT;
      default -> throw ConnectorException.badInput("Choose how GateRook signs in to " + engine.title + ": a "
          + "password, a JWT or none.");
    };
    String user = cfg.string("/auth/user");
    if (user != null && user.isBlank()) {
      user = null;
    }
    if (user != null && !USER.matcher(user).matches()) {
      throw ConnectorException.badInput("The user name must be printable ASCII of at most 255 characters.");
    }
    if (user == null && auth != Auth.JWT) {
      throw ConnectorException.badInput("Enter the " + engine.title + " user GateRook runs statements as.");
    }
    Env env = cfg.env() == null ? Env.PROD : cfg.env();
    if (auth == Auth.NONE && !loopback && env != Env.DEV) {
      throw ConnectorException.badInput("Without authentication " + engine.title + " believes any user name: use it "
          + "on dev or for a coordinator on this computer only. On " + env.id() + ", sign in with a password or a "
          + "JWT.");
    }
    if (auth != Auth.NONE && !tls && !loopback) {
      throw ConnectorException.badInput("A password or a token would travel unencrypted over http://: use the "
          + "coordinator's https:// address.");
    }
    String catalog = blankToNull(cfg.string("/catalog"));
    if (catalog != null && !CATALOG.matcher(catalog).matches()) {
      throw ConnectorException.badInput(catalog + " isn't a catalog name: letters, digits, _ and -.");
    }
    String schema = blankToNull(cfg.string("/schema"));
    if (schema != null && catalog == null) {
      throw ConnectorException.badInput("A default schema needs a default catalog.");
    }
    if (schema != null && !USER.matcher(schema).matches()) {
      throw ConnectorException.badInput("The default schema must be printable ASCII of at most 255 characters.");
    }
    List<String> catalogs = List.copyOf(cfg.strings("/catalogs"));
    for (String glob : catalogs) {
      if (!CATALOG_GLOB.matcher(glob).matches()) {
        throw ConnectorException.badInput(glob + " isn't a catalog name or pattern: letters, digits, _, - and *.");
      }
    }
    TrinoSettings s = new TrinoSettings(uri, engine, auth, user, catalog, schema, catalogs,
        blankToNull(cfg.string("/caCertificate")), scanBytes(cfg), loopback);
    if (catalog != null && !s.allows(catalog)) {
      throw ConnectorException.badInput("The default catalog " + catalog + " isn't one of the catalogs agents may "
          + "use: add it to Catalogs or choose another.");
    }
    return s;
  }

  /** {@code advanced.maxScanMb} as bytes, or {@code null}. */
  private static Long scanBytes(ConnectionConfig cfg) {
    var n = cfg.config().at("/advanced/maxScanMb");
    if (n.isMissingNode() || n.isNull() || !n.canConvertToLong()) {
      return null;
    }
    long mb = Math.max(1L, Math.min(n.asLong(), 100L << 20));
    return mb << 20;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /** Whether agents may use {@code catalog}: on the list, or any when the list is empty. Case-insensitive. */
  boolean allows(String catalog) {
    if (catalogs.isEmpty()) {
      return true;
    }
    if (catalog == null) {
      return false;
    }
    String c = catalog.toLowerCase(Locale.ROOT);
    for (String glob : catalogs) {
      if (Glob.text(glob.toLowerCase(Locale.ROOT)).matches(c)) {
        return true;
      }
    }
    return false;
  }

  /** The catalog a name is in: its own, or the session's; {@code null} when it names none and there is none. */
  String catalogOf(TrinoSql.Ref ref) {
    String own = ref.name().catalog(ref.role());
    return own != null ? own : catalog == null ? null : catalog.toLowerCase(Locale.ROOT);
  }

  /** {@code host:port} of the coordinator. */
  String address() {
    int port = base.getPort() >= 0 ? base.getPort() : "https".equals(base.getScheme()) ? 443 : 80;
    return base.getHost() + ":" + port;
  }

  /** The secret this connection signs in with, or {@code null}. */
  String secretField() {
    return switch (auth) {
      case BASIC -> PASSWORD;
      case JWT -> TOKEN;
      case NONE -> null;
    };
  }
}
