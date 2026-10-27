package ai.msrv.gaterook.connectors.mongodb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.Pem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One connection's config as the connector uses it (the form of {@code config.schema.json}), with the checks the
 * schema can't express (report 05 §4): no user information and none of the options that belong to a form field or
 * that the gateway decides in the connection string, and the prod defaults the core leaves to the connector
 * ({@code x-env-default}: TLS on, {@code secondaryPreferred}). A config with {@link #problems()} is never used to
 * connect: Test connection names the field, and every call is denied.
 *
 * @param uri              the connection string as stored
 * @param parsed           its parts, {@code null} when it doesn't parse
 * @param tls              TLS for every connection: the form's setting (on by default on prod), the connection
 *                         string's {@code tls=true}, or {@code mongodb+srv://} without {@code tls=false}
 * @param readPreference   the form's, else {@code secondaryPreferred} on prod and {@code primary} elsewhere
 * @param maxTimeMs        the connection's own query time limit, or {@code null} (the environment's applies)
 */
record MongoSettings(Env env, String uri, MongoUri parsed, boolean tls, String caCertificate,
    boolean allowInvalidHostnames, String readPreference, String user, String authSource, String mechanism,
    List<String> namespaceAllowlist, List<String> databases, Integer maxTimeMs, int poolSize, int serverSelectionTimeoutMs,
    List<Problem> problems) {

  static final String PASSWORD = "/credentials/password";
  static final String DEFAULT_AUTH_SOURCE = "admin";

  /** What is wrong with one field. */
  record Problem(String field, String text) {}

  /**
   * Options of the connection string the gateway refuses, with the reason. Lower case, the way the driver compares
   * option names. The form has a field for most of them; the rest would weaken what the gateway sets (time limits)
   * or carry a secret outside the secret store.
   */
  private static final Map<String, String> REFUSED_OPTIONS = Map.ofEntries(
      Map.entry("tlsinsecure", "use Skip the host name check (not on prod) instead"),
      Map.entry("tlsallowinvalidcertificates", "add the server's CA certificate instead"),
      Map.entry("tlsallowinvalidhostnames", "use Skip the host name check (not on prod) instead"),
      Map.entry("sslinvalidhostnameallowed", "use Skip the host name check (not on prod) instead"),
      Map.entry("tlscafile", "paste the CA certificate into CA certificate instead"),
      Map.entry("tlscertificatekeyfile", "client certificates aren't supported yet"),
      Map.entry("tlscertificatekeyfilepassword", "client certificates aren't supported yet"),
      Map.entry("authmechanism", "choose it in Credentials › Mechanism instead"),
      Map.entry("authmechanismproperties", "choose the mechanism in Credentials instead"),
      Map.entry("proxyusername", "credentials don't belong in the connection string"),
      Map.entry("proxypassword", "credentials don't belong in the connection string"),
      Map.entry("timeoutms", "the environment policy sets the time limits"));

  static final Set<String> READ_PREFERENCES = Set.of("primary", "primaryPreferred", "secondary",
      "secondaryPreferred", "nearest");

  static MongoSettings of(ConnectionConfig cfg) {
    Env env = cfg.env();
    List<Problem> problems = new ArrayList<>();
    String uri = cfg.string("/connection/uri");
    MongoUri parsed = null;
    try {
      parsed = MongoUri.parse(uri);
    } catch (IllegalArgumentException e) {
      problems.add(new Problem("/connection/uri", e.getMessage()));
    }
    boolean formTls = cfg.bool("/connection/tls", env == Env.PROD);
    boolean tls = formTls;
    String formReadPreference = cfg.string("/connection/readPreference");
    String readPreference = formReadPreference != null ? formReadPreference
        : env == Env.PROD ? "secondaryPreferred" : "primary";
    if (!READ_PREFERENCES.contains(readPreference)) {
      problems.add(new Problem("/connection/readPreference", "Read preference " + readPreference
          + " isn't one of " + String.join(", ", READ_PREFERENCES) + "."));
      readPreference = "primary";
    }
    String authSource = cfg.string("/credentials/authSource");
    if (authSource == null || authSource.isBlank()) {
      authSource = DEFAULT_AUTH_SOURCE;
    }
    if (parsed != null) {
      if (parsed.userInfo()) {
        problems.add(new Problem("/connection/uri", "Remove the user name and password from the connection "
            + "string; put them into Credentials."));
      }
      for (Map.Entry<String, String> o : parsed.options().entrySet()) {
        String reason = REFUSED_OPTIONS.get(o.getKey());
        if (reason != null) {
          problems.add(new Problem("/connection/uri", "The connection string can't set " + o.getKey() + ": "
              + reason + "."));
        }
      }
      String uriAuthSource = parsed.option("authSource");
      if (uriAuthSource != null && !uriAuthSource.equals(authSource)) {
        problems.add(new Problem("/credentials/authSource", "The connection string says authSource="
            + uriAuthSource + " and Auth database says " + authSource + ": make them the same."));
      }
      String uriReadPreference = parsed.option("readPreference");
      if (uriReadPreference != null && !uriReadPreference.equals(readPreference)) {
        problems.add(new Problem("/connection/readPreference", "The connection string says readPreference="
            + uriReadPreference + " and Read preference says " + readPreference + ": make them the same."));
      }
      String uriTls = parsed.option("tls") != null ? parsed.option("tls") : parsed.option("ssl");
      if ("false".equalsIgnoreCase(uriTls) && formTls) {
        problems.add(new Problem("/connection/tls", "The connection string says tls=false and Use TLS is on: "
            + "make them the same."));
      }
      if ("true".equalsIgnoreCase(uriTls) || (parsed.srv() && uriTls == null)) {
        tls = true;
      }
    }
    boolean allowInvalidHostnames = cfg.bool("/connection/allowInvalidHostnames", false);
    if (allowInvalidHostnames && env == Env.PROD) {
      problems.add(new Problem("/connection/allowInvalidHostnames", "Skip the host name check isn't allowed on "
          + "prod."));
    }
    String mechanism = cfg.string("/credentials/mechanism");
    if (mechanism == null) {
      mechanism = "auto";
    }
    if (!Set.of("auto", "SCRAM-SHA-256", "SCRAM-SHA-1", "none").contains(mechanism)) {
      problems.add(new Problem("/credentials/mechanism", "Mechanism " + mechanism + " isn't supported."));
    }
    String user = cfg.string("/credentials/user");
    if (user != null && user.isBlank()) {
      user = null;
    }
    List<String> allowlist = cfg.strings("/advanced/namespaceAllowlist");
    List<String> databases = new ArrayList<>();
    JsonNode selection = cfg.config().at("/connection/databases");
    if (!selection.isMissingNode()) {
      if (!selection.isArray() || selection.isEmpty() || selection.size() > 200) {
        problems.add(new Problem("/connection/databases", "Select between 1 and 200 databases."));
      } else {
        for (JsonNode db : selection) {
          if (!db.isString() || db.asString().contains(".") || db.asString().indexOf('\0') >= 0
              || !Namespaces.validEntry(db.asString() + ".*")
              || databases.contains(db.asString())) {
            problems.add(new Problem("/connection/databases", "Select unique, valid database names."));
          } else {
            databases.add(db.asString());
          }
        }
      }
    }
    for (String entry : allowlist) {
      if (!Namespaces.validEntry(entry)) {
        problems.add(new Problem("/advanced/namespaceAllowlist", entry + " isn't database.collection or "
            + "database.*."));
      }
    }
    Integer maxTimeMs = cfg.config().at("/advanced/maxTimeMs").isNumber()
        ? cfg.integer("/advanced/maxTimeMs", 0) : null;
    int poolSize = Math.clamp(cfg.integer("/advanced/poolSize", 2), 1, 10);
    int serverSelection = Math.clamp(cfg.integer("/advanced/serverSelectionTimeoutMs", 5_000), 1_000, 30_000);
    String ca = cfg.string("/connection/caCertificate");
    if (ca != null && ca.isBlank()) {
      ca = null;
    }
    if (ca != null) {
      try {
        Pem.certificates(ca);
      } catch (IllegalArgumentException e) {
        problems.add(new Problem("/connection/caCertificate", e.getMessage()));
      }
    }
    if (user != null && "none".equals(mechanism)) {
      problems.add(new Problem("/credentials/mechanism", "Mechanism none sends no user: clear User, or choose "
          + "a mechanism."));
    }
    return new MongoSettings(env, uri, parsed, tls, ca, allowInvalidHostnames, readPreference, user, authSource,
        mechanism, List.copyOf(allowlist), List.copyOf(databases), maxTimeMs, poolSize, serverSelection,
        List.copyOf(problems));
  }

  boolean usable() {
    return problems.isEmpty();
  }

  /** The sole selected database, or the legacy connection string's default when no selection is stored. */
  String defaultDatabase() {
    if (!databases.isEmpty()) {
      return databases.size() == 1 ? databases.getFirst() : null;
    }
    return parsed == null ? null : parsed.database();
  }

  /** {@code mongo.dev.internal:27017/orders · secondaryPreferred}: where a call goes, for previews and texts. */
  String target(String database) {
    return (parsed == null ? "?" : String.join(",", parsed.hosts()) + "/" + database) + " · " + readPreference;
  }

  /** The first problem as one sentence for the agent and the log. */
  String problemText() {
    return problems.isEmpty() ? null : problems.getFirst().text();
  }
}
