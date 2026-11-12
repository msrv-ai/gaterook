package ai.msrv.gaterook.connectors.athena;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.athena.AthenaApi.Call;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Athena form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}). On this computer: the
 * profiles of {@code ~/.aws/config} (the preferred one filled in when the credentials come from a profile), what keeps
 * the chosen profile from being used, and the regions with the profile's region filled in. Signed in as Test
 * connection signs in, under a session that may only list ({@link AthenaSettings#listPolicy}): the workgroups, the
 * chosen workgroup's results location, the data catalogs and the databases of the session catalog. Nothing runs a
 * query. A profile that runs a command for its credentials, or can't be used, is never signed in with; the
 * suggestion's sessions are dropped when it is done.
 */
final class AthenaSuggestions {

  static final String PROFILE = "/credentials/profile";
  static final String REGION = "/region";
  static final String WORKGROUP = "/workgroup";
  static final String CATALOG = "/catalog";
  static final String DATABASE = "/database";
  static final String DATABASES = "/databases";
  static final String OUTPUT = "/outputLocation";
  /** Said after what is wrong with a profile: why the form lists nothing from AWS. */
  static final String NO_SIGN_IN = "The setup helpers don't sign in with this profile, so nothing is listed from AWS.";
  /** Pages of 50 a listing reads: as many names as a field offers. */
  private static final int PAGES = 4;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private AthenaSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudCredentials cloud, CloudLocal local, AthenaApi api) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, cloud, api, out);
    } else {
      local(cfg, cloud, local, out);
    }
    return out.build();
  }

  private static void local(ConnectionConfig cfg, CloudCredentials cloud, CloudLocal local, Suggestions.Builder out) {
    boolean fromProfile = "profile".equals(text(cfg, "/credentials/source"));
    String profile = fromProfile ? text(cfg, PROFILE) : null;
    local.aws(out, fromProfile ? PROFILE : null, profile, false, REGION);
    if (!fromProfile) {
      return;
    }
    if (local.awsProfiles().isEmpty()) {
      out.note("There are no AWS profiles on this computer: run aws configure, or choose Access key.");
    } else if (profile != null) {
      // Which files are read doesn't depend on the region: any valid one makes the spec.
      cloud.profileWarnings(new AwsSpec(AwsSpec.Source.PROFILE, null, null, profile, null, null, 900, "us-east-1"))
          .forEach(out::note);
    }
  }

  /** One page's names and what else a listing says. */
  private record Listing(List<JsonNode> items, boolean complete) {}

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, CloudCredentials cloud,
      AthenaApi api, Suggestions.Builder out) {
    AthenaSettings s;
    try {
      s = AthenaSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    if (s.credentials().source() == AwsSpec.Source.PROFILE) {
      List<String> warnings = cloud.profileWarnings(s.credentials());
      if (!warnings.isEmpty()) {
        warnings.forEach(out::note);
        out.note(NO_SIGN_IN);
        return;
      }
    }
    // Its own credentials, dropped afterwards: a form asks again with every change.
    ConnectionConfig own = own(cfg);
    Call c = new Call(own, s, secrets, ctx);
    String policy = s.listPolicy();
    List<String> notes = new ArrayList<>();
    String arn = null;
    try {
      try {
        AwsCredentialsProvider provider = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(own.id(),
            own.version(), own.name()), s.credentials(), policy, secrets), ctx::taint);
        arn = cloud.callerIdentity(provider, s.region()).arn();
      } catch (SdkException e) {
        throw ConnectorException.unavailable("The AWS credentials couldn't be loaded: "
            + AthenaApi.firstLine(e.getMessage()) + ".", e);
      }
      boolean workgroupExists = workgroups(api, c, policy, out, notes);
      if (workgroupExists) {
        results(api, c, policy, out, notes);
      }
      catalogs(api, c, policy, workgroupExists, out, notes);
      String databases = databases(api, c, policy, workgroupExists, out);
      out.note("Signed in as " + arn + "." + (databases == null ? "" : " " + databases));
    } catch (ConnectorException e) {
      out.note(arn == null ? e.getMessage() : "Signed in as " + arn + ". " + e.getMessage());
    } finally {
      cloud.evict(own.id());
    }
    notes.forEach(out::note);
  }

  /** The workgroups for Workgroup; whether the chosen one exists (or may, when they can't be listed). */
  private static boolean workgroups(AthenaApi api, Call c, String policy, Suggestions.Builder out, List<String> notes)
      throws ConnectorException {
    AthenaSettings s = c.settings();
    Listing l = list(api, c, policy, "ListWorkGroups", F.objectNode().put("MaxResults", 50), "WorkGroups");
    if (l == null) {
      return true;
    }
    List<Option> options = new ArrayList<>();
    List<String> enabled = new ArrayList<>();
    JsonNode chosen = null;
    for (JsonNode wg : l.items()) {
      String name = wg.path("Name").asString("");
      if (!AthenaSettings.WORKGROUP.matcher(name).matches()) {
        continue;
      }
      String state = wg.path("State").asString("");
      if ("ENABLED".equals(state)) {
        enabled.add(name);
      }
      String engine = wg.path("EngineVersion").path("EffectiveEngineVersion").asString(
          wg.path("EngineVersion").path("SelectedEngineVersion").asString(""));
      options.add(new Option(name, null, state.toLowerCase(Locale.ROOT) + (engine.isEmpty() ? "" : " · " + engine)));
      if (name.equals(s.workgroup())) {
        chosen = wg;
      }
    }
    out.field(WORKGROUP, new Suggestions.Field(options, enabled.size() == 1 ? enabled.getFirst() : null,
        "the account", l.complete()));
    if (chosen == null) {
      if (l.complete()) {
        notes.add("There is no workgroup " + s.workgroup() + " in " + s.region() + (enabled.isEmpty() ? "."
            : ": choose one of " + String.join(", ", enabled.subList(0, Math.min(5, enabled.size())))
                + (enabled.size() > 5 ? ", …" : "") + "."));
        return false;
      }
      return true;
    }
    if (!"ENABLED".equals(chosen.path("State").asString(""))) {
      notes.add("The workgroup " + s.workgroup() + " is disabled: enable it or choose another.");
    }
    return true;
  }

  /** The chosen workgroup's results location, and what to do when it has none. */
  private static void results(AthenaApi api, Call c, String policy, Suggestions.Builder out, List<String> notes)
      throws ConnectorException {
    AthenaSettings s = c.settings();
    JsonNode r = call(api, c, policy, "GetWorkGroup", F.objectNode().put("WorkGroup", s.workgroup()));
    if (r == null) {
      return;
    }
    AthenaConnector.Workgroup wg = AthenaConnector.Workgroup.of(r);
    if (wg.outputLocation() != null && !wg.outputLocation().isBlank()) {
      out.options(OUTPUT, "the workgroup", List.of(new Option(wg.outputLocation(), null,
          wg.enforced() ? "the workgroup's location, for every query" : "the workgroup's location")));
    } else if (!wg.managed() && s.outputLocation() == null) {
      notes.add("The workgroup " + s.workgroup() + " has no query results location: enter one under Query results "
          + "location, such as s3://<bucket>/athena-results/.");
    }
  }

  /** The data catalogs for Data catalog. */
  private static void catalogs(AthenaApi api, Call c, String policy, boolean workgroup, Suggestions.Builder out,
      List<String> notes) throws ConnectorException {
    AthenaSettings s = c.settings();
    ObjectNode req = F.objectNode().put("MaxResults", 50);
    if (workgroup) {
      req.put("WorkGroup", s.workgroup());
    }
    Listing l = list(api, c, policy, "ListDataCatalogs", req, "DataCatalogsSummary");
    if (l == null) {
      return;
    }
    List<Option> options = new ArrayList<>();
    for (JsonNode d : l.items()) {
      String name = d.path("CatalogName").asString("");
      if (AthenaSettings.CATALOG.matcher(name).matches()) {
        String type = d.path("Type").asString("");
        options.add(new Option(name, null, type.isEmpty() ? null : type));
      }
    }
    out.field(CATALOG, new Suggestions.Field(options, null, "the account", l.complete()));
    if (l.complete() && options.stream().noneMatch(o -> o.value().equalsIgnoreCase(s.catalog()))) {
      notes.add("There is no data catalog " + s.catalog() + " in " + s.region() + ".");
    }
  }

  /**
   * The databases of the session catalog for Databases, and those agents may use for Default database (the only one
   * filled in); what the note says of them, or {@code null} when they can't be listed.
   */
  private static String databases(AthenaApi api, Call c, String policy, boolean workgroup, Suggestions.Builder out)
      throws ConnectorException {
    AthenaSettings s = c.settings();
    ObjectNode req = F.objectNode().put("CatalogName", s.catalog()).put("MaxResults", 50);
    if (workgroup) {
      req.put("WorkGroup", s.workgroup());
    }
    Listing l;
    try {
      l = pages(api, c, policy, "ListDatabases", req, "DatabaseList");
    } catch (ConnectorException e) {
      if (e.kind() != ConnectorException.Kind.RESOURCE_ERROR && e.kind() != ConnectorException.Kind.BAD_INPUT) {
        throw e;
      }
      return "The databases of " + s.catalog() + " can't be listed: " + e.getMessage();
    }
    List<Option> all = new ArrayList<>();
    List<Option> allowed = new ArrayList<>();
    for (JsonNode d : l.items()) {
      String name = d.path("Name").asString("");
      if (!AthenaSettings.DATABASE.matcher(name).matches()) {
        continue;
      }
      String description = d.path("Description").asString("").strip();
      Option o = new Option(name, null, description.isEmpty() ? null : cut(description, 120));
      all.add(o);
      if (s.allows(s.catalog(), name)) {
        allowed.add(o);
      }
    }
    if (all.isEmpty()) {
      return "The credentials see no databases in " + s.catalog() + ".";
    }
    out.field(DATABASES, new Suggestions.Field(all, null, s.catalog(), l.complete()));
    out.field(DATABASE, new Suggestions.Field(allowed, allowed.size() == 1 && l.complete()
        ? allowed.getFirst().value() : null, s.catalog(), l.complete()));
    return all.size() + (l.complete() ? "" : "+") + (all.size() == 1 ? " database" : " databases") + " in "
        + s.catalog() + ": choose the ones agents may use.";
  }

  /** A listing that the credentials may not read is left out ({@code null}); other failures stop the suggestions. */
  private static Listing list(AthenaApi api, Call c, String policy, String operation, ObjectNode req, String items)
      throws ConnectorException {
    try {
      return pages(api, c, policy, operation, req, items);
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_ERROR || e.kind() == ConnectorException.Kind.BAD_INPUT) {
        return null;
      }
      throw e;
    }
  }

  private static JsonNode call(AthenaApi api, Call c, String policy, String operation, ObjectNode req)
      throws ConnectorException {
    try {
      return api.call(c, operation, req, policy, false);
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_ERROR || e.kind() == ConnectorException.Kind.BAD_INPUT) {
        return null;
      }
      throw e;
    }
  }

  /** Up to {@link #PAGES} pages of a listing; complete when Athena has no more. */
  private static Listing pages(AthenaApi api, Call c, String policy, String operation, ObjectNode req, String items)
      throws ConnectorException {
    List<JsonNode> out = new ArrayList<>();
    String token = null;
    for (int page = 0; page < PAGES; page++) {
      ObjectNode r = req.deepCopy();
      if (token != null) {
        r.put("NextToken", token);
      }
      JsonNode answer = api.call(c, operation, r, policy, false);
      answer.path(items).forEach(out::add);
      token = answer.path("NextToken").asString(null);
      if (token == null || token.isEmpty()) {
        return new Listing(out, true);
      }
      if (c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    return new Listing(out, false);
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /** The draft under an owner of its own: the credentials a suggestion makes are dropped afterwards. */
  static ConnectionConfig own(ConnectionConfig cfg) {
    return new ConnectionConfig(cfg.id() + "#suggest", cfg.name(), cfg.type(), cfg.env(), cfg.version(), cfg.config(),
        cfg.secretFields(), cfg.metadata());
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
