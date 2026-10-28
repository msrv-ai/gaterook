package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Bounded project suggestions for a draft connection, authenticated before any log scope has been selected. */
final class GcpProjects {

  static final int MAX_PROJECTS = 1_000;
  static final int MAX_PAGES = 10;
  private static final int PAGE_SIZE = 200;
  private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private GcpProjects() {}

  static TestResult discover(Backend.Call call) {
    long started = System.nanoTime();
    // Draft secret versions may be unchanged while an edited key changes; do not reuse a previous draft's token.
    CloudCredentials.Owner owner = new CloudCredentials.Owner("project-discovery-" + UUID.randomUUID(), 0,
        call.cfg().name());
    try {
      GcpSpec spec = GcpSpec.fromConfig(call.cfg().config(), "/credentials");
      URI override = call.cloud().endpoints().service("gcp.cloudresourcemanager");
      URI base = override == null ? GcpLoggingBackend.RESOURCE_MANAGER : override;
      var credentials = call.cloud().gcp(owner, spec, List.of(GcpLoggingBackend.PLATFORM_READ_ONLY), call.secrets());
      Map<String, String> projects = new TreeMap<>();
      var tokens = new HashSet<String>();
      String token = null;
      boolean complete = true;
      for (int page = 0; page < MAX_PAGES; page++) {
        URI uri = base.resolve("/v3/projects:search?pageSize=" + PAGE_SIZE + "&query=state%3AACTIVE"
            + (token == null ? "" : "&pageToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Accept", "application/json").GET();
        CloudCredentials.requestMetadata(credentials, uri, call.ctx()::taint)
            .forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        JsonNode response = response(Http.send(call.clients().google(), request, call.ctx(), "Project discovery"));
        JsonNode found = response.path("projects");
        if (!found.isMissingNode() && !found.isArray()) {
          throw ConnectorException.resourceError("Cloud Resource Manager returned an invalid project list.", null);
        }
        for (JsonNode project : found) {
          if (!project.path("state").asString("ACTIVE").equals("ACTIVE")) continue;
          String id = project.path("projectId").asString("");
          if (!PROJECT_ID.matcher(id).matches()) {
            complete = false;
            continue;
          }
          if (!projects.containsKey(id) && projects.size() >= MAX_PROJECTS) {
            complete = false;
            break;
          }
          String name = project.path("displayName").asString(id);
          projects.putIfAbsent(id, name.length() > 200 ? name.substring(0, 200) : name);
        }
        token = response.path("nextPageToken").asString(null);
        if (token == null || token.isBlank()) break;
        if (projects.size() >= MAX_PROJECTS || page == MAX_PAGES - 1 || token.length() > 8192
            || !tokens.add(token)) {
          complete = false;
          break;
        }
      }
      ObjectNode details = JsonNodeFactory.instance.objectNode().put("discoveryComplete", complete);
      ArrayNode choices = details.putArray("projects");
      projects.forEach((id, name) -> choices.addObject().put("projectId", id).put("displayName", name));
      return TestResult.success((System.nanoTime() - started) / 1_000_000, "Cloud Resource Manager API v3",
          spec.identity(), null).withDetails(details);
    } catch (IllegalArgumentException e) {
      return TestResult.failure("logs.config", e.getMessage(), null);
    } catch (ConnectorException e) {
      return TestResult.failure("logs.projects", e.getMessage(), null);
    } finally {
      call.cloud().evict(owner.id());
    }
  }

  private static JsonNode response(Http.Response response) throws ConnectorException {
    JsonNode tree;
    try {
      tree = JSON.readTree(response.body().isBlank() ? "{}" : response.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("Cloud Resource Manager answered " + response.status()
          + " with something that isn't JSON.", null);
    }
    if (response.ok()) {
      if (tree == null || !tree.isObject()) {
        throw ConnectorException.resourceError("Cloud Resource Manager returned an invalid project list.", null);
      }
      return tree;
    }
    String detail = tree == null ? "" : Http.excerpt(tree.at("/error/message").asString(""));
    throw switch (response.status()) {
      case 401 -> ConnectorException.unavailable("Project discovery refused the credentials. Check the selected "
          + "credentials and try again, or enter a project manually. " + detail, null);
      case 403 -> ConnectorException.resourceError("Cloud Resource Manager denied project discovery. Enable the "
          + "Cloud Resource Manager API and allow resourcemanager.projects.get for these credentials, or enter "
          + "a project manually. " + detail, null);
      case 429 -> ConnectorException.resourceError("Cloud Resource Manager's request quota is used up. Try again "
          + "later, or enter a project manually. " + detail, null);
      default -> ConnectorException.resourceError("Cloud Resource Manager answered " + response.status() + ". "
          + "Try again, or enter a project manually. " + detail, null);
    };
  }
}
