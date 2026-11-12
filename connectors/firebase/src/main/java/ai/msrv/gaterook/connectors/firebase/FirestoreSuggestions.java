package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.cloudauth.GoogleLocal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Help for the Firestore form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}). On this computer: the project
 * from a pasted service account key or gcloud, and a note while there is neither a Google sign-in nor a key. Signed in
 * as Test connection signs in, with the same {@code datastore} scope: the project's databases in Native mode for
 * Database (the only one filled in when the project has no {@code (default)}), and the database's top-level collections
 * for Collections. One page of each read-only list ({@code databases.list}, {@code listCollectionIds}).
 */
final class FirestoreSuggestions {

  /** Collections one suggestion reads at most. */
  static final int PAGE = Suggestions.MAX_OPTIONS;

  private FirestoreSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      FirestoreApi api, CloudCredentials cloud, CloudLocal local) {
    Suggestions.Builder out = Suggestions.builder();
    GoogleLocal.suggest(out, GoogleLocal.projects(cfg, secrets, local, out), "/project", "");
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, api, cloud, out);
    }
    return out.build();
  }

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, FirestoreApi api,
      CloudCredentials cloud, Suggestions.Builder out) {
    FirestoreSettings s;
    try {
      s = FirestoreSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    // A suggestion's own credentials: nothing of the connection's cache, dropped when the answer is ready.
    ConnectionConfig own = new ConnectionConfig("suggest-" + UUID.randomUUID(), cfg.name(), cfg.type(), cfg.env(), 0,
        cfg.config(), cfg.secretFields(), cfg.metadata());
    GoogleHttp.Caller c = new GoogleHttp.Caller(own, s.credentials(), List.of(FirestoreApi.SCOPE), secrets, ctx);
    try {
      if (databases(api, c, s, out)) {
        collections(api, c, s, out);
      }
    } catch (ConnectorException e) {
      GoogleLocal.failed(out, e);
    } finally {
      cloud.evict(own.id());
    }
  }

  /** The project's databases; whether the connection's database may be one of them. */
  private static boolean databases(FirestoreApi api, GoogleHttp.Caller c, FirestoreSettings s,
      Suggestions.Builder out) throws ConnectorException {
    JsonNode r;
    try {
      r = api.get(c, api.databases(s));
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE || e.kind() == ConnectorException.Kind.TIMEOUT) {
        throw e;
      }
      // An account that may read documents but not list databases: its collections say the rest.
      return true;
    }
    List<Option> options = new ArrayList<>();
    List<String> datastore = new ArrayList<>();
    for (JsonNode d : r.path("databases")) {
      String name = d.path("name").asString("");
      String id = name.substring(name.lastIndexOf('/') + 1);
      if (!FirestoreSettings.DATABASE.matcher(id).matches()) {
        continue;
      }
      if (d.path("type").asString("").equals("DATASTORE_MODE")) {
        datastore.add(id);
        continue;
      }
      String location = d.path("locationId").asString("");
      options.add(new Option(id, null, location.isEmpty() ? null : location));
    }
    String chosen = s.database();
    boolean found = options.stream().anyMatch(o -> o.value().equals(chosen));
    // Without a (default) database, the project's only one is what an empty or default Database means to the person.
    String only = !found && chosen.equals(FirestoreSettings.DEFAULT_DATABASE) && options.size() == 1
        ? options.getFirst().value() : null;
    if (!options.isEmpty()) {
      out.field("/database", Suggestions.Field.value(s.project(), only, options));
    }
    if (found) {
      return true;
    }
    if (datastore.contains(chosen)) {
      out.note(chosen + " is a database in Datastore mode, which the Firestore API can't read: choose one in Native "
          + "mode.");
    } else if (only != null) {
      out.note(s.project() + " has no (default) database: its database is " + only + ".");
    } else {
      out.note(options.isEmpty() ? s.project() + " has no Firestore database in Native mode that the credentials can "
          + "see." : s.project() + " has no database " + chosen + ": choose one of its " + options.size()
          + (options.size() == 1 ? " database." : " databases."));
    }
    return false;
  }

  /** The database's top-level collections, as the connection's IDs name them. */
  private static void collections(FirestoreApi api, GoogleHttp.Caller c, FirestoreSettings s,
      Suggestions.Builder out) throws ConnectorException {
    JsonNode r = api.post(c, api.uri(s, "listCollectionIds"), JsonNodeFactory.instance.objectNode()
        .put("pageSize", PAGE), false);
    List<Option> options = new ArrayList<>();
    for (JsonNode id : r.path("collectionIds")) {
      String name = id.asString("");
      // An ID with * or ? would be a pattern on the list, allowing more than itself.
      if (FirestorePath.problem(name) == null && name.indexOf('*') < 0 && name.indexOf('?') < 0) {
        options.add(Option.of(name));
      }
    }
    boolean complete = r.path("nextPageToken").asString("").isEmpty();
    if (options.isEmpty()) {
      out.note(s.label() + " has no top-level collection the credentials can see.");
      return;
    }
    out.field("/collections", new Suggestions.Field(options, null, s.label(), complete));
    out.note(options.size() + (complete ? "" : "+") + (options.size() == 1 && complete ? " top-level collection"
        : " top-level collections") + " in " + s.label() + ": choose the ones agents may use, or leave Collections "
        + "empty for all of them.");
  }
}
