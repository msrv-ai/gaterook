package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.connectors.cloudauth.GoogleApis;
import ai.msrv.gaterook.connectors.support.Glob;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings of one Realtime Database connection ({@code firebase-rtdb/config.schema.json}): the database's URL, one
 * of the hosts Google runs for it ({@link GoogleApis#realtimeDatabase}) and nothing else, the Google credentials of
 * connectors/cloud-auth, and the top-level paths agents may use.
 *
 * @param database the database's origin, {@code https://<host>}: no port, path, query or fragment
 * @param paths    globs of top-level keys ({@code users}, {@code public-*}); empty: the whole database
 */
record RtdbSettings(URI database, List<String> paths, GcpSpec credentials) {

  RtdbSettings {
    paths = List.copyOf(paths);
  }

  static RtdbSettings of(ConnectionConfig cfg) throws ConnectorException {
    String url = cfg.string("/databaseUrl");
    URI database = url == null ? null : database(url);
    if (database == null) {
      throw ConnectorException.badInput((url == null || url.isBlank() ? "The connection names no database URL"
          : "\"" + Names.shown(url) + "\" isn't the URL of a Firebase Realtime Database") + ". Use the URL the "
          + "Firebase console shows: https://<database>.firebaseio.com or "
          + "https://<database>.<region>.firebasedatabase.app.");
    }
    List<String> paths = new ArrayList<>();
    for (String entry : cfg.strings("/paths")) {
      // * and ? are the glob's own; the rest as in a key.
      String problem = Names.problem(entry.replace('?', '*'), RtdbPath.FORBIDDEN, RtdbPath.MAX_KEY_BYTES);
      if (problem != null) {
        throw ConnectorException.badInput("The path \"" + Names.shown(entry) + "\" of " + cfg.name() + " " + problem
            + ": name top-level keys such as users or public-*.");
      }
      paths.add(entry);
    }
    GcpSpec spec;
    try {
      spec = GcpSpec.fromConfig(cfg.config(), "/credentials");
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The Google credentials of " + cfg.name() + " aren't complete: "
          + e.getMessage() + ".");
    }
    return new RtdbSettings(database, paths, spec);
  }

  /** {@code https://<host>} of a database URL, or {@code null} when it isn't one: a path, query or fragment too. */
  static URI database(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
    String path = uri.getRawPath();
    if (!GoogleApis.realtimeDatabase(uri) || uri.getRawQuery() != null || uri.getRawFragment() != null
        || (path != null && !path.isEmpty() && !path.equals("/"))) {
      return null;
    }
    return URI.create("https://" + uri.getHost().toLowerCase(Locale.ROOT));
  }

  String host() {
    return database.getHost();
  }

  boolean restricted() {
    return !paths.isEmpty();
  }

  /** Whether agents may use the top-level key {@code top} and everything under it. */
  boolean allows(String top) {
    if (paths.isEmpty()) {
      return true;
    }
    for (String glob : paths) {
      if (Glob.text(glob).matches(top)) {
        return true;
      }
    }
    return false;
  }
}
