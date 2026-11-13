package com.gaterook.connectors.firebase;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.cloudauth.GoogleLocal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Realtime Database form ({@link com.gaterook.connector.api.Connector#suggest}). On this computer:
 * the URL a project's default database has ({@code https://<project>-default-rtdb.firebaseio.com}), offered for the
 * projects of a pasted service account key, gcloud and the service account to impersonate, never filled in; and a note
 * while there is neither a Google sign-in nor a key. Signed in: the project's databases from the Firebase Realtime
 * Database Management API ({@code instances.list}, read-only, with the {@code firebase.readonly} scope) for Database
 * URL, and the database's top-level keys for Paths, read as Test connection reads them ({@code shallow=true}).
 */
final class RtdbSuggestions {

  static final URI MANAGEMENT = URI.create("https://firebasedatabase.googleapis.com");
  /** Read-only access to Firebase's management APIs: enough to list a project's databases, nothing else. */
  static final String FIREBASE_READONLY = "https://www.googleapis.com/auth/firebase.readonly";
  static final String MANAGEMENT_WHAT = "The Firebase Realtime Database Management API";
  /** Projects whose databases one suggestion lists at most. */
  static final int MAX_PROJECTS = 2;
  /** A default database is named after its project. */
  private static final Pattern DEFAULT_DATABASE = Pattern.compile("([a-z][a-z0-9-]{4,28}[a-z0-9])-default-rtdb");
  private static final Pattern LOCATION = Pattern.compile("/locations/([a-z0-9-]{1,40})/");

  private RtdbSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      GoogleHttp http, RtdbApi api, CloudCredentials cloud, CloudLocal local) {
    Suggestions.Builder out = Suggestions.builder();
    GoogleLocal.Projects projects = GoogleLocal.projects(cfg, secrets, local, out);
    List<Option> defaults = projects.options().stream().map(o -> new Option("https://" + o.value()
        + "-default-rtdb.firebaseio.com", null, "default database of " + o.value() + ", if it has one")).toList();
    out.options("/databaseUrl", projects.source(), defaults);
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, http, api, cloud, projects, out);
    }
    return out.build();
  }

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, GoogleHttp http,
      RtdbApi api, CloudCredentials cloud, GoogleLocal.Projects projects, Suggestions.Builder out) {
    RtdbSettings s;
    try {
      s = RtdbSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    // A suggestion's own credentials: nothing of the connection's cache, dropped when the answer is ready.
    ConnectionConfig own = new ConnectionConfig("suggest-" + UUID.randomUUID(), cfg.name(), cfg.type(), cfg.env(), 0,
        cfg.config(), cfg.secretFields(), cfg.metadata());
    try {
      try {
        databases(http, cloud, new GoogleHttp.Caller(own, s.credentials(), List.of(FIREBASE_READONLY), secrets, ctx),
            s, projects, out);
      } catch (ConnectorException e) {
        // The database itself may still answer: its paths don't need the management API.
        GoogleLocal.failed(out, e);
      }
      paths(http, api, new GoogleHttp.Caller(own, s.credentials(), RtdbApi.SCOPES, secrets, ctx), s, out);
    } catch (ConnectorException e) {
      GoogleLocal.failed(out, e);
    } finally {
      cloud.evict(own.id());
    }
  }

  /**
   * The databases of the project the URL names ({@code <project>-default-rtdb}), else of the form's projects, at most
   * {@link #MAX_PROJECTS}: the first project with databases answers.
   */
  private static void databases(GoogleHttp http, CloudCredentials cloud, GoogleHttp.Caller c, RtdbSettings s,
      GoogleLocal.Projects projects, Suggestions.Builder out) throws ConnectorException {
    Set<String> candidates = new LinkedHashSet<>();
    Matcher named = DEFAULT_DATABASE.matcher(s.host().substring(0, s.host().indexOf('.')));
    if (named.matches()) {
      candidates.add(named.group(1));
    }
    projects.options().forEach(o -> candidates.add(o.value()));
    URI test = cloud.endpoints().service("gcp.firebasedatabase");
    String base = (test != null ? test : MANAGEMENT).toString().replaceAll("/$", "");
    String refused = null;
    int asked = 0;
    for (String project : candidates) {
      if (asked++ >= MAX_PROJECTS) {
        break;
      }
      URI uri = URI.create(base + "/v1beta/projects/" + project + "/locations/-/instances?pageSize=100");
      Map<String, List<String>> headers = http.token(c, uri, cloud::sendsGoogleTokensTo, true);
      List<Option> options = new ArrayList<>();
      boolean complete;
      try (GoogleHttp.Answer a = http.send(c.ctx(), "GET", uri, headers, null, MANAGEMENT_WHAT, false)) {
        if (a.status == 403 || a.status == 404) {
          if (refused == null) {
            refused = a.status == 403 ? "The credentials can't list the Realtime Databases of " + project
                + " (firebasedatabase.instances.list, in roles/firebasedatabase.viewer)." : null;
          }
          continue;
        }
        if (a.status == 401) {
          throw ConnectorException.unavailable(MANAGEMENT_WHAT + " refused the token. Sign in again (gcloud auth "
              + "application-default login) or check the key.", null);
        }
        if (!a.ok()) {
          throw ConnectorException.resourceError(MANAGEMENT_WHAT + " answered " + a.status + " for " + project + ".",
              null);
        }
        JsonNode r = a.json(GoogleHttp.MAX_BODY);
        for (JsonNode i : r.path("instances")) {
          URI database = RtdbSettings.database(i.path("databaseUrl").asString(""));
          if (database == null || !i.path("state").asString("ACTIVE").equals("ACTIVE")) {
            continue;
          }
          Matcher location = LOCATION.matcher(i.path("name").asString(""));
          String where = location.find() ? location.group(1) : null;
          boolean dflt = i.path("type").asString("").equals("DEFAULT_DATABASE");
          options.add(new Option(database.toString(), null, where == null ? (dflt ? "default" : null)
              : dflt ? where + ", default" : where));
        }
        complete = r.path("nextPageToken").asString("").isEmpty();
      }
      if (options.isEmpty()) {
        continue;
      }
      out.field("/databaseUrl", new Suggestions.Field(options, null, project, complete));
      boolean found = options.stream().anyMatch(o -> URI.create(o.value()).getHost().equals(s.host()));
      if (!found && complete) {
        out.note(s.database() + " isn't a database of " + project + ": choose one of its " + options.size()
            + (options.size() == 1 ? " database." : " databases."));
      }
      return;
    }
    if (refused != null) {
      out.note(refused);
    }
  }

  /** The database's top-level keys, for Paths: what Test connection reads, within the same bound. */
  private static void paths(GoogleHttp http, RtdbApi api, GoogleHttp.Caller c, RtdbSettings s,
      Suggestions.Builder out) throws ConnectorException {
    boolean personal = GoogleIdentity.personal(http.credentials(c));
    JsonNode root;
    try {
      root = api.get(c, api.uri(s, RtdbPath.ROOT, "shallow=true"), RtdbConnector.PROBE_BYTES, 256, personal);
    } catch (BoundedJson.TooLarge e) {
      out.note(s.host() + " has more top-level paths than a suggestion reads: type the ones agents may use.");
      return;
    }
    List<Option> options = new ArrayList<>();
    if (root != null && root.isObject()) {
      for (String key : root.propertyNames()) {
        // A key with * would be a pattern on the list, allowing more than itself.
        if (RtdbPath.problem(key) == null && key.indexOf('*') < 0) {
          options.add(Option.of(key));
        }
      }
    }
    if (options.isEmpty()) {
      out.note(root == null || root.isNull() ? s.host() + " is empty." : s.host() + " has no top-level path to "
          + "choose.");
      return;
    }
    boolean complete = options.size() <= Suggestions.MAX_OPTIONS;
    out.field("/paths", new Suggestions.Field(options, null, "the database", complete));
    out.note(Math.min(options.size(), Suggestions.MAX_OPTIONS) + (complete ? "" : "+") + (options.size() == 1
        ? " top-level path" : " top-level paths") + " in " + s.host() + ": choose the ones agents may use, or leave "
        + "Paths empty for the whole database.");
  }
}
