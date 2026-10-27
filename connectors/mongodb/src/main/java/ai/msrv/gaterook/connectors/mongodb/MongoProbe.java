package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.spi.dns.DnsClient;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection and the health check (report 05 §5): a client of its own (the cached ones aren't touched, nothing
 * is stored), at most {@value #BUDGET_MS} ms. {@code ping} is the connect and the login; the latency is the median of
 * three more. {@code buildInfo}, {@code hello}, {@code connectionStatus {showPrivileges: true}} (any user may run it)
 * say what the account may really do; {@code listDatabases {nameOnly, authorizedDatabases}} what it sees, and whether
 * the allowlist names something that exists. The hints are the kit's: a user that can write, an administrator, a
 * server without access control, prod without TLS or reading the primary.
 */
final class MongoProbe {

  static final long BUDGET_MS = 5_000;

  /** Actions of a privilege that change data or schema (measured on 8.0.23 and 8.3.2: read 10–11, readWrite 24–25). */
  static final Set<String> WRITE_ACTIONS = Set.of("insert", "update", "remove", "createCollection", "dropCollection",
      "createIndex", "dropIndex", "renameCollectionSameDB", "convertToCapped", "collMod", "dropDatabase",
      "bypassDocumentValidation", "applyOps", "createSearchIndexes", "dropSearchIndex", "updateSearchIndex",
      "compactStructuredEncryptionData", "cleanupStructuredEncryptionData");

  /** Actions that make an administrator. */
  static final Set<String> ADMIN_ACTIONS = Set.of("anyAction", "createUser", "dropUser", "grantRole", "revokeRole",
      "createRole", "dropRole", "dropDatabase", "shutdown", "setParameter");

  static final Set<String> ADMIN_ROLES = Set.of("root", "__system", "dbOwner", "userAdmin", "userAdminAnyDatabase",
      "dbAdminAnyDatabase", "clusterAdmin", "restore");

  private final DnsClient dns;

  MongoProbe(DnsClient dns) {
    this.dns = dns;
  }

  TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      MongoSettings.Problem p = s.problems().getFirst();
      return TestResult.failure("mongo.config", p.text(), null)
          .withHint(new Hint(Severity.ERROR, "mongo.config", p.field(), p.text()));
    }
    long budget = Math.max(1_000, Math.min(BUDGET_MS, ctx.remaining().toMillis()));
    try (MongoLogin login = MongoLogin.resolve(s, secrets)) {
      if (login.user() != null && !login.hasPassword()) {
        return TestResult.failure("mongo.secret", "No password is stored for " + login.user() + ". Enter it under "
            + "Credentials, or choose Mechanism none for a server without access control.", null);
      }
      List<TestResult.Check> checks = new ArrayList<>();
      MongoClient client;
      try {
        client = MongoClients.create(ClientSettings.build(s, login, dns,
            Duration.ofMillis(Math.min(s.serverSelectionTimeoutMs(), budget)), Duration.ofMillis(budget)));
      } catch (MongoException | IllegalArgumentException e) {
        return MongoErrors.test(e, s, null);
      }
      try (client) {
        return probe(client, cfg, s, login, checks);
      } catch (MongoException e) {
        return MongoErrors.test(e, s, client).withChecks(checks);
      }
    } catch (SecretUnavailableException e) {
      return TestResult.failure("mongo.secret", e.getMessage(), null);
    }
  }

  private TestResult probe(MongoClient client, ConnectionConfig cfg, MongoSettings s, MongoLogin login,
      List<TestResult.Check> checks) {
    MongoDatabase admin = client.getDatabase("admin");
    BsonDocument ping = new BsonDocument("ping", new BsonInt32(1));
    long t0 = System.nanoTime();
    admin.runCommand(ping, BsonDocument.class);
    long connectMs = ms(t0);
    checks.add(new TestResult.Check("connect", true, connectMs, "Connected to " + s.parsed().display()
        + (login.user() == null ? "" : " as " + login.identity(s))));
    long[] runs = new long[3];
    for (int i = 0; i < runs.length; i++) {
      long t = System.nanoTime();
      admin.runCommand(ping, BsonDocument.class);
      runs[i] = ms(t);
    }
    Arrays.sort(runs);
    long latency = runs[1];
    checks.add(new TestResult.Check("latency", true, latency, "Median of three pings"));

    BsonDocument build = admin.runCommand(new BsonDocument("buildInfo", new BsonInt32(1)), BsonDocument.class);
    String version = build.getString("version", new BsonString("?")).getValue();
    Topology topology = topology(admin);
    checks.add(new TestResult.Check("tls", true, 0, s.tls() ? "Encrypted" : "Not encrypted"));

    BsonDocument status = admin.runCommand(new BsonDocument("connectionStatus", new BsonInt32(1))
        .append("showPrivileges", BsonBoolean.TRUE), BsonDocument.class);
    Access access = access(status.getDocument("authInfo", new BsonDocument()));
    checks.add(new TestResult.Check("permissions", true, 0, access.authOff() ? "Access control is off"
        : !access.admin().isEmpty() ? "Administrator" : !access.write().isEmpty() ? "Can write"
        : !access.read().isEmpty() ? "Read only" : "Can't read anything"));

    List<String> databases = databases(admin);
    List<String> missing = missing(client, s, databases);
    checks.add(new TestResult.Check("allowlist", missing.isEmpty(), 0, s.namespaceAllowlist().isEmpty()
        ? "No allowlist: every database the user can read, except admin, local and config"
        : missing.isEmpty() ? "Every allowlist entry found" : "Not found: " + String.join(", ", missing)));

    TestResult.Level level = access.authOff() || !access.admin().isEmpty() ? TestResult.Level.ADMIN
        : !access.write().isEmpty() ? TestResult.Level.WRITE
        : !access.read().isEmpty() ? TestResult.Level.READ : TestResult.Level.UNKNOWN;
    ObjectNode permissionDetails = JsonNodeFactory.instance.objectNode();
    ArrayNode roles = permissionDetails.putArray("roles");
    access.roles().forEach(roles::add);
    ArrayNode visible = permissionDetails.putArray("databases");
    databases.forEach(visible::add);
    TestResult.Permissions permissions = new TestResult.Permissions(level, readText(access), writeText(access),
        List.copyOf(access.admin()), permissionDetails);
    String identity = access.user() != null ? access.user() : login.identity(s);
    TestResult result = TestResult.success(latency, "MongoDB " + version, identity, permissions);
    for (Hint h : hints(cfg.env(), s, access, topology, missing, identity)) {
      result = result.withHint(h);
    }
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    details.putObject("server").put("product", "MongoDB").put("version", version).put("topology", topology.kind())
        .put("setName", topology.setName());
    details.putObject("tls").put("enabled", s.tls());
    details.put("user", identity);
    details.put("connectMs", connectMs);
    ArrayNode selectable = details.putArray("databases");
    Namespaces namespaces = new Namespaces(s.namespaceAllowlist());
    databases.stream().filter(namespaces::databaseVisible).sorted().forEach(selectable::add);
    details.put("discoveryComplete", true);
    if (!access.write().isEmpty() || !access.admin().isEmpty() || access.authOff()) {
      details.putObject("snippets").putObject("mongo.user-can-write").put("language", "js")
          .put("text", readOnlyUser(s));
    }
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("serverVersion", "MongoDB " + majorMinor(version));
    metadata.put("version", version);
    metadata.put("topology", topology.kind());
    return result.withChecks(checks).withDetails(details).withMetadata(metadata);
  }

  /** {@code ping} through a client that is open now, else through one of its own. */
  HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, MongoClientCache cache) {
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      return HealthResult.unreachable(s.problemText());
    }
    BsonDocument ping = new BsonDocument("ping", new BsonInt32(1));
    try (MongoClientCache.Lease lease = cache.existing(cfg)) {
      if (lease != null) {
        long t = System.nanoTime();
        lease.client().getDatabase("admin").runCommand(ping, BsonDocument.class);
        return HealthResult.healthy(ms(t));
      }
    } catch (MongoException e) {
      MongoErrors.Reason r = MongoErrors.connection(e, s, null);
      return HealthResult.unreachable(r == null ? MongoErrors.firstLine(e.getMessage()) : MongoErrors.sentence(r, s));
    }
    long budget = Math.max(1_000, Math.min(BUDGET_MS, ctx.remaining().toMillis()));
    try (MongoLogin login = MongoLogin.resolve(s, secrets);
         MongoClient client = MongoClients.create(ClientSettings.build(s, login, dns,
             Duration.ofMillis(Math.min(s.serverSelectionTimeoutMs(), budget)), Duration.ofMillis(budget)))) {
      try {
        client.getDatabase("admin").runCommand(ping, BsonDocument.class);
        long t = System.nanoTime();
        client.getDatabase("admin").runCommand(ping, BsonDocument.class);
        return HealthResult.healthy(ms(t));
      } catch (MongoException e) {
        MongoErrors.Reason r = MongoErrors.connection(e, s, client);
        return HealthResult.unreachable(r == null ? MongoErrors.firstLine(e.getMessage())
            : MongoErrors.sentence(r, s));
      }
    } catch (SecretUnavailableException e) {
      return HealthResult.unreachable(e.getMessage());
    } catch (MongoException | IllegalArgumentException e) {
      return HealthResult.unreachable(MongoErrors.firstLine(e.getMessage()));
    }
  }

  record Topology(String kind, String setName) {}

  private static Topology topology(MongoDatabase admin) {
    BsonDocument hello;
    try {
      hello = admin.runCommand(new BsonDocument("hello", new BsonInt32(1)), BsonDocument.class);
    } catch (MongoCommandException e) {
      // 4.2 and 4.4 before 4.4.2 know only the old name.
      hello = admin.runCommand(new BsonDocument("isMaster", new BsonInt32(1)), BsonDocument.class);
    }
    if ("isdbgrid".equals(hello.getString("msg", new BsonString("")).getValue())) {
      return new Topology("sharded", null);
    }
    if (hello.containsKey("setName")) {
      return new Topology("replicaSet", hello.getString("setName").getValue());
    }
    return new Topology("standalone", null);
  }

  /** What {@code connectionStatus} says the account may do. */
  record Access(String user, boolean authOff, Set<String> read, Set<String> write, Set<String> admin,
      List<String> roles) {}

  static Access access(BsonDocument authInfo) {
    BsonArray users = authInfo.getArray("authenticatedUsers", new BsonArray());
    String user = null;
    if (!users.isEmpty() && users.getFirst().isDocument()) {
      BsonDocument u = users.getFirst().asDocument();
      user = u.getString("user", new BsonString("?")).getValue() + "@" + u.getString("db", new BsonString("?"))
          .getValue();
    }
    List<String> roles = new ArrayList<>();
    Set<String> admin = new TreeSet<>();
    for (BsonValue r : authInfo.getArray("authenticatedUserRoles", new BsonArray())) {
      if (r.isDocument()) {
        String role = r.asDocument().getString("role", new BsonString("?")).getValue();
        roles.add(role + "@" + r.asDocument().getString("db", new BsonString("?")).getValue());
        if (ADMIN_ROLES.contains(role)) {
          admin.add(role);
        }
      }
    }
    Set<String> read = new LinkedHashSet<>();
    Set<String> write = new TreeSet<>();
    Set<String> writeScope = new LinkedHashSet<>();
    for (BsonValue p : authInfo.getArray("authenticatedUserPrivileges", new BsonArray())) {
      if (!p.isDocument()) {
        continue;
      }
      BsonDocument priv = p.asDocument();
      String scope = scope(priv.getDocument("resource", new BsonDocument()));
      boolean writes = false;
      for (BsonValue a : priv.getArray("actions", new BsonArray())) {
        String action = a.isString() ? a.asString().getValue() : "";
        if (action.equals("find")) {
          read.add(scope);
        }
        if (WRITE_ACTIONS.contains(action)) {
          write.add(action);
          writes = true;
        }
        if (ADMIN_ACTIONS.contains(action)) {
          admin.add(action);
        }
      }
      if (writes) {
        writeScope.add(scope);
      }
    }
    boolean authOff = users.isEmpty();
    Set<String> writeOut = write.isEmpty() ? Set.of() : new LinkedHashSet<>(List.of(String.join(", ", write)
        + " on " + String.join(", ", writeScope)));
    return new Access(user, authOff, read, writeOut, admin, List.copyOf(roles));
  }

  private static String scope(BsonDocument resource) {
    if (resource.getBoolean("anyResource", BsonBoolean.FALSE).getValue()
        || resource.getBoolean("cluster", BsonBoolean.FALSE).getValue()) {
      return "every database";
    }
    String db = resource.getString("db", new BsonString("")).getValue();
    String coll = resource.getString("collection", new BsonString("")).getValue();
    if (db.isEmpty()) {
      return coll.isEmpty() ? "every database" : "every " + coll + " collection";
    }
    return coll.isEmpty() ? db : db + "." + coll;
  }

  private static String readText(Access a) {
    if (a.authOff()) {
      return "everything: access control is off";
    }
    return a.read().isEmpty() ? null : "find on " + String.join(", ", a.read());
  }

  private static String writeText(Access a) {
    if (a.authOff()) {
      return "everything: access control is off";
    }
    if (a.write().isEmpty()) {
      return null;
    }
    List<String> writeRoles = a.roles().stream().filter(r -> !r.startsWith("read@")).toList();
    return a.write().iterator().next() + (writeRoles.isEmpty() ? "" : " (" + String.join(", ", writeRoles) + ")");
  }

  static List<Hint> hints(Env env, MongoSettings s, Access a, Topology topology, List<String> missing,
      String identity) {
    List<Hint> out = new ArrayList<>();
    if (a.authOff()) {
      out.add(new Hint(env == Env.PROD ? Severity.DANGER : Severity.WARNING, "mongo.no-auth", "/credentials/user",
          "Access control is off: anyone who reaches this server can write. Turn on authorization and use a user "
              + "with the read role."));
    } else if (!a.admin().isEmpty()) {
      out.add(new Hint(Severity.DANGER, "mongo.user-is-admin", "/credentials/user", "This user is an administrator ("
          + String.join(", ", a.admin()) + "): it can drop databases and manage users. Use a user with the read "
          + "role."));
    } else if (!a.write().isEmpty()) {
      boolean dev = env == Env.DEV;
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "mongo.user-can-write", "/credentials/user",
          "This user can write. " + (dev ? "Fine for dev: the gateway refuses $out and $merge before they reach "
              + "the server. For stage and prod use a user with the read role only."
              : "The gateway refuses $out and $merge, but on " + env.id() + " use a user with the read role only: "
              + "its roles are the real boundary.")));
    }
    if (env == Env.PROD && !s.tls()) {
      out.add(new Hint(Severity.WARNING, "mongo.tls-off", "/connection/tls", "The connection isn't encrypted. On "
          + "prod turn on TLS."));
    }
    if (env == Env.PROD && "primary".equals(s.readPreference()) && !"standalone".equals(topology.kind())) {
      out.add(new Hint(Severity.INFO, "mongo.read-primary", "/connection/readPreference", "secondaryPreferred "
          + "keeps agent reads off the primary."));
    }
    if (!missing.isEmpty()) {
      out.add(new Hint(Severity.WARNING, "mongo.allowlist-not-found", "/advanced/namespaceAllowlist",
          (missing.size() == 1 ? missing.getFirst() + " isn't" : String.join(", ", missing) + " aren't")
              + " visible to " + (identity == null ? "this user" : identity) + "."));
    }
    return out;
  }

  private static List<String> databases(MongoDatabase admin) {
    BsonDocument r = admin.runCommand(new BsonDocument("listDatabases", new BsonInt32(1))
        .append("nameOnly", BsonBoolean.TRUE).append("authorizedDatabases", BsonBoolean.TRUE), BsonDocument.class);
    List<String> out = new ArrayList<>();
    for (BsonValue d : r.getArray("databases", new BsonArray())) {
      if (d.isDocument() && d.asDocument().containsKey("name")) {
        out.add(d.asDocument().getString("name").getValue());
      }
    }
    return out;
  }

  /** Allowlist entries whose database isn't visible, or whose collection doesn't exist. */
  private static List<String> missing(MongoClient client, MongoSettings s, List<String> databases) {
    List<String> out = new ArrayList<>();
    for (String entry : s.namespaceAllowlist()) {
      int dot = entry.indexOf('.');
      String db = entry.substring(0, dot);
      String coll = entry.substring(dot + 1);
      if (coll.equals("*")) {
        if (!databases.contains(db)) {
          out.add(entry);
        }
        continue;
      }
      try {
        BsonDocument r = client.getDatabase(db).runCommand(new BsonDocument("listCollections", new BsonInt32(1))
            .append("filter", new BsonDocument("name", new BsonString(coll))).append("nameOnly", BsonBoolean.TRUE)
            .append("authorizedCollections", BsonBoolean.TRUE), BsonDocument.class);
        if (r.getDocument("cursor", new BsonDocument()).getArray("firstBatch", new BsonArray()).isEmpty()) {
          out.add(entry);
        }
      } catch (MongoCommandException e) {
        out.add(entry);
      }
    }
    return out;
  }

  /** mongosh for a read-only user of the connection's databases. */
  static String readOnlyUser(MongoSettings s) {
    Set<String> dbs = new LinkedHashSet<>();
    for (String entry : s.namespaceAllowlist()) {
      dbs.add(entry.substring(0, entry.indexOf('.')));
    }
    if (!s.databases().isEmpty()) {
      if (s.namespaceAllowlist().isEmpty()) {
        dbs.addAll(s.databases());
      } else {
        dbs.retainAll(s.databases());
      }
    }
    if (dbs.isEmpty()) {
      dbs.add(s.defaultDatabase() == null ? "<database>" : s.defaultDatabase());
    }
    StringBuilder roles = new StringBuilder();
    for (String db : dbs) {
      if (!roles.isEmpty()) {
        roles.append(", ");
      }
      roles.append("{ role: \"read\", db: \"").append(db).append("\" }");
    }
    return "use " + s.authSource() + "\ndb.createUser({\n  user: \"gw_reader\",\n  pwd: passwordPrompt(),\n  roles: ["
        + roles + "]\n})";
  }

  static String majorMinor(String version) {
    String[] parts = version.split("\\.");
    return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
