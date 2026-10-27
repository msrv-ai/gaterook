package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A Redis connection's configuration ({@code config.schema.json}, report 05 §4), read once per call: everything an
 * agent can't change — the server, its database, TLS, the login's user and the keys it may read.
 *
 * @param verifyHost   {@code tlsVerify=full}: the certificate must name {@link #host}; {@code ca}: only its chain
 * @param caCertificates a private CA, or empty for the platform's trust store
 * @param maxValueBytes the longest string value read whole ({@code GETRANGE} beyond it)
 * @param chars         how the server compares the bytes of glob ranges, from the last Test connection
 *                      ({@code metadata.globChars}); unknown until one ran
 */
record RedisSettings(String host, int port, int database, List<Integer> databases, boolean tls, boolean verifyHost,
    List<X509Certificate> caCertificates, String user, List<RedisGlob> keyAllowlist, List<RedisGlob> keyDenylist,
    int commandTimeoutMs, int connectTimeoutMs, int maxValueBytes, int poolSize, boolean noTouch,
    RedisGlob.Chars chars) {

  /** The JSON pointer of the password; the user is a plain field next to it. */
  static final String PASSWORD = "/credentials/password";
  static final int MAX_PATTERN = 256;
  /** The metadata field Test connection writes: {@code signed} or {@code unsigned} ({@link RedisGlob.Chars}). */
  static final String GLOB_CHARS = "globChars";

  /** The settings can't be used: the text says which field and what to do; it never quotes a secret. */
  static final class InvalidSettingsException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidSettingsException(String message) {
      super(message);
    }
  }

  RedisSettings {
    databases = List.copyOf(databases);
    caCertificates = List.copyOf(caCertificates);
    keyAllowlist = List.copyOf(keyAllowlist);
    keyDenylist = List.copyOf(keyDenylist);
  }

  static RedisSettings parse(ConnectionConfig cfg) throws InvalidSettingsException {
    JsonNode c = cfg.config().path("connection");
    String host = c.path("host").asString("").strip();
    if (host.isEmpty() || host.length() > 253) {
      throw new InvalidSettingsException("Give the host name or address of the Redis server.");
    }
    for (int i = 0; i < host.length(); i++) {
      char ch = host.charAt(i);
      if (ch <= ' ' || ch == 0x7f || ch == '/' || ch == '@') {
        throw new InvalidSettingsException("The host is a name or an address only, like cache.dev.internal.");
      }
    }
    Optional<AddressPolicy.Verdict> syntax = AddressPolicy.checkHostSyntax(host);
    if (syntax.isPresent()) {
      throw new InvalidSettingsException(syntax.get().text());
    }
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
    if (literal.isPresent()) {
      AddressPolicy.Verdict v = AddressPolicy.builder().build().check(literal.get(), -1);
      if (v.reason() == AddressPolicy.Reason.ALWAYS_BLOCKED) {
        throw new InvalidSettingsException(v.text());
      }
    }
    int port = range(c, "port", 6379, 1, 65_535, "The port");
    int database = range(c, "database", 0, 0, 1_023, "The database");
    List<Integer> databases = new ArrayList<>();
    JsonNode selection = c.path("databases");
    if (!selection.isMissingNode()) {
      if (!selection.isArray() || selection.isEmpty() || selection.size() > 1_024) {
        throw new InvalidSettingsException("Select between 1 and 1,024 databases.");
      }
      for (JsonNode db : selection) {
        if (!db.isIntegralNumber() || db.asLong() < 0 || db.asLong() > 1_023 || databases.contains(db.asInt())) {
          throw new InvalidSettingsException("Selected databases must be unique whole numbers from 0 to 1,023.");
        }
        databases.add(db.asInt());
      }
      database = databases.getFirst();
    }
    // x-env-default: on prod, TLS is on unless the form says otherwise (the core doesn't fill defaults in).
    JsonNode tlsNode = c.path("tls");
    boolean tls = tlsNode.isBoolean() ? tlsNode.asBoolean() : cfg.env() == Env.PROD;
    String verify = c.path("tlsVerify").asString("full");
    if (!verify.equals("full") && !verify.equals("ca")) {
      throw new InvalidSettingsException("TLS verification is full or ca.");
    }
    // The core refuses to save this (x-forbidden-env-values); a config that got past it still isn't used.
    if (cfg.env() == Env.PROD && verify.equals("ca")) {
      throw new InvalidSettingsException("TLS verification can't be ca on prod.");
    }
    List<X509Certificate> ca = certificates(c.path("caCertificate").asString(""));

    JsonNode credentials = cfg.config().path("credentials");
    String user = credentials.path("user").asString("").strip();

    JsonNode advanced = cfg.config().path("advanced");
    List<RedisGlob> allow = globs(advanced.path("keyAllowlist"), "Keys the agent may read");
    List<RedisGlob> deny = globs(advanced.path("keyDenylist"), "Keys the agent never reads");
    int commandTimeout = range(advanced, "commandTimeoutMs", 2_000, 200, 30_000, "The command timeout");
    int connectTimeout = range(advanced, "connectTimeoutMs", 2_000, 500, 30_000, "The connect timeout");
    int maxValueKb = range(advanced, "maxValueKb", 64, 1, 1_024, "The max value size");
    int poolSize = range(advanced, "poolSize", 2, 1, 10, "The pool size");
    boolean noTouch = advanced.path("noTouch").asBoolean(true);
    RedisGlob.Chars chars = cfg.metadata() == null ? RedisGlob.Chars.UNKNOWN
        : RedisGlob.Chars.of(cfg.metadata().path(GLOB_CHARS).asString(""));
    return new RedisSettings(host, port, database, databases, tls, verify.equals("full"), ca, user.isEmpty() ? null : user,
        allow, deny, commandTimeout, connectTimeout, maxValueKb * 1024, poolSize, noTouch, chars);
  }

  RedisSettings withDatabase(int selected) {
    return new RedisSettings(host, port, selected, databases, tls, verifyHost, caCertificates, user, keyAllowlist,
        keyDenylist, commandTimeoutMs, connectTimeoutMs, maxValueBytes, poolSize, noTouch, chars);
  }

  /** A page belongs to both its saved connection and its database, including legacy single-database configs. */
  String cursorScope(String connectionId) {
    return connectionId + "/database/" + database;
  }

  /** {@code cache.dev.internal:6379}; IPv6 in brackets. */
  String hostPort() {
    return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
  }

  /** The user the server sees: {@code default} without a name in the form. */
  String userOrDefault() {
    return user == null ? "default" : user;
  }

  /**
   * Whether the connection's lists let an agent read {@code key}. While the server's way of comparing range bytes is
   * unknown ({@link RedisGlob.Chars#UNKNOWN}), a key is read only if both readings allow it: the allowlist matches it
   * either way and the denylist matches it neither way.
   */
  boolean keyAllowed(byte[] key) {
    return listed(key) && !denied(key);
  }

  /** The words for a key the lists hide: which list did it. */
  String whyNotAllowed(byte[] key) {
    if (denied(key)) {
      return "it matches a pattern of \"Keys the agent never reads\"";
    }
    return "it matches none of \"Keys the agent may read\"";
  }

  /** Whether a pattern of the lists means one thing to an x86-64 server and another to an arm64 one. */
  boolean listsDependOnSign() {
    return keyAllowlist.stream().anyMatch(RedisGlob::dependsOnSign)
        || keyDenylist.stream().anyMatch(RedisGlob::dependsOnSign);
  }

  private boolean listed(byte[] key) {
    if (keyAllowlist.isEmpty()) {
      return true;
    }
    return switch (chars) {
      case SIGNED -> RedisGlob.any(keyAllowlist, key, true);
      case UNSIGNED -> RedisGlob.any(keyAllowlist, key, false);
      case UNKNOWN -> RedisGlob.any(keyAllowlist, key, true) && RedisGlob.any(keyAllowlist, key, false);
    };
  }

  private boolean denied(byte[] key) {
    return switch (chars) {
      case SIGNED -> RedisGlob.any(keyDenylist, key, true);
      case UNSIGNED -> RedisGlob.any(keyDenylist, key, false);
      case UNKNOWN -> RedisGlob.any(keyDenylist, key, true) || RedisGlob.any(keyDenylist, key, false);
    };
  }

  private static int range(JsonNode parent, String field, int dflt, int min, int max, String title)
      throws InvalidSettingsException {
    JsonNode n = parent.path(field);
    if (n.isMissingNode() || n.isNull()) {
      return dflt;
    }
    if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
      throw new InvalidSettingsException(title + " is a whole number from " + min + " to " + max + ".");
    }
    return n.asInt();
  }

  private static List<RedisGlob> globs(JsonNode list, String title) throws InvalidSettingsException {
    List<RedisGlob> out = new ArrayList<>();
    if (list.isMissingNode() || list.isNull()) {
      return out;
    }
    if (!list.isArray() || list.size() > 100) {
      throw new InvalidSettingsException(title + " is a list of up to 100 Redis globs.");
    }
    for (JsonNode n : list) {
      String p = n.asString("");
      if (p.isEmpty() || p.length() > MAX_PATTERN || hasControl(p)) {
        throw new InvalidSettingsException(title + ": each pattern is 1 to " + MAX_PATTERN
            + " characters without control characters, like cache:*.");
      }
      out.add(RedisGlob.of(p));
    }
    return out;
  }

  static boolean hasControl(String s) {
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch < 0x20 || ch == 0x7f || (ch >= 0x80 && ch < 0xa0)) {
        return true;
      }
    }
    return false;
  }

  private static List<X509Certificate> certificates(String pem) throws InvalidSettingsException {
    List<X509Certificate> out = new ArrayList<>();
    if (pem.isBlank()) {
      return out;
    }
    try {
      CertificateFactory f = CertificateFactory.getInstance("X.509");
      for (Pem.Block b : Pem.blocks(pem)) {
        if (b.type().equals("CERTIFICATE")) {
          out.add((X509Certificate) f.generateCertificate(new ByteArrayInputStream(b.der())));
        }
      }
    } catch (CertificateException | RuntimeException e) {
      throw new InvalidSettingsException("The CA certificate isn't a PEM certificate (-----BEGIN CERTIFICATE-----).");
    }
    if (out.isEmpty()) {
      throw new InvalidSettingsException("The CA certificate isn't a PEM certificate (-----BEGIN CERTIFICATE-----).");
    }
    return out;
  }
}
