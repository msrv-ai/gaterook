package ai.msrv.gaterook.connectors.mongodb;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which {@code database.collection} the agent may read (report 05 §3.3, §6). Without an allowlist: every database the
 * user can read, except the system ones — {@code admin} (the users' SCRAM hashes are in {@code admin.system.users}),
 * {@code local} (the oplog: every change of every database), {@code config} — and except {@code system.*}
 * collections anywhere. With an allowlist ({@code orders.*}, {@code orders.orders}): only what it names; a system
 * database needs an entry naming that database, and a {@code system.*} collection an entry naming that very
 * collection ({@code local.oplog.rs} opens the oplog and nothing else of {@code local}).
 *
 * <p>The allowlist is defence in depth and a convenience, not the boundary: a view in the list reads its source
 * collection. The boundary is the user's roles (Test connection shows them).
 */
final class Namespaces {

  static final Set<String> SYSTEM_DATABASES = Set.of("admin", "local", "config");

  private static final Pattern ENTRY = Pattern.compile("^[^$\\s/\\\\.\"]{1,64}\\.(\\*|[^$\\s]{1,255})$");

  private final List<String> allowlist;
  private final List<String> databases;

  Namespaces(List<String> allowlist) {
    this(allowlist, List.of());
  }

  Namespaces(List<String> allowlist, List<String> databases) {
    this.allowlist = List.copyOf(allowlist);
    this.databases = List.copyOf(databases);
  }

  static boolean validEntry(String entry) {
    return entry != null && ENTRY.matcher(entry).matches();
  }

  boolean restricted() {
    return !allowlist.isEmpty() || !databases.isEmpty();
  }

  List<String> allowlist() {
    return allowlist;
  }

  /** Whether the agent may read {@code database.collection}. */
  boolean allowed(String database, String collection) {
    if (!databases.isEmpty() && !databases.contains(database)) {
      return false;
    }
    boolean systemCollection = collection.startsWith("system.");
    if (allowlist.isEmpty()) {
      return !SYSTEM_DATABASES.contains(database) && !systemCollection;
    }
    for (String entry : allowlist) {
      int dot = entry.indexOf('.');
      String db = entry.substring(0, dot);
      String coll = entry.substring(dot + 1);
      if (!db.equals(database)) {
        continue;
      }
      if (coll.equals("*") ? !systemCollection : coll.equals(collection)) {
        return true;
      }
    }
    return false;
  }

  /** Whether any collection of {@code database} may be read: what {@code describe_connection} lists. */
  boolean databaseVisible(String database) {
    if (!databases.isEmpty() && !databases.contains(database)) {
      return false;
    }
    if (allowlist.isEmpty()) {
      return !SYSTEM_DATABASES.contains(database);
    }
    for (String entry : allowlist) {
      if (entry.substring(0, entry.indexOf('.')).equals(database)) {
        return true;
      }
    }
    return false;
  }

  /** The reason the agent gets for a namespace it may not read. */
  String refusal(String database, String collection) {
    String ns = database + "." + collection;
    if (!databases.isEmpty() && !databases.contains(database)) {
      return database + " isn't one of this connection's selected databases ("
          + String.join(", ", databases) + ").";
    }
    if (!allowlist.isEmpty()) {
      return ns + " isn't in the collections this connection may read (" + String.join(", ", allowlist) + ").";
    }
    if (SYSTEM_DATABASES.contains(database)) {
      return database + " is a system database: MCP Gateway doesn't read admin, local or config unless the "
          + "connection's allowlist names them.";
    }
    return ns + " is a system collection: MCP Gateway doesn't read system.* unless the connection's allowlist "
        + "names it.";
  }
}
