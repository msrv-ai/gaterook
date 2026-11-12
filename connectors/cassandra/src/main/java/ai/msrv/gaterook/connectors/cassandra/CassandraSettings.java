package ai.msrv.gaterook.connectors.cassandra;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.Glob;
import ai.msrv.gaterook.connectors.support.Pem;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The settings of one Cassandra connection ({@code config.schema.json}).
 *
 * @param contactPoints   the nodes the driver first connects to
 * @param localDataCenter the data center whose nodes serve every request (the driver's {@code local-datacenter});
 *                        {@code null} only in a draft of the form ({@link #draft}), whose own session infers it
 * @param keyspace        the session's keyspace, used by statements that don't name one; or {@code null}
 * @param keyspaces       globs of keyspace names agents may use; empty: every keyspace but the system ones
 * @param user            the role GateRook signs in as, or {@code null} for a cluster without sign-in
 * @param tls             TLS to every node; with {@link #autoTls} the detected transport replaces it
 *                        ({@link #withTls}) before a session opens
 * @param autoTls         Connection type Auto: {@link AutoTls} finds the transport before a credential is sent
 * @param verifyHostname  whether a node's certificate must carry its address (or {@link #tlsServerName})
 * @param tlsServerName   the name every node's certificate must carry, or {@code null}: each node's own address
 * @param caCertificates  a private CA; empty: the platform's CAs
 * @param readConsistency the consistency of reads ({@code LOCAL_ONE} or {@code LOCAL_QUORUM})
 * @param allowFiltering  {@code ALLOW FILTERING} on dev; never anywhere else
 */
record CassandraSettings(List<ContactPoint> contactPoints, String localDataCenter, String keyspace,
    List<String> keyspaces, String user, boolean tls, boolean autoTls, boolean verifyHostname, String tlsServerName,
    List<X509Certificate> caCertificates, String readConsistency, boolean allowFiltering,
    Duration statementTimeout, Duration connectTimeout, int connectionsPerNode) {

  /** One {@code host:port} of {@code contactPoints}. */
  record ContactPoint(String host, int port) {

    @Override
    public String toString() {
      return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
  }

  static final String PASSWORD = "/credentials/password";
  static final String USER = "/credentials/user";
  static final int DEFAULT_PORT = 9042;
  static final int MAX_CONTACT_POINTS = 20;
  static final Set<String> CONSISTENCIES = Set.of("LOCAL_ONE", "LOCAL_QUORUM");

  /** Cassandra's keyspace names: letters, digits and underscores, at most 48 characters. */
  static final Pattern KEYSPACE = Pattern.compile("[A-Za-z0-9_]{1,48}");
  private static final Pattern HOST = Pattern.compile(
      "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
  private static final Pattern CONTACT_POINT = Pattern.compile("(\\[[0-9A-Fa-f:.]+\\]|[^:\\[\\]]+)(?::(\\d{1,5}))?");
  private static final Pattern GLOB = Pattern.compile("[A-Za-z0-9_*?]{1,48}");

  /**
   * The keyspaces Cassandra itself keeps, as the driver names them ({@code advanced.metadata.schema
   * .refreshed-keyspaces}): {@code system}, {@code system_*}, {@code dse_*}, {@code solr_admin}, {@code OpsCenter}.
   */
  static boolean systemKeyspace(String keyspace) {
    return keyspace.equals("system") || keyspace.startsWith("system_") || keyspace.startsWith("dse_")
        || keyspace.equals("solr_admin") || keyspace.equals("OpsCenter");
  }

  /**
   * Keyspaces and tables that hold roles, their password hashes and permissions: never available to an agent,
   * whatever the list says. ScyllaDB 6 keeps its roles in tables of {@code system}.
   */
  static boolean authTable(String keyspace, String table) {
    if (keyspace.equals("system_auth") || keyspace.equals("dse_security")) {
      return true;
    }
    return keyspace.equals("system") && table != null && (table.equals("roles") || table.startsWith("role_"));
  }

  static CassandraSettings of(ConnectionConfig cfg) throws ConnectorException {
    return of(cfg, false);
  }

  /**
   * A draft of the form as setup suggestions read it: Local data center may still be empty, as they find it
   * ({@code x-suggest}). Never used for a saved connection.
   */
  static CassandraSettings draft(ConnectionConfig cfg) throws ConnectorException {
    return of(cfg, true);
  }

  private static CassandraSettings of(ConnectionConfig cfg, boolean draft) throws ConnectorException {
    List<ContactPoint> points = new ArrayList<>();
    for (String text : cfg.strings("/contactPoints")) {
      points.add(contactPoint(text));
    }
    if (points.isEmpty()) {
      throw ConnectorException.badInput("Add a contact point: host:port of a node, such as cassandra.example.com:9042.");
    }
    if (points.size() > MAX_CONTACT_POINTS) {
      throw ConnectorException.badInput("At most " + MAX_CONTACT_POINTS + " contact points: the driver finds the "
          + "other nodes itself.");
    }
    String dc = cfg.string("/localDataCenter");
    if (draft && (dc == null || dc.isBlank())) {
      dc = null;
    } else if (dc == null || dc.isBlank() || dc.length() > 128 || !printable(dc)) {
      throw ConnectorException.badInput("Enter the local data center, such as datacenter1: the driver of Cassandra 4 "
          + "and later sends requests only to its nodes.");
    }
    String keyspace = blankToNull(cfg.string("/keyspace"));
    if (keyspace != null && !KEYSPACE.matcher(keyspace).matches()) {
      throw ConnectorException.badInput(keyspace + " isn't a keyspace name: letters, digits and underscores, at "
          + "most 48.");
    }
    List<String> keyspaces = new ArrayList<>();
    for (String glob : cfg.strings("/keyspaces")) {
      if (!GLOB.matcher(glob).matches()) {
        throw ConnectorException.badInput(glob + " isn't a keyspace name or pattern: letters, digits, underscores, "
            + "* and ?.");
      }
      keyspaces.add(glob);
    }
    String user = blankToNull(cfg.string(USER));
    // A stored connection without the key keeps TLS, the old default; Auto is what new connections choose outside
    // prod, and it is TLS until AutoTls finds otherwise: no session opens before it resolved the transport.
    JsonNode tlsNode = cfg.config().path("tls");
    boolean autoTls = tlsNode.isString() && tlsNode.asString().equals("auto");
    if (!tlsNode.isMissingNode() && !tlsNode.isNull() && !tlsNode.isBoolean() && !autoTls) {
      throw ConnectorException.badInput("Connection type is Auto, TLS or Without TLS.");
    }
    boolean tls = autoTls || !tlsNode.isBoolean() || tlsNode.asBoolean();
    String verify = cfg.string("/tlsVerify");
    boolean verifyHostname = verify == null || verify.equals("full");
    if (verify != null && !verify.equals("full") && !verify.equals("ca")) {
      throw ConnectorException.badInput("TLS verification is full or ca.");
    }
    String serverName = blankToNull(cfg.string("/tlsServerName"));
    if (serverName != null && (!HOST.matcher(serverName).matches() || serverName.length() > 253)) {
      throw ConnectorException.badInput(serverName + " isn't a host name for TLS.");
    }
    List<X509Certificate> ca = List.of();
    String pem = blankToNull(cfg.string("/caCertificate"));
    if (pem != null) {
      try {
        ca = Pem.certificates(pem);
      } catch (IllegalArgumentException e) {
        throw ConnectorException.badInput("The CA certificate isn't usable: " + e.getMessage() + ".");
      }
    }
    if (tls && !verifyHostname && ca.isEmpty()) {
      throw ConnectorException.badInput("Verifying the CA only needs the cluster's own CA certificate: with the "
          + "system's CAs, any server with a public certificate would pass. Paste the CA, or verify host names.");
    }
    String consistency = cfg.string("/consistency");
    consistency = consistency == null ? "LOCAL_ONE" : consistency.toUpperCase(Locale.ROOT);
    if (!CONSISTENCIES.contains(consistency)) {
      throw ConnectorException.badInput("Read consistency is LOCAL_ONE or LOCAL_QUORUM.");
    }
    boolean allowFiltering = cfg.bool("/advanced/allowFiltering", false) && cfg.env() == Env.DEV;
    int statementMs = cfg.integer("/advanced/statementTimeoutMs", 0);
    Duration statementTimeout = statementMs <= 0 ? null : Duration.ofMillis(Math.max(100, Math.min(statementMs,
        600_000)));
    Duration connectTimeout = Duration.ofMillis(Math.max(500, Math.min(cfg.integer("/advanced/connectTimeoutMs",
        5_000), 30_000)));
    int perNode = Math.max(1, Math.min(cfg.integer("/advanced/connectionsPerNode", 1), 8));
    return new CassandraSettings(List.copyOf(points), dc, keyspace, List.copyOf(keyspaces), user, tls, autoTls,
        verifyHostname, serverName, ca, consistency, allowFiltering, statementTimeout, connectTimeout, perNode);
  }

  /** The transport Auto found, without changing the saved choice. */
  CassandraSettings withTls(boolean enabled) {
    return new CassandraSettings(contactPoints, localDataCenter, keyspace, keyspaces, user, enabled, false,
        verifyHostname, tlsServerName, caCertificates, readConsistency, allowFiltering, statementTimeout,
        connectTimeout, connectionsPerNode);
  }

  static ContactPoint contactPoint(String text) throws ConnectorException {
    String t = text == null ? "" : text.strip();
    Matcher m = CONTACT_POINT.matcher(t);
    if (t.isEmpty() || t.length() > 300 || !m.matches()) {
      throw ConnectorException.badInput(shown(t) + " isn't host:port, such as cassandra.example.com:9042 or "
          + "10.0.4.12:9042.");
    }
    String host = m.group(1);
    if (host.startsWith("[")) {
      host = host.substring(1, host.length() - 1);
    } else if (!HOST.matcher(host).matches()) {
      throw ConnectorException.badInput(shown(t) + " isn't host:port: the host is a name or an IP address.");
    }
    int port = DEFAULT_PORT;
    if (m.group(2) != null) {
      port = Integer.parseInt(m.group(2));
      if (port < 1 || port > 65535) {
        throw ConnectorException.badInput(shown(t) + " has a port outside 1 to 65535.");
      }
    }
    return new ContactPoint(host, port);
  }

  private static String shown(String text) {
    return text.length() > 60 ? text.substring(0, 59) + "…" : text;
  }

  /**
   * Whether agents may use {@code keyspace}: never the auth keyspaces; a system keyspace only when an entry of the
   * list names it from its first character ({@code system_views}, {@code system*}), not by a pattern such as
   * {@code *}; any other keyspace when the list is empty or one of its patterns matches.
   */
  boolean allows(String keyspace) {
    if (keyspace == null || authTable(keyspace, null)) {
      return false;
    }
    boolean system = systemKeyspace(keyspace);
    if (keyspaces.isEmpty()) {
      return !system;
    }
    for (String glob : keyspaces) {
      if (system && (glob.startsWith("*") || glob.startsWith("?"))) {
        continue;
      }
      if (Glob.text(glob).matches(keyspace)) {
        return true;
      }
    }
    return false;
  }

  /** Whether statements that don't name a keyspace can run: a default keyspace that the list allows. */
  boolean defaultKeyspaceAllowed() {
    return keyspace != null && allows(keyspace);
  }

  /** {@code host:port, …} for messages and the approval's target, at most three. */
  String contactPointsText() {
    List<String> shown = new ArrayList<>();
    for (int i = 0; i < Math.min(3, contactPoints.size()); i++) {
      shown.add(contactPoints.get(i).toString());
    }
    return String.join(", ", shown) + (contactPoints.size() > 3 ? " +" + (contactPoints.size() - 3) : "");
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static boolean printable(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        return false;
      }
    }
    return true;
  }
}
