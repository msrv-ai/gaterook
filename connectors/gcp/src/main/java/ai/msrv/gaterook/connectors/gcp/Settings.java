package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A GCP connection as its form writes it (report 09 §4): the projects, folders or organizations an agent may search
 * ({@code scopes}), the projects whose metrics it may read ({@code metricsProjects}, by default the projects among the
 * scopes), the credentials and whether one resource is read live from its service's API. Nothing here is a secret:
 * the service account key is a secret field the call resolves.
 *
 * @param projectNumbers project id → number, as Test connection found them ({@code metadata.projects}); empty before
 *                       the first test. Used only to order scopes when a resource name names its project by number.
 */
record Settings(List<String> scopes, List<String> metricsProjects, GcpSpec credentials, boolean liveGet,
    Map<String, String> projectNumbers) {

  static final Pattern SCOPE = Pattern.compile(
      "^(projects/([a-z][a-z0-9-]{4,28}[a-z0-9]|\\d{1,19})|folders/\\d{1,19}|organizations/\\d{1,19})$");
  static final Pattern PROJECT = Pattern.compile("^([a-z][a-z0-9-]{4,28}[a-z0-9]|\\d{1,19})$");
  private static final Pattern SERVICE_ACCOUNT_PROJECT =
      Pattern.compile("^[a-z0-9-]+@([a-z][a-z0-9-]{4,28}[a-z0-9])\\.iam\\.gserviceaccount\\.com$");

  /** Settings that can't be used; every call of the connection is denied with this message. */
  static final class InvalidSettingsException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidSettingsException(String message) {
      super(message);
    }
  }

  Settings {
    scopes = List.copyOf(scopes);
    metricsProjects = List.copyOf(metricsProjects);
    projectNumbers = Map.copyOf(projectNumbers);
  }

  static Settings parse(ConnectionConfig cfg) throws InvalidSettingsException {
    JsonNode c = cfg.config();
    if (c == null || !c.isObject()) {
      throw new InvalidSettingsException("The connection has no settings.");
    }
    Set<String> scopes = new LinkedHashSet<>();
    for (JsonNode s : c.path("scopes")) {
      String scope = s.asString("").strip();
      if (!SCOPE.matcher(scope).matches()) {
        throw new InvalidSettingsException("\"" + abbreviate(scope) + "\" isn't a project, folder or organization "
            + "(projects/acme-stage, folders/123, organizations/456).");
      }
      scopes.add(scope);
    }
    if (scopes.isEmpty()) {
      throw new InvalidSettingsException("Add at least one project, folder or organization under Scopes.");
    }
    Set<String> metrics = new LinkedHashSet<>();
    for (JsonNode p : c.path("metricsProjects")) {
      String project = p.asString("").strip();
      if (!PROJECT.matcher(project).matches()) {
        throw new InvalidSettingsException("\"" + abbreviate(project) + "\" isn't a project ID (acme-stage).");
      }
      metrics.add(project);
    }
    if (metrics.isEmpty()) {
      for (String scope : scopes) {
        if (scope.startsWith("projects/")) {
          metrics.add(scope.substring("projects/".length()));
        }
      }
    }
    GcpSpec spec;
    try {
      spec = GcpSpec.fromConfig(c, "/credentials");
    } catch (IllegalArgumentException e) {
      throw new InvalidSettingsException(e.getMessage());
    }
    Map<String, String> numbers = new TreeMap<>();
    JsonNode meta = cfg.metadata() == null ? null : cfg.metadata().path("projects");
    if (meta != null && meta.isObject()) {
      meta.properties().forEach(e -> {
        String number = e.getValue().asString("");
        if (PROJECT.matcher(e.getKey()).matches() && number.matches("\\d{1,19}")) {
          numbers.put(e.getKey(), number);
        }
      });
    }
    return new Settings(new ArrayList<>(scopes), new ArrayList<>(metrics), spec, c.path("liveGet").asBoolean(true),
        numbers);
  }

  String firstScope() {
    return scopes.getFirst();
  }

  /** The default project of {@code gcp_metrics}, or {@code null} when the connection names none. */
  String firstMetricsProject() {
    return metricsProjects.isEmpty() ? null : metricsProjects.getFirst();
  }

  /** {@code projects/acme-stage}, {@code projects/acme-stage +2}: where the connection reads, for texts. */
  String target() {
    return scopes.size() == 1 ? firstScope() : firstScope() + " +" + (scopes.size() - 1);
  }

  /**
   * The project whose API quota the gateway's reads spend (report 09 §1 #8): the billed project if one is named, else
   * the project of the impersonated service account, else the first project among the scopes. Part of the shared
   * quota key {@code gcp:<project>:asset}.
   */
  String consumerProject() {
    if (credentials.quotaProject() != null && PROJECT.matcher(credentials.quotaProject()).matches()) {
      return credentials.quotaProject();
    }
    if (credentials.impersonate() != null) {
      Matcher m = SERVICE_ACCOUNT_PROJECT.matcher(credentials.impersonate().toLowerCase(Locale.ROOT));
      if (m.matches()) {
        return m.group(1);
      }
    }
    for (String scope : scopes) {
      if (scope.startsWith("projects/")) {
        return scope.substring("projects/".length());
      }
    }
    return firstScope();
  }

  boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  boolean hasMetricsProject(String project) {
    return metricsProjects.contains(project);
  }

  /**
   * Whether a series or an alert labelled {@code project_id} belongs to another project than the connection's own:
   * a metrics project that scopes others (a metrics scope) answers with their data too — a stage connection whose
   * project watches prod would read prod. Known only when every scope is a project; with a folder or an organization
   * the connection's projects aren't listed, and nothing is left out. Nor while a project is known by its number only
   * (a series carries the id, which Test connection learns — {@code metadata.projects}), nor for a series without the
   * label (reduced without grouping by it): what can't be told apart is shown.
   */
  boolean foreignProject(String projectId) {
    if (projectId == null || projectId.isBlank()) {
      return false;
    }
    Set<String> own = new java.util.HashSet<>(metricsProjects);
    for (String scope : scopes) {
      if (!scope.startsWith("projects/")) {
        return false;
      }
      own.add(scope.substring("projects/".length()));
    }
    for (String p : own) {
      if (p.chars().allMatch(Character::isDigit) && !projectNumbers.containsValue(p)) {
        return false;
      }
    }
    if (own.contains(projectId)) {
      return false;
    }
    String number = projectNumbers.get(projectId);
    return number == null || !own.contains(number);
  }

  private static String abbreviate(String s) {
    return s.length() <= 60 ? s : s.substring(0, 60) + "…";
  }
}
