package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The settings of one Firestore connection ({@code firestore/config.schema.json}): the project and the database, the
 * Google credentials of connectors/cloud-auth, and the collections agents may use.
 *
 * <p>An entry of {@code collections} without a slash is a glob of collection IDs ({@code orders}, {@code audit-*}):
 * it allows every collection with a matching ID wherever it is, and collection-group queries on that ID. An entry with
 * a slash is a glob of collection paths ({@code users/*}{@code /orders}, {@code /orders}: a leading slash anchors it at
 * the root), matched segment by segment ({@code *} within one, {@code **} across any number); it never allows a
 * collection group, which spans every path. An empty list allows every collection the credentials may read.
 */
record FirestoreSettings(String project, String database, List<String> collections, GcpSpec credentials) {

  static final Pattern PROJECT = Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
  static final Pattern DATABASE = Pattern.compile("^(\\(default\\)|[a-z][a-z0-9-]{2,61}[a-z0-9])$");
  static final String DEFAULT_DATABASE = "(default)";

  FirestoreSettings {
    collections = List.copyOf(collections);
  }

  static FirestoreSettings of(ConnectionConfig cfg) throws ConnectorException {
    String project = cfg.string("/project");
    if (project == null || !PROJECT.matcher(project).matches()) {
      throw ConnectorException.badInput("Choose the Google Cloud project of the database, such as acme-stage.");
    }
    String database = cfg.string("/database");
    if (database == null || database.isBlank()) {
      database = DEFAULT_DATABASE;
    }
    if (!DATABASE.matcher(database).matches()) {
      throw ConnectorException.badInput("\"" + Names.shown(database) + "\" isn't a Firestore database ID: use "
          + "(default) or the ID of a named database.");
    }
    List<String> collections = new ArrayList<>();
    for (String entry : cfg.strings("/collections")) {
      String problem = entryProblem(entry);
      if (problem != null) {
        throw ConnectorException.badInput("The collection \"" + Names.shown(entry) + "\" of " + cfg.name() + " "
            + problem + ".");
      }
      collections.add(entry);
    }
    GcpSpec spec;
    try {
      spec = GcpSpec.fromConfig(cfg.config(), "/credentials");
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The Google credentials of " + cfg.name() + " aren't complete: "
          + e.getMessage() + ".");
    }
    return new FirestoreSettings(project, database, collections, spec);
  }

  /** Why an entry of {@code collections} can't be used, or {@code null}. */
  private static String entryProblem(String entry) {
    String e = entry.startsWith("/") ? entry.substring(1) : entry;
    if (e.isEmpty()) {
      return "is empty";
    }
    for (String segment : e.split("/", -1)) {
      if (segment.isEmpty()) {
        return "has an empty segment";
      }
      // * and ? are the glob's own; the rest as in an ID.
      String problem = Names.problem(segment.replace('?', '*'), "", FirestorePath.MAX_ID_BYTES);
      if (problem != null) {
        return problem;
      }
    }
    return null;
  }

  /** {@code projects/acme/databases/(default)}. */
  String databaseName() {
    return "projects/" + project + "/databases/" + database;
  }

  /** {@code projects/acme/databases/(default)/documents}: the parent of every path. */
  String documents() {
    return databaseName() + "/documents";
  }

  /** {@code acme/(default)}, for texts. */
  String label() {
    return project + "/" + database;
  }

  boolean restricted() {
    return !collections.isEmpty();
  }

  /** Whether agents may use the collection at {@code path} (an odd number of IDs). */
  boolean allowsCollection(FirestorePath path) {
    if (collections.isEmpty()) {
      return true;
    }
    if (!path.collection()) {
      return false;
    }
    for (String entry : collections) {
      String e = entry.startsWith("/") ? entry.substring(1) : entry;
      boolean pathGlob = entry.startsWith("/") || entry.contains("/");
      if (pathGlob ? Glob.path(e).matches(path.text()) : Glob.text(e).matches(path.id())) {
        return true;
      }
    }
    return false;
  }

  /** Whether agents may query every collection with this ID: an ID entry matches it. */
  boolean allowsGroup(String id) {
    if (collections.isEmpty()) {
      return true;
    }
    for (String entry : collections) {
      if (!entry.contains("/") && Glob.text(entry).matches(id)) {
        return true;
      }
    }
    return false;
  }
}
