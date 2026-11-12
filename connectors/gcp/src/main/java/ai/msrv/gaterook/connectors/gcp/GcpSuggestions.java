package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.cloudauth.GoogleLocal;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the GCP form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}). On this computer: the project for
 * the resource scopes ({@code projects/<id>}) from a pasted service account key or gcloud, and a note while there is
 * neither a Google sign-in nor a key. Signed in: the projects inside the connection's scopes, for Metrics projects,
 * found in Cloud Asset as Test connection finds resources (folders and organizations searched, at most
 * {@link #MAX_SEARCHES} of them). Projects to pick as scopes come from the form's project picker
 * ({@code discoverProjects}), not from here.
 */
final class GcpSuggestions {

  static final String PROJECT_TYPE = "cloudresourcemanager.googleapis.com/Project";
  /** Folders, organizations and project numbers looked up in Cloud Asset for one suggestion, at most. */
  static final int MAX_SEARCHES = 5;
  static final String METRICS_SOURCE = "the connection's scopes";
  private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");

  private GcpSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudCredentials cloud, CloudLocal local, HttpClient http) {
    Suggestions.Builder out = Suggestions.builder();
    GoogleLocal.suggest(out, GoogleLocal.projects(cfg, secrets, local, out), "/scopes", "projects/");
    if (scope == Suggestions.Scope.SERVICE) {
      metricsProjects(cfg, secrets, ctx, cloud, http, out);
    }
    return out.build();
  }

  /**
   * The projects of the scopes, for {@code /metricsProjects}: a project scope as it is, the projects under a folder or
   * an organization and the ID of a project scope named by its number from Cloud Asset. Only active projects.
   */
  private static void metricsProjects(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      CloudCredentials cloud, HttpClient http, Suggestions.Builder out) {
    Settings s;
    try {
      s = Settings.parse(cfg);
    } catch (Settings.InvalidSettingsException e) {
      out.note(e.getMessage());
      return;
    }
    // A suggestion's own credentials: nothing of the connection's cache, dropped when the answer is ready.
    CloudCredentials.Owner owner = new CloudCredentials.Owner("suggest-" + UUID.randomUUID(), 0, cfg.name());
    Map<String, Option> found = new LinkedHashMap<>();
    boolean complete = true;
    int searched = 0;
    int listed = 0;
    try {
      GoogleApi api = new GoogleApi(http, cloud, owner, s, secrets, ctx);
      Call call = new Call(cfg, s, GcpPolicy.defaults(cfg.env()), api, cloud.endpoints(), ctx);
      String base = call.base("gcp.cloudasset", GoogleApi.ASSET).toString().replaceAll("/$", "");
      for (String scope : s.scopes()) {
        String id = scope.startsWith("projects/") ? scope.substring("projects/".length()) : null;
        if (id != null && PROJECT_ID.matcher(id).matches()) {
          found.putIfAbsent(id, new Option(id, null, "a scope"));
          continue;
        }
        if (found.size() >= Suggestions.MAX_OPTIONS || searched >= MAX_SEARCHES
            || (searched > 0 && !call.timeForAnotherPage())) {
          complete = false;
          break;
        }
        searched++;
        JsonNode page;
        try {
          call.assetQuota();
          page = api.get(URI.create(base + "/v1/" + scope + ":searchAllResources?"
              + Call.param("assetTypes", PROJECT_TYPE) + "&" + Call.param("pageSize", "200") + "&"
              + Call.param("readMask", "name,project,displayName,state,additionalAttributes")), "Cloud Asset");
        } catch (GoogleApi.GoogleException e) {
          if (e.status != 403 && e.status != 404) {
            throw e;
          }
          // Another scope may still answer: say what this one needs.
          out.note("Can't list the projects in " + scope + ": " + e.getMessage());
          complete = false;
          continue;
        }
        for (JsonNode r : page.path("results")) {
          String projectId = r.path("additionalAttributes").path("projectId").asString("");
          String state = r.path("state").asString("ACTIVE");
          if (!PROJECT_ID.matcher(projectId).matches() || !state.equals("ACTIVE")) {
            continue;
          }
          String name = OutputSanitizer.sanitize(r.path("displayName").asString("").strip());
          if (found.putIfAbsent(projectId, new Option(projectId, name.isEmpty() || name.equals(projectId) ? null
              : name, scope.startsWith("projects/") ? scope : "in " + scope)) == null) {
            listed++;
          }
        }
        if (!page.path("nextPageToken").asString("").isEmpty()) {
          complete = false;
        }
      }
    } catch (ConnectorException e) {
      GoogleLocal.failed(out, e);
      complete = false;
    } finally {
      cloud.evict(owner.id());
    }
    List<Option> options = new ArrayList<>(found.values());
    if (options.isEmpty()) {
      return;
    }
    out.field("/metricsProjects", new Suggestions.Field(options, null, METRICS_SOURCE, complete));
    boolean projectScope = s.scopes().stream().anyMatch(scope -> scope.startsWith("projects/"));
    String count = options.size() + (complete ? "" : "+") + (options.size() == 1 && complete ? " project"
        : " projects");
    if (!projectScope && s.metricsProjects().isEmpty()) {
      out.note("gcp_metrics has no project to read yet: choose metrics projects among the " + count + " in the "
          + "scopes.");
    } else if (listed > 0) {
      out.note(count + " in the connection's scopes: add those gcp_metrics may read under Metrics projects.");
    }
  }
}
