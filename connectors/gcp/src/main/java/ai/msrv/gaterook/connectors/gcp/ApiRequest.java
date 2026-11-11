package ai.msrv.gaterook.connectors.gcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call of {@code gcp_api_read} or {@code gcp_api_change}, checked without I/O: the API and method as their
 * Discovery document names them, the parameters and the body. The same checks run in {@code classify}, again in
 * {@code prepare} and {@code execute}, and there once more on the URL the Discovery document gives
 * ({@link ApiCall}).
 *
 * <p>The boundary is the connection's scopes: every {@code projects/…} value and every {@code project} or
 * {@code projectId} parameter names a project of the connection (or, with folder and organization scopes, a project
 * that {@code execute} proves to be under one of them), {@code folders/…} and {@code organizations/…} values are scopes
 * or lie inside one, billing accounts are outside, and a request that names none of them is refused, except Cloud
 * Storage, whose bucket {@code execute} looks up. Locations follow the Cloud policy; a wildcard location ({@code -})
 * only when the policy lists none.
 *
 * @param projects the projects the arguments name, ids or numbers, in order
 */
record ApiRequest(String tool, String api, String version, String method, ObjectNode params, JsonNode body,
    List<String> projects) {

  static final String READ = "gcp_api_read";
  static final String CHANGE = "gcp_api_change";
  static final int MAX_PARAMS = 40;
  static final int MAX_VALUE = 4_096;
  static final int MAX_ITEMS = 50;
  static final long DEFAULT_MAX_BODY = 1024 * 1024;

  private static final Pattern PARAM = Pattern.compile("^[A-Za-z$][A-Za-z0-9_.$-]{0,99}$");
  private static final Pattern PROJECT_REF = Pattern.compile("(?:^|/)projects/([^/:?#]+)");
  private static final Pattern PARENT_REF = Pattern.compile("^(folders|organizations)/([^/:?#]+)");
  private static final Pattern LOCATION_REF = Pattern.compile("(?:^|/)(?:locations|zones|regions)/([^/:?#]+)");
  private static final Set<String> PROJECT_PARAMS = Set.of("project", "projectId", "projectsId");
  private static final Set<String> LOCATION_PARAMS = Set.of("location", "zone", "region", "locationsId", "zonesId",
      "regionsId");

  ApiRequest {
    projects = List.copyOf(projects);
  }

  static ApiRequest of(String tool, JsonNode args, String connection, Settings s, GcpPolicy p, long maxBody)
      throws Refusal {
    String api = DescribeRequest.text(args, "service");
    if (api == null || !Discovery.API.matcher(api).matches()) {
      throw new Refusal("bad_input", "service is the short name of a Google Cloud API, such as run, compute, "
          + "storage, sqladmin, container, pubsub, cloudbuild or clouderrorreporting.", null);
    }
    String version = DescribeRequest.text(args, "version");
    if (version != null && !Discovery.VERSION.matcher(version).matches()) {
      throw new Refusal("bad_input", "version is an API version like v1, v2 or v1beta1.", null);
    }
    String method = DescribeRequest.text(args, "method");
    if (method == null || !Discovery.METHOD.matcher(method).matches()) {
      throw new Refusal("bad_input", "method is a Discovery method name like projects.locations.services.list.",
          null);
    }
    if (method.startsWith(api + ".")) {
      method = method.substring(api.length() + 1);
    }
    String why = ApiRules.denied(api, method);
    if (why != null) {
      throw new Refusal("method_not_allowed", why, null);
    }
    DescribeRequest.checkService(Services.id(api), api, p);
    ObjectNode params = params(args.get("params"));
    JsonNode body = args.get("body");
    if (body != null && body.isNull()) {
      body = null;
    }
    if (body != null) {
      if (!body.isObject()) {
        throw new Refusal("bad_input", "body is a JSON object, the request body of the method.", null);
      }
      long limit = maxBody > 0 ? maxBody : DEFAULT_MAX_BODY;
      if (body.toString().getBytes(StandardCharsets.UTF_8).length > limit) {
        throw new Refusal("bad_input", "body is larger than " + (limit / 1024) + " KB.", null);
      }
    }
    List<String> projects = scope(params, connection, s);
    locations(params, p);
    return new ApiRequest(tool, api, version, method, params, body, projects);
  }

  private static ObjectNode params(JsonNode given) throws Refusal {
    if (given == null || given.isNull()) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (!(given instanceof ObjectNode o)) {
      throw new Refusal("bad_input", "params is an object of the method's path and query parameters.", null);
    }
    if (o.size() > MAX_PARAMS) {
      throw new Refusal("bad_input", "At most " + MAX_PARAMS + " params.", null);
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, JsonNode> e : o.properties()) {
      String name = e.getKey();
      if (!PARAM.matcher(name).matches()) {
        throw new Refusal("bad_input", "\"" + DescribeRequest.abbreviate(name) + "\" isn't a parameter name.", null);
      }
      JsonNode v = e.getValue();
      if (ApiRules.FORBIDDEN_PARAMS.contains(name)) {
        if (!(name.equals("alt") && v.isString() && v.asString().equals("json"))) {
          throw new Refusal("param_not_allowed", "params." + name + " isn't accepted: it changes how the request "
              + "authenticates or makes it return raw content.", "Object contents and downloads aren't available "
              + "through gcp_api tools.");
        }
        continue;
      }
      if (v.isArray()) {
        if (v.size() > MAX_ITEMS) {
          throw new Refusal("bad_input", "params." + name + " has more than " + MAX_ITEMS + " values.", null);
        }
        for (JsonNode item : v) {
          scalar(name, item);
        }
      } else if (!v.isNull()) {
        scalar(name, v);
      }
      out.set(name, v.deepCopy());
    }
    return out;
  }

  private static void scalar(String name, JsonNode v) throws Refusal {
    if (!(v.isString() || v.isNumber() || v.isBoolean())) {
      throw new Refusal("bad_input", "params." + name + " is text, a number or true/false (or a list of them).",
          null);
    }
    if (v.isString() && v.asString().length() > MAX_VALUE) {
      throw new Refusal("bad_input", "params." + name + " is longer than " + MAX_VALUE + " characters.", null);
    }
  }

  /** The projects the parameters name; refuses one outside the connection and a request that names no scope. */
  private static List<String> scope(ObjectNode params, String connection, Settings s) throws Refusal {
    Set<String> projects = new LinkedHashSet<>();
    boolean scoped = false;
    for (Map.Entry<String, JsonNode> e : params.properties()) {
      for (String value : values(e.getValue())) {
        if (PROJECT_PARAMS.contains(e.getKey()) && !value.contains("/")) {
          projects.add(value);
          scoped = true;
        }
        if (value.startsWith("billingAccounts/")) {
          throw new Refusal("scope_not_allowed", "Billing accounts are outside the scopes of " + connection + ".",
              "Read a project's billing with cloudbilling projects.getBillingInfo, or its costs from the billing "
                  + "export in BigQuery.");
        }
        Matcher parent = PARENT_REF.matcher(value);
        if (parent.find()) {
          String scope = parent.group(1) + "/" + parent.group(2);
          if (!s.hasScope(scope)) {
            throw new Refusal("scope_not_allowed", scope + " isn't a scope of " + connection + ".", "Its scopes: "
                + String.join(", ", s.scopes()) + ".");
          }
          scoped = true;
        }
        Matcher m = PROJECT_REF.matcher(value);
        while (m.find()) {
          projects.add(m.group(1));
          scoped = true;
        }
      }
    }
    boolean parents = s.scopes().stream().anyMatch(sc -> !sc.startsWith("projects/"));
    for (String project : projects) {
      if (!project.matches("[a-z0-9][a-z0-9-]{0,62}")) {
        throw new Refusal("bad_input", "\"" + DescribeRequest.abbreviate(project) + "\" isn't a project ID or number.",
            null);
      }
      if (!ownProject(project, s) && !parents) {
        throw new Refusal("scope_not_allowed", "projects/" + project + " isn't a scope of " + connection + ".",
            "Its scopes: " + String.join(", ", s.scopes()) + ".");
      }
    }
    if (!scoped && !params.has("bucket")) {
      throw new Refusal("no_scope", "The request names no project, folder or organization of " + connection + ".",
          "Pass the parent or name in params, like {\"parent\": \"" + example(s) + "/locations/-\"}.");
    }
    return new ArrayList<>(projects);
  }

  private static void locations(ObjectNode params, GcpPolicy p) throws Refusal {
    for (Map.Entry<String, JsonNode> e : params.properties()) {
      for (String value : values(e.getValue())) {
        List<String> found = new ArrayList<>();
        if (LOCATION_PARAMS.contains(e.getKey()) && !value.contains("/")) {
          found.add(value);
        }
        Matcher m = LOCATION_REF.matcher(value);
        while (m.find()) {
          found.add(m.group(1));
        }
        for (String location : found) {
          if (location.equals("-") || location.equals("*")) {
            if (!p.regions().isEmpty()) {
              throw new Refusal("region_not_allowed", "params." + e.getKey() + " asks for every location, and "
                  + p.regionsText() + ".", "Name one location.");
            }
          } else if (!p.regionAllowed(location)) {
            throw new Refusal("region_not_allowed", "params." + e.getKey() + " is in " + location + "; "
                + p.regionsText() + ".", null);
          }
        }
      }
    }
  }

  /** Whether {@code project} (an id or a number) is a project scope of the connection itself. */
  static boolean ownProject(String project, Settings s) {
    if (s.hasScope("projects/" + project)) {
      return true;
    }
    for (Map.Entry<String, String> e : s.projectNumbers().entrySet()) {
      if (e.getValue().equals(project) && s.hasScope("projects/" + e.getKey())
          || e.getKey().equals(project) && s.hasScope("projects/" + e.getValue())) {
        return true;
      }
    }
    return false;
  }

  private static List<String> values(JsonNode v) {
    List<String> out = new ArrayList<>();
    if (v.isArray()) {
      v.forEach(item -> out.add(item.asString("")));
    } else if (!v.isNull()) {
      out.add(v.asString(""));
    }
    return out;
  }

  private static String example(Settings s) {
    for (String scope : s.scopes()) {
      if (scope.startsWith("projects/")) {
        return scope;
      }
    }
    return "projects/<project>";
  }

  /** The call as the approval stores it and the approved call runs it. */
  ObjectNode payload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", tool);
    n.put("service", api);
    if (version != null) {
      n.put("version", version);
    }
    n.put("method", method);
    n.set("params", params.deepCopy());
    if (body != null) {
      n.set("body", body.deepCopy());
    }
    return n;
  }

  /** {@code run projects.locations.services.list}, for texts. */
  String what() {
    return api + " " + method;
  }
}
