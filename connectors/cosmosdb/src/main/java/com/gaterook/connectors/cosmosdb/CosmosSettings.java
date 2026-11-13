package com.gaterook.connectors.cosmosdb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The settings of one Cosmos DB connection ({@code config.schema.json}): the account endpoint, how GateRook signs
 * requests, the databases and containers agents may use, the consistency of reads and the limits of a call.
 *
 * @param databases       globs of database IDs ({@code app-*}); empty: every database the credentials may read
 * @param containers      globs of container IDs ({@code orders}) or of {@code database/container}
 *                        ({@code app/orders}, {@code app/*}); empty: every container of the allowed databases
 * @param readConsistency {@code Session}, {@code ConsistentPrefix}, {@code Eventual}, or {@code null}: the account's
 *                        default
 * @param crossPartition  whether a query may leave out the partition key and fan out over every partition
 * @param maxPages        pages one query call reads at most to fill its limit
 * @param maxItemBytes    the largest body a change may send
 */
record CosmosSettings(CosmosEndpoint endpoint, Auth auth, KeyKind keyKind, String tenant, String clientId,
    List<String> databases, List<String> containers, String readConsistency, boolean crossPartition, int maxPages,
    long maxItemBytes) {

  /** How requests are authorized. */
  enum Auth { KEY, ENTRA }

  /** Which key the person says they entered: a read-only key, or a primary or secondary (read-write) key. */
  enum KeyKind { READ_ONLY, READ_WRITE }

  static final String ACCOUNT_KEY = "/auth/accountKey";
  static final String CLIENT_SECRET = "/auth/clientSecret";
  /** Cosmos DB's largest item: 2 MB of UTF-8 JSON. */
  static final long MAX_ITEM_BYTES = 2L * 1024 * 1024;
  static final int DEFAULT_PAGES = 5;
  static final int MAX_PAGES = 20;
  /** The consistency levels a read may ask for: weaker than or as strong as Session, never Strong or Bounded. */
  static final Set<String> READ_LEVELS = Set.of("Session", "ConsistentPrefix", "Eventual");

  private static final Pattern GUID = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  /** A tenant: its ID or one of its domain names, never anything that changes the token URL's path. */
  private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
      + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

  static CosmosSettings of(ConnectionConfig cfg, boolean plainLoopback) throws ConnectorException {
    CosmosEndpoint endpoint;
    try {
      endpoint = CosmosEndpoint.parse(cfg.string("/endpoint"), plainLoopback);
    } catch (CosmosEndpoint.Invalid e) {
      throw ConnectorException.badInput(e.getMessage());
    }
    String method = String.valueOf(cfg.string("/auth/method"));
    Auth auth;
    KeyKind kind = null;
    String tenant = null;
    String clientId = null;
    switch (method) {
      case "key" -> {
        auth = Auth.KEY;
        String k = cfg.string("/auth/keyKind");
        if ("readOnly".equals(k)) {
          kind = KeyKind.READ_ONLY;
        } else if ("readWrite".equals(k)) {
          kind = KeyKind.READ_WRITE;
        } else {
          throw ConnectorException.badInput("Say which key this is: a read-only key (recommended) or a primary or "
              + "secondary key.");
        }
      }
      case "entra" -> {
        auth = Auth.ENTRA;
        if (endpoint.emulator()) {
          throw ConnectorException.badInput("The emulator takes its key, not Microsoft Entra ID: choose Account key.");
        }
        tenant = blankToNull(cfg.string("/auth/tenantId"));
        clientId = blankToNull(cfg.string("/auth/clientId"));
        if (tenant == null || !(GUID.matcher(tenant).matches() || TENANT.matcher(tenant).matches())) {
          throw ConnectorException.badInput("Enter the directory (tenant) ID of the service principal, such as "
              + "00000000-0000-0000-0000-000000000000, or a domain of the tenant.");
        }
        if (clientId == null || !GUID.matcher(clientId).matches()) {
          throw ConnectorException.badInput("Enter the application (client) ID of the service principal: a GUID such "
              + "as 00000000-0000-0000-0000-000000000000.");
        }
        tenant = tenant.toLowerCase(Locale.ROOT);
        clientId = clientId.toLowerCase(Locale.ROOT);
      }
      default -> throw ConnectorException.badInput("Choose how GateRook signs in: an account key or Microsoft Entra "
          + "ID.");
    }
    List<String> databases = globs(cfg.strings("/databases"), false);
    List<String> containers = globs(cfg.strings("/containers"), true);
    String consistency = blankToNull(cfg.string("/readConsistency"));
    if (consistency == null || consistency.equals("account")) {
      consistency = null;
    } else if (!READ_LEVELS.contains(consistency)) {
      throw ConnectorException.badInput("Read consistency is the account's default, Session, ConsistentPrefix or "
          + "Eventual.");
    }
    int pages = Math.max(1, Math.min(MAX_PAGES, cfg.integer("/advanced/maxPages", DEFAULT_PAGES)));
    int kb = Math.max(1, Math.min(2048, cfg.integer("/advanced/maxItemKb", 2048)));
    return new CosmosSettings(endpoint, auth, kind, tenant, clientId, databases, containers, consistency,
        cfg.bool("/advanced/crossPartitionQueries", true), pages, Math.min(MAX_ITEM_BYTES, kb * 1024L));
  }

  private static List<String> globs(List<String> entries, boolean qualified) throws ConnectorException {
    List<String> out = new ArrayList<>();
    for (String entry : entries) {
      String e = entry == null ? "" : entry.strip();
      String[] parts = qualified && e.indexOf('/') >= 0 ? e.split("/", -1) : new String[] {e};
      if (parts.length > 2) {
        throw ConnectorException.badInput("A container entry is a container ID or database/container, not "
            + CosmosIds.shown(e) + ".");
      }
      for (String part : parts) {
        String problem = CosmosIds.nameProblem(part.replace('*', 'x'));
        if (problem != null) {
          throw ConnectorException.badInput((qualified ? "The container entry " : "The database entry ")
              + CosmosIds.shown(e) + " " + problem + ".");
        }
      }
      out.add(e);
    }
    return List.copyOf(out);
  }

  /** Whether agents may use the database {@code db}: on the list, or any when the list is empty. */
  boolean allowsDatabase(String db) {
    return databases.isEmpty() || Glob.anyText(databases, db);
  }

  /**
   * Whether a listing shows the database {@code db}: it is allowed, and the container list leaves it a container
   * (an entry without a database, or one for this database).
   */
  boolean showsDatabase(String db) {
    if (!allowsDatabase(db)) {
      return false;
    }
    if (containers.isEmpty()) {
      return true;
    }
    for (String entry : containers) {
      int slash = entry.indexOf('/');
      if (slash < 0 || Glob.text(entry.substring(0, slash)).matches(db)) {
        return true;
      }
    }
    return false;
  }

  /** Whether agents may use the container {@code container} of {@code db}: both lists allow it. */
  boolean allowsContainer(String db, String container) {
    if (!allowsDatabase(db)) {
      return false;
    }
    if (containers.isEmpty()) {
      return true;
    }
    for (String entry : containers) {
      int slash = entry.indexOf('/');
      if (slash < 0 ? Glob.text(entry).matches(container)
          : Glob.text(entry.substring(0, slash)).matches(db) && Glob.text(entry.substring(slash + 1)).matches(container)) {
        return true;
      }
    }
    return false;
  }

  /** Whether changes can't succeed: a read-only key, as the person said. */
  boolean readOnlyKey() {
    return auth == Auth.KEY && keyKind == KeyKind.READ_ONLY;
  }

  /** {@code acme.documents.azure.com}, or the emulator's address. */
  String label() {
    return endpoint.label();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
