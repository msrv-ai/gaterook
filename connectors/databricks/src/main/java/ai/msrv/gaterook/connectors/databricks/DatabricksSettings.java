package ai.msrv.gaterook.connectors.databricks;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.Glob;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The settings of one Databricks connection ({@code config.schema.json}): the workspace, the SQL warehouse, the
 * default catalog and schema of statements, the catalogs and schemas agents may use, and how GateRook signs in.
 *
 * @param base     {@code https://<host>[:port]/}: an https workspace URL whose host is a name, or a loopback address
 * @param catalog  the default catalog (lower case), or {@code null}: the workspace's
 * @param schema   the default schema (lower case), or {@code null}: {@code default}
 * @param catalogs globs of catalogs; empty: every catalog the identity may use
 * @param schemas  globs of {@code schema} or {@code catalog.schema}; empty: every schema the identity may use
 * @param clientId the service principal's client ID with OAuth, else {@code null}
 */
record DatabricksSettings(URI base, String host, String warehouse, String catalog, String schema,
    List<String> catalogs, List<String> schemas, Auth auth, String clientId) {

  /** How GateRook signs in to the workspace. */
  enum Auth {
    /** A personal access token, sent as a bearer token. */
    TOKEN,
    /** OAuth machine-to-machine: the service principal's client ID and secret for a token of the workspace. */
    OAUTH
  }

  /** Databricks' own workspace domains; another https host is possible (a private link or custom domain). */
  static final List<String> DATABRICKS_DOMAINS = List.of(".cloud.databricks.com", ".azuredatabricks.net",
      ".gcp.databricks.com", ".cloud.databricks.us", ".databricks.azure.cn", ".databricks.azure.us");
  static final Pattern WAREHOUSE = Pattern.compile("[A-Za-z0-9-]{1,64}");
  static final Pattern OBJECT = Pattern.compile("[A-Za-z0-9_-]{1,255}");
  static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
  private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  static DatabricksSettings of(ConnectionConfig cfg) throws ConnectorException {
    URI base = base(cfg.string("/host"));
    String warehouse = cfg.string("/warehouseId");
    if (warehouse == null || !WAREHOUSE.matcher(warehouse).matches()) {
      throw ConnectorException.badInput("Enter the SQL warehouse's ID, as its Connection details show it, such as "
          + "1a2b3c4d5e6f7a8b.");
    }
    String catalog = object(cfg.string("/catalog"), "catalog");
    String schema = object(cfg.string("/schema"), "schema");
    List<String> catalogs = lower(cfg.strings("/catalogs"));
    List<String> schemas = lower(cfg.strings("/schemas"));
    String method = cfg.string("/auth/method");
    Auth auth = method == null || method.equals("oauth") ? Auth.OAUTH : method.equals("token") ? Auth.TOKEN : null;
    if (auth == null) {
      throw ConnectorException.badInput("Choose how GateRook signs in: a personal access token or a service "
          + "principal.");
    }
    String clientId = null;
    if (auth == Auth.OAUTH) {
      clientId = cfg.string("/auth/clientId");
      if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
        throw ConnectorException.badInput("Enter the service principal's client ID (its application ID).");
      }
    }
    DatabricksSettings s = new DatabricksSettings(base, base.getHost(), warehouse, catalog, schema, catalogs,
        schemas, auth, clientId);
    if (!catalogs.isEmpty() && (catalog == null || !s.allowsCatalog(catalog))) {
      throw ConnectorException.badInput("Catalogs limits what agents may use: choose a default catalog from that "
          + "list, as statements without a catalog use it.");
    }
    if (!schemas.isEmpty() && (schema == null || !s.allows(catalog, schema))) {
      throw ConnectorException.badInput("Schemas limits what agents may use: choose a default schema from that list, "
          + "as statements without a schema use it.");
    }
    return s;
  }

  /**
   * The workspace URL as {@code https://<host>[:port]/}: https only, no user, path, query or fragment, and a host that
   * is a name, never an IP address but a loopback one.
   */
  static URI base(String text) throws ConnectorException {
    String hint = "Enter the workspace URL, such as https://dbc-a1b2c3d4-e5f6.cloud.databricks.com.";
    if (text == null || text.isBlank()) {
      throw ConnectorException.badInput(hint);
    }
    URI u;
    try {
      u = new URI(text.strip());
    } catch (java.net.URISyntaxException e) {
      throw ConnectorException.badInput(hint);
    }
    if (!"https".equalsIgnoreCase(u.getScheme())) {
      throw ConnectorException.badInput("The workspace URL must start with https://: GateRook sends tokens over "
          + "TLS only.");
    }
    String path = u.getRawPath();
    if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
        || (path != null && !path.isEmpty() && !path.equals("/")) || u.getHost() == null) {
      throw ConnectorException.badInput(hint + " Leave out paths such as /sql/1.0/warehouses: the warehouse has its "
          + "own field.");
    }
    String host = u.getHost().toLowerCase(Locale.ROOT);
    if (!(loopback(host) || name(host))) {
      throw ConnectorException.badInput("The workspace URL needs a host name: an IP address is refused, except a "
          + "loopback one.");
    }
    int port = u.getPort();
    return URI.create("https://" + host + (port > 0 ? ":" + port : "") + "/");
  }

  /** {@code 127.x.x.x} or {@code [::1]}. */
  private static boolean loopback(String host) {
    if (host.equals("[::1]")) {
      return true;
    }
    if (!IPV4.matcher(host).matches()) {
      return false;
    }
    for (String part : host.split("\\.")) {
      if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0"))) {
        return false;
      }
    }
    return host.startsWith("127.");
  }

  /** A DNS name whose last label isn't a number (which would make it an IP address in some readers). */
  private static boolean name(String host) {
    if (host.length() > 253 || host.startsWith("[")) {
      return false;
    }
    String[] labels = host.split("\\.", -1);
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    return !labels[labels.length - 1].chars().allMatch(Character::isDigit);
  }

  private static String object(String value, String what) throws ConnectorException {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!OBJECT.matcher(value).matches()) {
      throw ConnectorException.badInput(value + " isn't a " + what + " name of letters, digits, _ and -.");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static List<String> lower(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String v : values) {
      out.add(v.toLowerCase(Locale.ROOT));
    }
    return List.copyOf(out);
  }

  /** Whether the host is one of Databricks' own domains. */
  boolean databricksDomain() {
    return DATABRICKS_DOMAINS.stream().anyMatch(host::endsWith);
  }

  /** Whether agents may use {@code catalog}: on the list, or any when the list is empty. */
  boolean allowsCatalog(String catalog) {
    return catalogs.isEmpty() || (catalog != null && Glob.anyText(catalogs, catalog.toLowerCase(Locale.ROOT)));
  }

  /**
   * Whether agents may use the schema {@code catalog.schema}: its catalog allowed, and the schema on the list, or any
   * when it is empty. An entry without a catalog matches the schema in every catalog allowed.
   */
  boolean allows(String catalog, String schema) {
    if (!allowsCatalog(catalog)) {
      return false;
    }
    if (schemas.isEmpty()) {
      return true;
    }
    if (schema == null) {
      return false;
    }
    String c = catalog == null ? "" : catalog.toLowerCase(Locale.ROOT);
    String s = schema.toLowerCase(Locale.ROOT);
    for (String entry : schemas) {
      int dot = entry.indexOf('.');
      if (dot < 0 ? Glob.text(entry).matches(s)
          : Glob.text(entry.substring(0, dot)).matches(c) && Glob.text(entry.substring(dot + 1)).matches(s)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the lists limit anything. */
  boolean limited() {
    return !catalogs.isEmpty() || !schemas.isEmpty();
  }

  /** The schema statements use without one: the connection's, else Databricks' {@code default}. */
  String defaultSchema() {
    return schema == null ? "default" : schema;
  }
}
