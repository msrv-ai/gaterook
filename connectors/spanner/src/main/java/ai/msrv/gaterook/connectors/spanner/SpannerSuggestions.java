package ai.msrv.gaterook.connectors.spanner;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Spanner form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}). On this computer: the project
 * from a pasted service account key or gcloud, and a note while there is neither a Google sign-in nor a key. Signed in
 * as Test connection signs in: the project's instances, the databases of the instance with their dialects, and the
 * database roles for Database role; a note when the instance or the database typed isn't there. One page of each
 * read-only list ({@code instances.list}, {@code databases.list}, {@code databaseRoles.list}); no session is opened.
 */
final class SpannerSuggestions {

  /** Instances, databases and roles one suggestion reads at most. */
  static final int PAGE = Suggestions.MAX_OPTIONS;
  /** Every database has them: {@code public} is everyone's, the others read the schema and the statistics. */
  private static final Set<String> SYSTEM_ROLES = Set.of("public", "spanner_info_reader", "spanner_sys_reader");

  private SpannerSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      SpannerApi api, CloudCredentials cloud, CloudLocal local) {
    Suggestions.Builder out = Suggestions.builder();
    GoogleLocal.suggest(out, GoogleLocal.projects(cfg, secrets, local, out), "/project", "");
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, api, cloud, out);
    }
    return out.build();
  }

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, SpannerApi api,
      CloudCredentials cloud, Suggestions.Builder out) {
    SpannerSettings s;
    try {
      s = SpannerSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    // A suggestion's own credentials: nothing of the connection's cache, dropped when the answer is ready.
    ConnectionConfig own = new ConnectionConfig("suggest-" + UUID.randomUUID(), cfg.name(), cfg.type(), cfg.env(), 0,
        cfg.config(), cfg.secretFields(), cfg.metadata());
    SpannerApi.Call c = new SpannerApi.Call(own, s, secrets, ctx);
    try {
      if (instances(api, c, out) && databases(api, c, out)) {
        roles(api, c, out);
      }
    } catch (ConnectorException e) {
      GoogleLocal.failed(out, e);
    } finally {
      cloud.evict(own.id());
    }
  }

  /** The project's instances; whether the instance typed may be there. */
  private static boolean instances(SpannerApi api, SpannerApi.Call c, Suggestions.Builder out)
      throws ConnectorException {
    SpannerSettings s = c.settings();
    String what = "Spanner instances.list";
    SpannerApi.Response r = api.read(c, SpannerApi.path("projects", s.project(), "instances") + "?pageSize=" + PAGE,
        what);
    if (r.status() == 403) {
      out.note("The credentials can't list the instances of " + s.project() + " (spanner.instances.list, in "
          + "roles/spanner.viewer). Check the instance id in the Google Cloud console.");
      return true;
    }
    if (r.status() / 100 != 2) {
      GoogleLocal.failed(out, SpannerApi.error(r.status(), r.tree(), what, c, false));
      return false;
    }
    List<Option> options = new ArrayList<>();
    for (JsonNode i : r.tree().path("instances")) {
      String id = last(i.path("name").asString(""));
      if (!SpannerSettings.INSTANCE.matcher(id).matches()) {
        continue;
      }
      String name = OutputSanitizer.sanitize(i.path("displayName").asString("").strip());
      options.add(new Option(id, name.isEmpty() || name.equals(id) ? null : name,
          detail(last(i.path("config").asString("")), i.path("state").asString(""))));
    }
    boolean complete = r.tree().path("nextPageToken").asString("").isEmpty();
    if (!options.isEmpty()) {
      out.field("/instance", new Suggestions.Field(options, null, s.project(), complete));
    }
    if (complete && options.stream().noneMatch(o -> o.value().equals(s.instance()))) {
      out.note(options.isEmpty() ? s.project() + " has no Spanner instance the credentials can see."
          : s.project() + " has no instance " + s.instance() + ": choose one of its " + options.size()
              + (options.size() == 1 ? " instance." : " instances."));
      return false;
    }
    return true;
  }

  /** The instance's databases with their dialects; whether the database typed is there. */
  private static boolean databases(SpannerApi api, SpannerApi.Call c, Suggestions.Builder out)
      throws ConnectorException {
    SpannerSettings s = c.settings();
    String what = "Spanner databases.list";
    SpannerApi.Response r = api.read(c, SpannerApi.path("projects", s.project(), "instances", s.instance(),
        "databases") + "?pageSize=" + PAGE, what);
    if (r.status() == 403) {
      out.note("The credentials can't list the databases of " + s.instance() + " (spanner.databases.list, in "
          + "roles/spanner.viewer). Check the database id in the Google Cloud console.");
      return true;
    }
    if (r.status() == 404) {
      out.note(s.project() + " has no instance " + s.instance() + ".");
      return false;
    }
    if (r.status() / 100 != 2) {
      GoogleLocal.failed(out, SpannerApi.error(r.status(), r.tree(), what, c, false));
      return false;
    }
    List<Option> options = new ArrayList<>();
    String dialect = null;
    for (JsonNode d : r.tree().path("databases")) {
      String id = last(d.path("name").asString(""));
      if (!SpannerSettings.DATABASE.matcher(id).matches()) {
        continue;
      }
      String language = dialect(d.path("databaseDialect").asString(""));
      options.add(new Option(id, null, detail(language, d.path("state").asString(""))));
      if (id.equals(s.database())) {
        dialect = language;
      }
    }
    boolean complete = r.tree().path("nextPageToken").asString("").isEmpty();
    if (!options.isEmpty()) {
      out.field("/database", new Suggestions.Field(options, null, s.instance(), complete));
    }
    boolean found = options.stream().anyMatch(o -> o.value().equals(s.database()));
    if (!found && complete) {
      out.note(options.isEmpty() ? s.instance() + " has no database the credentials can see."
          : s.instance() + " has no database " + s.database() + ": choose one of its " + options.size()
              + (options.size() == 1 ? " database." : " databases."));
      return false;
    }
    if (found) {
      out.note("Found " + s.label() + (dialect == null ? "." : ", a " + (dialect.equals("PostgreSQL")
          ? "PostgreSQL-dialect" : dialect) + " database."));
    }
    return true;
  }

  /** The database's roles of fine-grained access control, the system roles last; none when they can't be listed. */
  private static void roles(SpannerApi api, SpannerApi.Call c, Suggestions.Builder out) throws ConnectorException {
    SpannerApi.Response r = api.read(c, c.database() + "/databaseRoles?pageSize=" + PAGE,
        "Spanner databaseRoles.list");
    if (r.status() / 100 != 2) {
      // An optional setting: an account without spanner.databaseRoles.list simply gets no help with it.
      return;
    }
    List<Option> own = new ArrayList<>();
    List<Option> system = new ArrayList<>();
    for (JsonNode role : r.tree().path("databaseRoles")) {
      String name = last(role.path("name").asString(""));
      if (!SpannerSettings.ROLE.matcher(name).matches() || name.equals("public")) {
        // public is every principal's role: as the database role it would give agents nothing of their own.
        continue;
      }
      if (SYSTEM_ROLES.contains(name)) {
        system.add(new Option(name, null, "system role"));
      } else {
        own.add(Option.of(name));
      }
    }
    own.addAll(system);
    if (!own.isEmpty()) {
      out.field("/advanced/databaseRole", new Suggestions.Field(own, null, c.settings().database(),
          r.tree().path("nextPageToken").asString("").isEmpty()));
    }
  }

  /** The last segment of a resource name: {@code orders} of {@code projects/p/instances/i/databases/orders}. */
  private static String last(String name) {
    return name.substring(name.lastIndexOf('/') + 1);
  }

  private static String dialect(String databaseDialect) {
    return switch (databaseDialect) {
      case "GOOGLE_STANDARD_SQL" -> "GoogleSQL";
      case "POSTGRESQL" -> "PostgreSQL";
      default -> null;
    };
  }

  /** One short fact, and the state when it isn't ready ({@code CREATING}). */
  private static String detail(String fact, String state) {
    String s = state.isEmpty() || state.startsWith("READY") ? null : state.toLowerCase(Locale.ROOT);
    String f = fact == null || fact.isEmpty() ? null : fact;
    return f == null ? s : s == null ? f : f + ", " + s;
  }
}
