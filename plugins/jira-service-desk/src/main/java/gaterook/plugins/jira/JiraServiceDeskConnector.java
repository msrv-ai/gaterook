package gaterook.plugins.jira;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira Service Management requests for agents (reports 11 §3.3, 16 §8): search, read, queues and SLAs; comments and
 * transitions wait for a person's approval in the gateway and run here only with {@code ctx.approved()}.
 *
 * <p>The tools are those of the {@code mcp-gateway-plugin.json} in this JAR: release 1.2.0 serves five, 1.3.0 adds
 * {@code jira_request_sla}; a call of a tool the release doesn't declare is refused. Answers of Jira about the request
 * (not found, no permission) are results the agent reads ({@code isError}); trouble reaching Jira is an exception the
 * gateway counts against the connection's circuit breaker.
 */
public final class JiraServiceDeskConnector implements Connector {

  private static final Pattern ISSUE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,9}-[0-9]{1,9}$");
  private static final Pattern TRANSITION = Pattern.compile("^[0-9]{1,10}$");
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String API = "/rest/servicedeskapi";

  private final ConnectorManifest manifest;

  /** The plugin host's way in: the manifest of this JAR. */
  public JiraServiceDeskConnector() {
    this(Manifests.own());
  }

  /** A release described by {@code manifest} (tests). */
  JiraServiceDeskConnector(JsonNode manifest) {
    this.manifest = Manifests.read(manifest);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return 8;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Optional<ToolDefinition> t = tool(call.tool());
    if (t.isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of Jira Service Desk "
          + manifest.version() + ".", null);
    }
    return t.get().nature() == ToolDefinition.Nature.READ ? Classification.read()
        : Classification.mutation(call.tool() + " changes the request in Jira.");
  }

  /** The request exactly as it goes to Jira; no I/O, no secret. */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    JiraConfig config;
    try {
      config = JiraConfig.of(cfg);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), cfg.name(),
          call.tool() + " on " + cfg.name());
    }
    String key = call.string("issueKey");
    String target = cfg.name() + " · " + config.host();
    return switch (call.tool()) {
      case "jira_add_comment" -> {
        ObjectNode body = commentBody(call);
        boolean visible = body.path("public").asBoolean(false);
        yield http("POST", config, API + "/request/" + key + "/comment", body, target,
            (visible ? "Public" : "Internal") + " comment on " + key);
      }
      case "jira_transition_request" -> http("POST", config, API + "/request/" + key + "/transition",
          transitionBody(call), target, "Transition " + key + " (transition " + call.string("transitionId") + ")");
      case "jira_search_requests" -> http("GET", config, searchPath(call), null, target, "Search requests");
      case "jira_get_request" -> http("GET", config, API + "/request/" + key, null, target, "Read " + key);
      case "jira_list_queues" -> http("GET", config, queuesPath(call), null, target,
          "Queues of service desk " + call.integer("serviceDeskId", 0));
      case "jira_request_sla" -> http("GET", config, slaPath(key), null, target, "SLAs of " + key);
      default -> ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), target,
          call.tool());
    };
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    JiraClient jira;
    try {
      jira = JiraClient.of(cfg, secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("config", e.getMessage(), null);
    }
    String host = jira.config().host();
    try {
      long t0 = System.nanoTime();
      JiraClient.Response info = jira.get(API + "/info");
      checks.add(new TestResult.Check("Service Management", info.ok(), ms(t0), info.ok() ? null
          : "HTTP " + info.status()));
      if (!info.ok()) {
        return failure(info, host, "/info", checks);
      }
      String version = text(info.json(), "version");
      t0 = System.nanoTime();
      String api = jira.config().platformApi();
      JiraClient.Response me = jira.get("/rest/api/" + api + "/myself");
      checks.add(new TestResult.Check("Account", me.ok(), ms(t0), me.ok() ? null : "HTTP " + me.status()));
      if (!me.ok()) {
        return failure(me, host, "/myself", checks);
      }
      String identity = Optional.ofNullable(text(me.json(), "emailAddress"))
          .or(() -> Optional.ofNullable(text(me.json(), "displayName"))).orElse(text(me.json(), "name"));
      t0 = System.nanoTime();
      JiraClient.Response perms = jira.get("/rest/api/" + api
          + "/mypermissions?permissions=BROWSE_PROJECTS,ADD_COMMENTS,TRANSITION_ISSUES");
      checks.add(new TestResult.Check("Permissions", perms.ok(), ms(t0), perms.ok() ? null
          : "HTTP " + perms.status()));
      boolean browse = has(perms, "BROWSE_PROJECTS");
      boolean comment = has(perms, "ADD_COMMENTS");
      boolean transition = has(perms, "TRANSITION_ISSUES");
      List<String> writes = new ArrayList<>();
      if (comment) {
        writes.add("comment");
      }
      if (transition) {
        writes.add("transition");
      }
      ObjectNode details = NODES.objectNode();
      details.put("browseProjects", browse);
      details.put("addComments", comment);
      details.put("transitionIssues", transition);
      TestResult.Permissions permissions = new TestResult.Permissions(!perms.ok() ? TestResult.Level.UNKNOWN
          : writes.isEmpty() ? TestResult.Level.READ : TestResult.Level.WRITE, browse ? "Browse projects" : null,
          writes.isEmpty() ? null : String.join(" and ", writes) + " on requests", List.of(), details);
      TestResult result = TestResult.success(ms(started), version, identity, permissions).withChecks(checks);
      if (!writes.isEmpty()) {
        result = result.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "can-write", null,
            "This account can " + String.join(" and ", writes) + " requests. Use a read-only account for prod."));
      }
      if (perms.ok() && !browse) {
        result = result.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "no-browse", null,
            "This account can't browse projects: searches will come back empty."));
      }
      return result;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("Reach " + host, false, ms(started), e.getMessage()));
      return TestResult.failure(e.kind() == ConnectorException.Kind.DENIED ? "denied" : "unreachable",
          "Jira can't be reached at " + host + ": " + e.getMessage(), e.getMessage()).withChecks(checks);
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition t = tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool()
        + " isn't a tool of Jira Service Desk " + manifest.version() + "."));
    if (t.nature() != ToolDefinition.Nature.READ && !ctx.approved()) {
      // Defence in depth: the gateway sends a change only after a person approved it.
      throw ConnectorException.denied(call.tool() + " changes the request: it runs only after a person approved it.");
    }
    JiraClient jira = JiraClient.of(cfg, secrets, ctx);
    return switch (call.tool()) {
      case "jira_search_requests" -> search(jira, call);
      case "jira_get_request" -> getRequest(jira, issueKey(call));
      case "jira_list_queues" -> queues(jira, call);
      case "jira_request_sla" -> sla(jira, issueKey(call));
      case "jira_add_comment" -> addComment(jira, call);
      case "jira_transition_request" -> transition(jira, call);
      default -> throw ConnectorException.badInput(call.tool() + " isn't implemented here.");
    };
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult search(JiraClient jira, ToolCall call) throws ConnectorException {
    Long desk = call.arguments().hasNonNull("serviceDeskId") ? call.arguments().get("serviceDeskId").asLong() : null;
    if (desk != null && !jira.config().allows(String.valueOf(desk))) {
      throw ConnectorException.denied("Service desk " + desk + " isn't one of this connection's service desks.");
    }
    JiraClient.Response r = jira.get(searchPath(call));
    if (!r.ok()) {
      return failure(r, null);
    }
    JsonNode page = json(r);
    ArrayNode rows = NODES.arrayNode();
    ArrayNode requests = NODES.arrayNode();
    int hidden = 0;
    for (JsonNode v : page.path("values")) {
      String deskId = text(v, "serviceDeskId");
      if (!jira.config().allows(deskId)) {
        hidden++;
        continue;
      }
      ObjectNode item = request(v);
      requests.add(item);
      ArrayNode row = rows.addArray();
      row.add(item.path("issueKey").asString());
      row.add(item.path("summary").asString(""));
      row.add(item.path("status").asString(""));
      row.add(deskId);
      row.add(item.path("reporter").asString(""));
      row.add(item.path("created").asString(""));
    }
    ObjectNode structured = NODES.objectNode();
    structured.set("requests", requests);
    structured.put("start", page.path("start").asInt(0));
    structured.put("limit", page.path("limit").asInt(rows.size()));
    structured.put("isLastPage", page.path("isLastPage").asBoolean(true));
    if (hidden > 0) {
      structured.put("hiddenByConnection", hidden);
    }
    String next = page.path("isLastPage").asBoolean(true) ? null
        : "start=" + (page.path("start").asInt(0) + page.path("size").asInt(rows.size()));
    return ToolResult.table(new ToolResult.Table(List.of(column("key"), column("summary"), column("status"),
        column("serviceDeskId"), column("reporter"), column("created")), rows, next), structured);
  }

  private ToolResult getRequest(JiraClient jira, String key) throws ConnectorException {
    JiraClient.Response r = jira.get(API + "/request/" + key);
    if (!r.ok() || !jira.config().allows(text(json(r), "serviceDeskId"))) {
      return r.ok() ? notFound(key) : failure(r, key);
    }
    ObjectNode out = request(r.json());
    ArrayNode fields = out.putArray("fields");
    for (JsonNode f : r.json().path("requestFieldValues")) {
      ObjectNode field = fields.addObject();
      field.put("id", f.path("fieldId").asString(""));
      field.put("label", f.path("label").asString(""));
      field.set("value", f.has("value") ? f.get("value").deepCopy() : NODES.nullNode());
    }
    ArrayNode transitions = out.putArray("transitions");
    JiraClient.Response tr = jira.get(API + "/request/" + key + "/transition");
    if (tr.ok() && tr.json() != null) {
      for (JsonNode t : tr.json().path("values")) {
        ObjectNode item = transitions.addObject();
        item.put("id", t.path("id").asString(""));
        item.put("name", t.path("name").asString(""));
      }
    }
    String line = key + " · " + out.path("summary").asString("") + " · " + out.path("status").asString("")
        + (transitions.isEmpty() ? "" : " · transitions: " + names(transitions));
    return ToolResult.json(out, line);
  }

  private ToolResult queues(JiraClient jira, ToolCall call) throws ConnectorException {
    long desk = call.arguments().path("serviceDeskId").asLong(0);
    if (desk <= 0) {
      throw ConnectorException.badInput("serviceDeskId is required.");
    }
    if (!jira.config().allows(String.valueOf(desk))) {
      throw ConnectorException.denied("Service desk " + desk + " isn't one of this connection's service desks.");
    }
    JiraClient.Response r = jira.get(queuesPath(call));
    if (!r.ok()) {
      return r.status() == 404 ? ToolResult.error("Service desk " + desk + " not found or not visible to this "
          + "account.") : failure(r, null);
    }
    JsonNode page = json(r);
    ArrayNode rows = NODES.arrayNode();
    for (JsonNode q : page.path("values")) {
      ArrayNode row = rows.addArray();
      row.add(q.path("id").asString(""));
      row.add(q.path("name").asString(""));
      if (q.path("issueCount").isNumber()) {
        row.add(q.get("issueCount").asLong());
      } else {
        row.addNull();
      }
      row.add(q.path("jql").asString(""));
    }
    ObjectNode structured = NODES.objectNode();
    structured.put("serviceDeskId", desk);
    structured.put("queues", rows.size());
    structured.put("isLastPage", page.path("isLastPage").asBoolean(true));
    return ToolResult.table(new ToolResult.Table(List.of(column("id"), column("name"),
        new ToolResult.Column("issueCount", "integer", null, null, null), column("jql")), rows, null), structured);
  }

  /**
   * The SLAs of a request (report 11 §3.3): the ongoing cycle gives the state (ongoing or paused); without one, the
   * last completed cycle (completed). Durations in milliseconds, the breach time as Jira writes it.
   */
  private ToolResult sla(JiraClient jira, String key) throws ConnectorException {
    if (!jira.config().serviceDeskIds().isEmpty()) {
      JiraClient.Response req = jira.get(API + "/request/" + key);
      if (!req.ok() || !jira.config().allows(text(req.json(), "serviceDeskId"))) {
        return req.ok() ? notFound(key) : failure(req, key);
      }
    }
    JiraClient.Response r = jira.get(slaPath(key));
    if (!r.ok()) {
      return failure(r, key);
    }
    JsonNode page = json(r);
    ObjectNode out = NODES.objectNode();
    out.put("issueKey", key);
    ArrayNode slas = out.putArray("slas");
    List<String> lines = new ArrayList<>();
    for (JsonNode v : page.path("values")) {
      JsonNode cycle = v.path("ongoingCycle");
      String state;
      if (cycle.isObject()) {
        state = cycle.path("paused").asBoolean(false) ? "paused" : "ongoing";
      } else {
        JsonNode completed = v.path("completedCycles");
        cycle = completed.isArray() && !completed.isEmpty() ? completed.get(completed.size() - 1) : NODES.objectNode();
        state = "completed";
      }
      ObjectNode s = slas.addObject();
      s.put("name", v.path("name").asString(""));
      s.put("state", state);
      s.put("breached", cycle.path("breached").asBoolean(false));
      millis(s, "goalMillis", cycle.path("goalDuration"));
      millis(s, "elapsedMillis", cycle.path("elapsedTime"));
      millis(s, "remainingMillis", cycle.path("remainingTime"));
      String breach = cycle.path("breachTime").path("iso8601").asString(null);
      if (breach == null) {
        s.putNull("breachTime");
      } else {
        s.put("breachTime", breach);
      }
      lines.add(key + " · " + slaLine(s));
    }
    out.put("truncated", !page.path("isLastPage").asBoolean(true));
    return ToolResult.json(out, lines.isEmpty() ? key + " has no SLA." : String.join("\n", lines));
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult addComment(JiraClient jira, ToolCall call) throws ConnectorException {
    String key = issueKey(call);
    Optional<ToolResult> refused = checkDesk(jira, key);
    if (refused.isPresent()) {
      return refused.get();
    }
    ObjectNode body = commentBody(call);
    JiraClient.Response r = jira.post(API + "/request/" + key + "/comment", body);
    if (!r.ok()) {
      return failure(r, key);
    }
    ObjectNode out = NODES.objectNode();
    out.put("issueKey", key);
    out.put("commentId", text(r.json(), "id"));
    out.put("public", body.path("public").asBoolean(false));
    out.put("created", r.json() == null ? null : r.json().path("created").path("iso8601").asString(null));
    return ToolResult.json(out, (body.path("public").asBoolean(false) ? "Public" : "Internal") + " comment "
        + out.path("commentId").asString("") + " added to " + key + ".");
  }

  private ToolResult transition(JiraClient jira, ToolCall call) throws ConnectorException {
    String key = issueKey(call);
    String id = call.string("transitionId");
    if (id == null || !TRANSITION.matcher(id).matches()) {
      throw ConnectorException.badInput("transitionId is the number of a transition, as jira_get_request lists it.");
    }
    Optional<ToolResult> refused = checkDesk(jira, key);
    if (refused.isPresent()) {
      return refused.get();
    }
    JiraClient.Response r = jira.post(API + "/request/" + key + "/transition", transitionBody(call));
    if (!r.ok()) {
      return r.status() == 400 ? ToolResult.error("Jira refused transition " + id + " of " + key + ": "
          + jiraMessage(r) + " jira_get_request lists the transitions it can take now.") : failure(r, key);
    }
    ObjectNode out = NODES.objectNode();
    out.put("issueKey", key);
    out.put("transitionId", id);
    out.put("done", true);
    return ToolResult.json(out, key + " took transition " + id + ".");
  }

  /** A connection limited to some service desks changes only their requests: the request is read first. */
  private Optional<ToolResult> checkDesk(JiraClient jira, String key) throws ConnectorException {
    if (jira.config().serviceDeskIds().isEmpty()) {
      return Optional.empty();
    }
    JiraClient.Response r = jira.get(API + "/request/" + key);
    if (!r.ok()) {
      return Optional.of(failure(r, key));
    }
    return jira.config().allows(text(r.json(), "serviceDeskId")) ? Optional.empty() : Optional.of(notFound(key));
  }

  // ── Requests and answers ────────────────────────────────────────────────────────────────────────────────────

  private static ActionPreview http(String method, JiraConfig config, String pathAndQuery, JsonNode body,
      String target, String summary) {
    String text = method + " " + config.uri(pathAndQuery) + (body == null ? "" : "\n" + body);
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text, target, summary);
  }

  private static String searchPath(ToolCall call) {
    StringBuilder q = new StringBuilder(API + "/request?requestStatus=");
    q.append(enc(call.arguments().path("requestStatus").asString("OPEN_REQUESTS")));
    String term = call.string("searchTerm");
    if (term != null && !term.isBlank()) {
      q.append("&searchTerm=").append(enc(term));
    }
    if (call.arguments().hasNonNull("serviceDeskId")) {
      q.append("&serviceDeskId=").append(call.arguments().get("serviceDeskId").asLong());
    }
    q.append("&start=").append(Math.max(0, call.integer("start", 0)));
    q.append("&limit=").append(Math.clamp(call.integer("limit", 20), 1, 50));
    return q.toString();
  }

  private static String queuesPath(ToolCall call) {
    return API + "/servicedesk/" + call.arguments().path("serviceDeskId").asLong(0) + "/queue?includeCount=true"
        + "&start=" + Math.max(0, call.integer("start", 0)) + "&limit=" + Math.clamp(call.integer("limit", 50), 1, 50);
  }

  private static String slaPath(String key) {
    return API + "/request/" + key + "/sla?start=0&limit=50";
  }

  private static ObjectNode commentBody(ToolCall call) {
    ObjectNode body = NODES.objectNode();
    body.put("body", call.arguments().path("body").asString(""));
    body.put("public", call.arguments().path("public").asBoolean(false));
    return body;
  }

  private static ObjectNode transitionBody(ToolCall call) {
    ObjectNode body = NODES.objectNode();
    body.put("id", call.arguments().path("transitionId").asString(""));
    String comment = call.string("comment");
    if (comment != null && !comment.isBlank()) {
      body.putObject("additionalComment").put("body", comment);
    }
    return body;
  }

  /** A request of Jira's list or detail, flattened for the agent. */
  private static ObjectNode request(JsonNode v) {
    ObjectNode out = NODES.objectNode();
    out.put("issueKey", v.path("issueKey").asString(""));
    String summary = null;
    for (JsonNode f : v.path("requestFieldValues")) {
      if ("summary".equals(f.path("fieldId").asString())) {
        summary = f.path("value").asString(null);
      }
    }
    out.put("summary", summary);
    out.put("status", v.path("currentStatus").path("status").asString(null));
    out.put("statusCategory", v.path("currentStatus").path("statusCategory").asString(null));
    out.put("serviceDeskId", text(v, "serviceDeskId"));
    out.put("requestTypeId", text(v, "requestTypeId"));
    JsonNode reporter = v.path("reporter");
    out.put("reporter", reporter.path("displayName").asString(reporter.path("emailAddress").asString(null)));
    out.put("created", v.path("createdDate").path("iso8601").asString(null));
    return out;
  }

  private static String issueKey(ToolCall call) throws ConnectorException {
    String key = call.string("issueKey");
    if (key == null || !ISSUE_KEY.matcher(key).matches()) {
      throw ConnectorException.badInput("issueKey is a request key such as SD-123.");
    }
    return key;
  }

  /**
   * What Jira's refusal means for the agent: not found, the token, the request itself. Overload and outages are
   * exceptions instead: they say nothing about the request and count against the connection.
   */
  private static ToolResult failure(JiraClient.Response r, String key) throws ConnectorException {
    int s = r.status();
    if (s == 401 || s == 403) {
      return ToolResult.error("Jira rejected the token. Test the connection.");
    }
    if (s == 404) {
      return key == null ? ToolResult.error("Jira found nothing at that address.") : notFound(key);
    }
    if (s == 429) {
      throw ConnectorException.unavailable("Jira is limiting requests (HTTP 429). Try again in a minute.", null);
    }
    if (s >= 500) {
      throw ConnectorException.unavailable("Jira answered HTTP " + s + ".", null);
    }
    return ToolResult.error("Jira refused the request (HTTP " + s + "): " + jiraMessage(r));
  }

  /** The JSON of a successful answer; one that isn't JSON (a proxy's page) is the resource's error. */
  private static JsonNode json(JiraClient.Response r) throws ConnectorException {
    if (r.json() == null) {
      throw ConnectorException.resourceError("Jira answered HTTP " + r.status() + " with something that isn't JSON.",
          null);
    }
    return r.json();
  }

  private static ToolResult notFound(String key) {
    return ToolResult.error(key + " not found or not visible to this account.");
  }

  private static TestResult failure(JiraClient.Response r, String host, String what,
      List<TestResult.Check> checks) {
    if (r.status() == 401 || r.status() == 403) {
      return TestResult.failure("auth", "Jira rejected the token: check the email and the token of the connection.",
          "HTTP " + r.status() + " from " + what).withChecks(checks);
    }
    if (r.status() == 404) {
      return TestResult.failure("not-service-management", host + " doesn't answer like Jira Service Management. "
          + "Check the site URL and the deployment.", "HTTP 404 from " + what).withChecks(checks);
    }
    return TestResult.failure("http-" + r.status(), "Jira answered HTTP " + r.status() + ".", "HTTP " + r.status()
        + " from " + what).withChecks(checks);
  }

  /** Jira's own words: {@code errorMessage}, else the first of {@code errorMessages}, else the status. */
  private static String jiraMessage(JiraClient.Response r) {
    JsonNode j = r.json();
    if (j != null) {
      if (j.path("errorMessage").isString()) {
        return cut(j.get("errorMessage").asString());
      }
      for (JsonNode m : j.path("errorMessages")) {
        return cut(m.asString());
      }
      for (Map.Entry<String, JsonNode> e : j.path("errors").properties()) {
        return cut(e.getKey() + ": " + e.getValue().asString());
      }
    }
    return "HTTP " + r.status() + ".";
  }

  private static boolean has(JiraClient.Response perms, String permission) {
    return perms.ok() && perms.json() != null
        && perms.json().path("permissions").path(permission).path("havePermission").asBoolean(false);
  }

  private static void millis(ObjectNode out, String name, JsonNode duration) {
    if (duration.path("millis").isNumber()) {
      out.put(name, duration.get("millis").asLong());
    } else {
      out.putNull(name);
    }
  }

  /** "Time to first response: ongoing, 2h 30m left (goal 4h)". */
  static String slaLine(JsonNode s) {
    StringBuilder b = new StringBuilder(s.path("name").asString("SLA")).append(": ").append(s.path("state").asString());
    if (s.path("breached").asBoolean(false)) {
      b.append(", breached");
    } else if (s.path("remainingMillis").isNumber() && !"completed".equals(s.path("state").asString())) {
      b.append(", ").append(duration(s.get("remainingMillis").asLong())).append(" left");
    }
    if (s.path("goalMillis").isNumber()) {
      b.append(" (goal ").append(duration(s.get("goalMillis").asLong())).append(')');
    }
    return b.toString();
  }

  static String duration(long millis) {
    long minutes = Math.abs(millis) / 60_000;
    long hours = minutes / 60;
    long rest = minutes % 60;
    String text = hours == 0 ? rest + "m" : rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    return millis < 0 ? "-" + text : text;
  }

  private static String names(ArrayNode transitions) {
    List<String> out = new ArrayList<>();
    for (JsonNode t : transitions) {
      out.add(t.path("name").asString("") + " (" + t.path("id").asString("") + ")");
    }
    return String.join(", ", out);
  }

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  private static ToolResult.Column column(String name) {
    return new ToolResult.Column(name, "text", null, null, null);
  }

  private static String text(JsonNode n, String field) {
    if (n == null) {
      return null;
    }
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static String cut(String s) {
    return s.length() <= 300 ? s : s.substring(0, 300) + "…";
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
