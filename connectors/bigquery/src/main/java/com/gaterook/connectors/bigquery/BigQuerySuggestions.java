package com.gaterook.connectors.bigquery;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.cloudauth.GoogleLocal;
import com.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the BigQuery form ({@link com.gaterook.connector.api.Connector#suggest}). On this computer: the project
 * from a pasted service account key or gcloud, and a note while there is neither a Google sign-in nor a key. Signed in
 * as Test connection signs in: the project's datasets for Datasets, their locations for Location, and the projects
 * BigQuery lists for the credentials. One page of each read-only list ({@code datasets.list}, {@code projects.list}).
 */
final class BigQuerySuggestions {

  /** Datasets and projects one suggestion reads at most. */
  static final int PAGE = Suggestions.MAX_OPTIONS;
  private static final Pattern LOCATION = Pattern.compile("[A-Za-z0-9-]{2,40}");

  private BigQuerySuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      BigQueryApi api, CloudCredentials cloud, CloudLocal local) {
    Suggestions.Builder out = Suggestions.builder();
    GoogleLocal.Projects projects = GoogleLocal.projects(cfg, secrets, local, out);
    if (scope == Suggestions.Scope.SERVICE) {
      projects = service(cfg, secrets, ctx, api, cloud, projects, out);
    }
    GoogleLocal.suggest(out, projects, "/project", "");
    return out.build();
  }

  /** The datasets and their locations; the projects BigQuery lists after the local ones. */
  private static GoogleLocal.Projects service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      BigQueryApi api, CloudCredentials cloud, GoogleLocal.Projects projects, Suggestions.Builder out) {
    BigQuerySettings s;
    try {
      s = BigQuerySettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return projects;
    }
    // A suggestion's own credentials: nothing of the connection's cache, dropped when the answer is ready.
    ConnectionConfig own = new ConnectionConfig("suggest-" + UUID.randomUUID(), cfg.name(), cfg.type(), cfg.env(), 0,
        cfg.config(), cfg.secretFields(), cfg.metadata());
    BigQueryApi.Call c = new BigQueryApi.Call(own, s, secrets, ctx);
    try {
      if (!datasets(api, c, out)) {
        return projects;
      }
      return projects.plus("your BigQuery projects", projects(api, c));
    } finally {
      cloud.evict(own.id());
    }
  }

  /** {@code datasets.list} of the connection's project; whether it answered. */
  private static boolean datasets(BigQueryApi api, BigQueryApi.Call c, Suggestions.Builder out) {
    String project = c.settings().project();
    JsonNode r;
    try {
      r = api.get(c, BigQueryApi.path("projects", project, "datasets") + BigQueryApi.query("maxResults",
          Integer.toString(PAGE)), "BigQuery datasets.list");
    } catch (ConnectorException e) {
      GoogleLocal.failed(out, e);
      return false;
    }
    List<Option> datasets = new ArrayList<>();
    Map<String, Integer> locations = new LinkedHashMap<>();
    for (JsonNode d : r.path("datasets")) {
      String id = d.path("datasetReference").path("datasetId").asString("");
      if (!BigQuerySettings.DATASET.matcher(id).matches()) {
        continue;
      }
      String location = d.path("location").asString("");
      String name = OutputSanitizer.sanitize(d.path("friendlyName").asString("").strip());
      datasets.add(new Option(id, name.isEmpty() || name.equals(id) ? null : name,
          location.isEmpty() ? null : location));
      if (LOCATION.matcher(location).matches()) {
        locations.merge(location, 1, Integer::sum);
      }
    }
    boolean complete = r.path("nextPageToken").asString("").isEmpty();
    if (!datasets.isEmpty()) {
      out.field("/datasets", new Suggestions.Field(datasets, null, project, complete));
    }
    List<Option> where = new ArrayList<>();
    locations.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .forEach(e -> where.add(new Option(e.getKey(), null, e.getValue() + (e.getValue() == 1 ? " dataset"
            : " datasets"))));
    if (!where.isEmpty()) {
      out.field("/location", new Suggestions.Field(where, null, "the project's datasets", complete));
    }
    String count = datasets.size() + (complete ? "" : "+");
    out.note(datasets.isEmpty() ? "The credentials see no dataset in " + project + "."
        : count + (datasets.size() == 1 && complete ? " dataset" : " datasets") + " in " + project + ": choose the "
            + "ones agents may use, or leave Datasets empty for all of them.");
    return true;
  }

  /** The projects BigQuery lists for the credentials ({@code projects.list}); none when it refuses. */
  private static List<Option> projects(BigQueryApi api, BigQueryApi.Call c) {
    JsonNode r;
    try {
      r = api.get(c, "projects" + BigQueryApi.query("maxResults", Integer.toString(PAGE)), "BigQuery projects.list");
    } catch (ConnectorException e) {
      // Only a convenience: the project is typed already, and its datasets answered.
      return List.of();
    }
    List<Option> out = new ArrayList<>();
    for (JsonNode p : r.path("projects")) {
      String id = p.path("projectReference").path("projectId").asString("");
      if (!BigQuerySettings.PROJECT.matcher(id).matches()) {
        continue;
      }
      String name = OutputSanitizer.sanitize(p.path("friendlyName").asString("").strip());
      out.add(new Option(id, name.isEmpty() || name.equals(id) ? null : name, "BigQuery"));
    }
    return out;
  }
}
