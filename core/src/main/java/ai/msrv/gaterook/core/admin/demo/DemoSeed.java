package ai.msrv.gaterook.core.admin.demo;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.audit.AuditBackfill;
import ai.msrv.gaterook.core.audit.AuditChain;
import ai.msrv.gaterook.core.audit.AuditExports;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.clients.ClientService;
import ai.msrv.gaterook.core.notifications.NotificationService;
import ai.msrv.gaterook.core.oauth.OAuthService;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelService;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import ai.msrv.gaterook.core.mcpservers.McpServerService;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.StartupFacts;
import ai.msrv.gaterook.core.secrets.MasterKeyService;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The demo world of the kit (design/BLUEPRINT.md §9) for comparing the screens with the kit (ARCHITECTURE.md §2.4,
 * §8.1): with {@code GW_DEMO=1} (or {@code gateway.demo=true}) a core that just created its home fills it from
 * {@code classpath:demo/seed.json} before it reports ready.
 *
 * <p>Everything goes through the modules' own services, exactly as the window and the agents would cause it:
 * external stores, connections with their secrets stored here or referenced in a store (S1), the endpoints
 * {@code dev}, {@code stage-readonly} and {@code incident-prod} with the kit's tool modes (and the Setup changes of
 * switching them), the tokens, the tunnel's endpoint with this morning's session and claude.ai's sign-in, the audit's
 * past days ({@link DemoHistory}: the kit's 24 hours, the day before and a week to compare with), the waiting
 * requests with their {@code received} rows, a decided one, a few logged calls, and the audit export destinations of
 * Settings (shown, never contacted). So every row is signed and the audit is one chain, anchored while it was still
 * empty; a demo home is a normal home afterwards. Times are those of the seeding, not the kit's 14:30.
 *
 * <p>Never in a release, never into a home that has anything in it: the switch is a test override like the others of
 * {@link BuildFlags}, and an existing database is somebody's data.
 */
@Component
class DemoSeed {

  static final String RESOURCE = "demo/seed.json";

  private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Environment environment;
  private final StartupFacts facts;
  private final MasterKeyLock lock;
  private final ConnectionService connections;
  private final SecretStores stores;
  private final EndpointService endpoints;
  private final EndpointConnections wiring;
  private final EndpointTools tools;
  private final EndpointSnapshotService snapshots;
  private final TokenService tokens;
  private final ApprovalService approvals;
  private final AuditWriter audit;
  private final AuditChain chain;
  private final AuditBackfill backfill;
  private final AuditExports exports;
  private final ClientService clients;
  private final NotificationService notifications;
  private final TunnelService tunnel;
  private final OAuthService oauth;
  private final McpServerService mcpServers;
  private final TemplateService templates;
  private final MasterKeyService masterKey;
  private final JsonMapper json;
  private final Clock clock;

  DemoSeed(Environment environment, StartupFacts facts, MasterKeyLock lock, ConnectionService connections,
      SecretStores stores, EndpointService endpoints, EndpointConnections wiring, EndpointTools tools, EndpointSnapshotService snapshots,
      TokenService tokens, ApprovalService approvals, AuditWriter audit, AuditChain chain, AuditBackfill backfill,
      AuditExports exports, ClientService clients, NotificationService notifications, TunnelService tunnel,
      OAuthService oauth, McpServerService mcpServers, TemplateService templates, MasterKeyService masterKey,
      JsonMapper json, Clock clock) {
    this.environment = environment;
    this.facts = facts;
    this.lock = lock;
    this.connections = connections;
    this.stores = stores;
    this.endpoints = endpoints;
    this.wiring = wiring;
    this.tools = tools;
    this.snapshots = snapshots;
    this.tokens = tokens;
    this.approvals = approvals;
    this.audit = audit;
    this.chain = chain;
    this.backfill = backfill;
    this.exports = exports;
    this.clients = clients;
    this.notifications = notifications;
    this.tunnel = tunnel;
    this.oauth = oauth;
    this.mcpServers = mcpServers;
    this.templates = templates;
    this.masterKey = masterKey;
    this.json = json;
    this.clock = clock;
  }

  /** Before the ready line (the reporter has the lowest precedence): the window opens on the finished world. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  void onReady() {
    if (!requested()) {
      return;
    }
    if (BuildFlags.RELEASE) {
      log.warn("GW_DEMO is ignored: this is a release build");
      return;
    }
    if (!facts.firstRun() || !connections.list().isEmpty() || !endpoints.list().isEmpty()) {
      log.warn("GW_DEMO is ignored: the demo world only goes into an empty home, and this one has data");
      return;
    }
    if (lock.locked()) {
      log.warn("GW_DEMO is ignored: the core is locked ({})", lock.message());
      return;
    }
    // This runs before the audit's start check, which would take a filled history without an anchor for one whose
    // anchor was deleted: the empty history is anchored first, so the seeded rows are an ordinary verified tail.
    if (!chain.anchorIfEmpty()) {
      log.warn("GW_DEMO is ignored: the audit history couldn't be anchored before seeding");
      return;
    }
    try {
      Counts counts = seed(read());
      log.info("Demo world seeded from {}: {} stores, {} connections, {} MCP servers, {} templates, {} endpoints, {} "
          + "tokens, {} client links, {} requests, {} calls, {} audit rows of the past days", RESOURCE, counts.stores,
          counts.connections, counts.mcpServers, counts.templates, counts.endpoints, counts.tokens, counts.links,
          counts.requests, counts.calls, counts.history);
    } catch (RuntimeException e) {
      // A half-seeded demo is still a working core; the log says what is missing.
      log.error("The demo world could not be seeded completely", e);
    }
  }

  boolean requested() {
    String flag = environment.getProperty("GW_DEMO", environment.getProperty("gateway.demo", "false"));
    return flag.equals("1") || flag.equalsIgnoreCase("true");
  }

  private JsonNode read() {
    try (InputStream in = DemoSeed.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the classpath");
      }
      return json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final class Counts {
    int stores;
    int connections;
    int mcpServers;
    int templates;
    int endpoints;
    int tokens;
    int links;
    int requests;
    int calls;
    int history;
  }

  Counts seed(JsonNode world) {
    Counts counts = new Counts();
    for (JsonNode s : world.path("stores")) {
      // Connected without being asked anything: the demo's stores don't exist, and nothing reads them until a call.
      Map<String, char[]> signIn = new LinkedHashMap<>();
      for (JsonNode field : s.path("secrets")) {
        signIn.put(field.asString(), ("demo-" + s.path("name").asString() + "-" + HexFormat.of().formatHex(bytes(6)))
            .toCharArray());
      }
      stores.create(s.path("kind").asString(), s.path("name").asString(), s.path("config"), signIn, "Demo world");
      counts.stores++;
    }
    for (JsonNode c : world.path("connections")) {
      Map<String, SecretInput> secrets = new LinkedHashMap<>();
      for (var ref : c.path("secrets").properties()) {
        // Where the value lives, never the value: bound to the connected store of its kind, or "not connected".
        JsonNode r = ref.getValue();
        secrets.put(ref.getKey(), "vault-dynamic".equals(r.path("source").asString())
            ? SecretInput.dynamic(r.path("ref").asString(), r.path("store").asString(null))
            : SecretInput.reference(r.path("ref").asString(), r.path("store").asString(null)));
      }
      if (c.hasNonNull("secret")) {
        // A demo value per connection: it is stored encrypted like any secret, and no screen ever shows it.
        secrets.put(c.path("secret").asString(), SecretInput.local(("demo-" + c.path("name").asString() + "-"
            + HexFormat.of().formatHex(bytes(6))).toCharArray()));
      }
      // The kit's world, not a person's save: its prod hosts don't exist, and testing them would only slow the seed.
      ConnectionService.Summary created = connections.create(c.path("name").asString(), c.path("type").asString(),
          Env.of(c.path("env").asString()), c.path("config"), secrets, "Demo world", ConnectionService.CONFIRM_DANGER);
      facts(created.id(), c);
      counts.connections++;
    }
    for (JsonNode m : world.path("mcpServers")) {
      mcpServer(m);
      counts.mcpServers++;
    }
    for (JsonNode t : world.path("templates")) {
      // After the connections they run on and before the endpoints: ssh_run is wired with its runbooks.
      template(t);
      counts.templates++;
    }
    for (JsonNode e : world.path("endpoints")) {
      String slug = e.path("slug").asString();
      endpoints.create(slug, e.path("description").asString(null),
          EndpointService.StartFrom.of(e.path("startFrom").asString("empty")), "Demo world");
      for (JsonNode c : e.path("connections")) {
        wiring.add(slug, c.asString(), null);
      }
      if (e.has("access")) {
        endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, e.path("access").asString(), null, null,
            null, null, null), endpoints.get(slug).version(), e.path("reason").asString(null));
      }
      for (JsonNode m : e.path("modes")) {
        if (m.has("from")) {
          setMode(slug, m, Mode.valueOf(m.path("from").asString()), null);
        }
        setMode(slug, m, Mode.valueOf(m.path("mode").asString()), m.path("reason").asString(null));
      }
      counts.endpoints++;
    }
    for (JsonNode t : world.path("tokens")) {
      // A backdated token (the kit's expired ci-smoke) is issued as long ago as the seed says.
      Instant issuedAt = t.has("issuedDaysAgo")
          ? clock.instant().minus(Duration.ofDays(t.path("issuedDaysAgo").asLong())) : null;
      TokenService.Issued issued = tokens.issue(t.path("endpoint").asString(), t.path("name").asString(),
          t.has("ttlDays") ? t.path("ttlDays").asInt() : null, null, issuedAt);
      if (t.has("revoke")) {
        tokens.revoke(issued.id(), t.path("revoke").asString());
      }
      counts.tokens++;
    }
    ObjectNode callers = (ObjectNode) world.path("callers").deepCopy();
    for (ClientService.DemoGrant g : clients(world.path("clients"))) {
      // The demo's calls come from the clients' real grants, so Activity and the Clients page name the same access.
      for (JsonNode link : world.path("clients").path("links")) {
        if (link.path("client").asString().equals(g.client()) && link.path("endpoint").asString().equals(g.endpoint())
            && link.hasNonNull("caller")) {
          callers.putObject(link.path("caller").asString()).put("principal", g.grantId()).put("label", g.label())
              .put("client", g.client());
        }
      }
      counts.links++;
    }
    // Before the requests, so they are announced as in the kit (desktop on; the demo's Slack never sends).
    notifications.seedDemo(world.path("notifications"));
    TunnelSession session = tunnel(world.path("tunnel"));
    // The past first, oldest row first: the audit's ids keep the order of time (the requests and calls below are of
    // the seeding's own moment).
    counts.history = history(world.path("history"), callers, session);
    Map<String, Task> tasks = tasks(world.path("tasks"));
    for (JsonNode r : world.path("requests")) {
      request(r, callers.path(r.path("caller").asString()), tasks.get(r.path("task").asString("")));
      counts.requests++;
    }
    for (JsonNode c : world.path("calls")) {
      call(c, callers.path(c.path("caller").asString()), tasks.get(c.path("task").asString("")));
      counts.calls++;
    }
    // Last: their cursors name rows of the history.
    exports.seedDemo(world.path("auditExport"));
    keyBackup(world.path("masterKey"));
    return counts;
  }

  // ── What the kit shows that a person and the gateway would have made by now ─────────────────────────────────

  /**
   * The facts the last test of a connection found ({@code TestResult.metadataUpdate}: the server's version, a read
   * replica, the AWS account) and its health as the checks left it — billing-api unreachable since 11:42 in the kit —
   * recorded through the same calls as a test and a health check. The demo's hosts don't exist: a check run on them
   * later says unreachable, so the dev loop runs the demo without periodic checks.
   */
  private void facts(String connectionId, JsonNode c) {
    if (c.path("facts").isObject()) {
      connections.recordMetadata(connectionId, c.path("facts"));
    }
    JsonNode h = c.path("health");
    if (!h.isObject()) {
      return;
    }
    HealthResult.State state = HealthResult.State.valueOf(h.path("state").asString());
    Long latency = h.path("latencyMs").isNumber() ? h.path("latencyMs").asLong() : null;
    String message = h.path("message").asString(null);
    Instant now = clock.instant();
    if (h.has("sinceMinutesAgo")) {
      connections.recordHealth(connectionId, state, latency, message,
          now.minus(Duration.ofMinutes(h.path("sinceMinutesAgo").asLong())));
    }
    connections.recordHealth(connectionId, state, latency, message,
        now.minus(Duration.ofMinutes(h.path("checkedMinutesAgo").asLong(1))));
  }

  /**
   * An external MCP server as installing its catalog entry leaves it (BLUEPRINT §9: {@code github-mcp} v0.6.2, 26
   * tools, on no endpoint): its connection, its token encrypted here and the reviewed snapshot of its tools. The seed's
   * handshake stands in for the first start: no npm, no process.
   */
  private void mcpServer(JsonNode m) {
    String name = m.path("name").asString();
    Map<String, SecretInput> values = new LinkedHashMap<>();
    for (JsonNode field : m.path("secrets")) {
      values.put(field.asString(), SecretInput.local(("demo-" + name + "-" + HexFormat.of().formatHex(bytes(6)))
          .toCharArray()));
    }
    String prefix = m.path("config").path("toolPrefix").asString();
    List<JsonNode> tools = new ArrayList<>();
    for (JsonNode t : m.path("tools")) {
      ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
      ObjectNode properties = input.putObject("properties");
      var required = input.putArray("required");
      t.path("params").forEach(p -> {
        properties.putObject(p.asString()).put("type", "string");
        required.add(p.asString());
      });
      ObjectNode declared = JsonNodeFactory.instance.objectNode();
      declared.put("name", t.path("name").asString());
      declared.put("description", t.path("description").asString());
      declared.set("inputSchema", input);
      ObjectNode entry = JsonNodeFactory.instance.objectNode();
      entry.put("exposed", prefix + t.path("name").asString());
      entry.put("original", t.path("name").asString());
      entry.put("description", t.path("description").asString());
      entry.set("inputSchema", input.deepCopy());
      entry.put("definitionHash", CanonicalJson.sha256Hex(declared));
      tools.add(entry);
    }
    McpServerRuntime.Handshake handshake = new McpServerRuntime.Handshake(m.path("serverInfo").deepCopy(),
        m.path("protocolVersion").asString(), null, tools, List.of(), 0, List.of());
    mcpServers.seedDemo(name, Env.of(m.path("env").asString("dev")), (ObjectNode) m.path("config").deepCopy(), values,
        handshake, m.path("npmIntegrity").asString(null), "Demo world");
  }

  /** A template of the kit (the runbooks of dev-vm and prod-vm), saved and compiled as the editor saves it. */
  private void template(JsonNode t) {
    JsonNode definition = t.path("definition");
    TemplateService.SaveResult saved = templates.create(t.path("kind").asString(), t.path("connection").asString(),
        definition.path("name").asString(), definition, null, "Demo world");
    if (saved.template() == null) {
      throw new IllegalStateException(RESOURCE + ": " + definition.path("name").asString() + " doesn't compile: "
          + saved.compile().errors());
    }
  }

  /**
   * The master key as the kit has it ("last backup 12 days ago", "Created Jul 14, never rotated"): its dates, as the
   * world says them. No backup file: a copy of the key without a passphrase would stay in a home that is a normal home
   * afterwards (review API #7).
   */
  private void keyBackup(JsonNode k) {
    if (!k.isObject()) {
      return;
    }
    Instant now = clock.instant();
    masterKey.seedDemo(now.minus(Duration.ofDays(k.path("backupDaysAgo").asLong())),
        now.minus(Duration.ofDays(k.path("createdDaysAgo").asLong())));
  }

  /** An agent's task the seed's calls belong to: the client's trace id and the name it gave the task. */
  private record Task(String traceId, String title) {}

  private static Map<String, Task> tasks(JsonNode t) {
    Map<String, Task> out = new LinkedHashMap<>();
    for (var e : t.properties()) {
      if (e.getValue().isObject()) {
        out.put(e.getKey(), new Task(e.getValue().path("traceId").asString(), e.getValue().path("title").asString()));
      }
    }
    return out;
  }

  /** The tunnel's session of this morning and claude.ai's sign-in in it (its grant is claude.ai's principal). */
  private record TunnelSession(Instant from, Instant to, String grantId) {}

  /**
   * The audit's past ({@link DemoHistory}): the kit's day and the eight before it, written through the audit as
   * calls of the clients' own callers, claude.ai's through the tunnel.
   */
  private int history(JsonNode h, JsonNode callers, TunnelSession session) {
    if (!h.isObject()) {
      return 0;
    }
    Instant now = clock.instant();
    TunnelSession tunnelSession = session != null ? session
        : new TunnelSession(now.minus(Duration.ofHours(4)), now.minus(Duration.ofMinutes(20)), "og_demo");
    Caller claudeAi = new Caller(Caller.Kind.OAUTH, tunnelSession.grantId() == null ? "og_demo"
        : tunnelSession.grantId(), "claude.ai", "claude-ai", Ingress.TUNNEL, "160.79.104.10");
    Map<String, EndpointSnapshot> bySlug = new java.util.HashMap<>();
    DemoHistory.World world = new DemoHistory.World(caller(callers.path("codex")),
        caller(callers.path("claude-desktop")), claudeAi, tunnelSession.from(), tunnelSession.to(),
        (slug, tool, connection) -> pair(bySlug.computeIfAbsent(slug, k -> snapshots.bySlug(k).orElseThrow()), tool,
            connection));
    return backfill.write(new DemoHistory(h.path("random").asLong(1), now, world).calls(h.path("days").asInt(8)));
  }

  /**
   * The kit's clients (BLUEPRINT §9): Claude Desktop on {@code dev} and {@code incident-prod}, Codex on {@code dev} as
   * {@code codex-laptop}, Cursor detected. They live in the demo user home, never the developer's own.
   */
  private List<ClientService.DemoGrant> clients(JsonNode c) {
    if (c.isMissingNode()) {
      return List.of();
    }
    List<String> detected = new ArrayList<>();
    c.path("detected").forEach(d -> detected.add(d.asString()));
    Map<String, String> files = new LinkedHashMap<>();
    c.path("files").properties().forEach(f -> files.put(f.getKey(), f.getValue().asString()));
    List<ClientService.DemoLink> links = new ArrayList<>();
    c.path("links").forEach(l -> links.add(new ClientService.DemoLink(l.path("client").asString(),
        l.path("endpoint").asString(), l.path("method").asString(), l.path("grantLabel").asString(null))));
    try {
      return clients.seedDemo(detected, files, links);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void setMode(String slug, JsonNode m, Mode mode, String reason) {
    EndpointSnapshot s = snapshots.bySlug(slug).orElseThrow();
    String tool = m.path("tool").asString();
    if (!m.has("connection")) {
      tools.setMode(slug, tool, null, mode, null, s.coreTool(tool).orElseThrow().rowVersion(), reason);
      return;
    }
    EndpointSnapshot.Pair pair = pair(s, tool, m.path("connection").asString());
    tools.setMode(slug, pair.toolKey(), pair.connectionName(), mode, null, pair.rowVersion(), reason);
  }

  /**
   * The kit's tunnel (BLUEPRINT §9): {@code stage-readonly} is the tunnel's endpoint, it was public through a quick
   * tunnel this morning and is off now, and claude.ai's sign-in of that session is in "Signed in" for 184 days. Times
   * are relative to the seeding, like the rest of the world.
   */
  private TunnelSession tunnel(JsonNode t) {
    if (!t.isObject()) {
      return null;
    }
    Instant now = clock.instant();
    Instant from = now.minus(Duration.ofMinutes(t.path("fromMinutesAgo").asLong()));
    Instant to = now.minus(Duration.ofMinutes(t.path("toMinutesAgo").asLong()));
    String slug = t.path("endpoint").asString();
    String host = t.path("host").asString();
    tunnel.seedDemo(slug, host, TunnelProvider.of(t.path("provider").asString()).orElseThrow(), from, to,
        t.path("calls").asLong(), t.path("reason").asString(null));
    JsonNode s = t.path("signIn");
    String grant = null;
    if (s.isObject()) {
      List<String> redirects = new ArrayList<>();
      s.path("redirectUris").forEach(r -> redirects.add(r.asString()));
      grant = oauth.seedDemo(new OAuthService.DemoSignIn(s.path("clientId").asString(),
          s.path("clientHost").asString(), s.path("clientName").asString(), redirects, slug,
          "https://" + host + "/mcp/" + slug, from.plus(Duration.ofMinutes(s.path("minutesAfterStart").asLong()))));
    }
    return new TunnelSession(from, to, grant);
  }

  /** A request waiting for a person, as the call path makes it after {@code prepare}; then the seed's decision. */
  private void request(JsonNode r, JsonNode who, Task task) {
    String slug = r.path("endpoint").asString();
    EndpointSnapshot s = snapshots.bySlug(slug).orElseThrow();
    EndpointSnapshot.Pair pair = pair(s, r.path("tool").asString(), r.path("connection").asString());
    Caller caller = caller(who);
    ObjectNode arguments = (ObjectNode) r.path("arguments").deepCopy();
    String note = arguments.path("note").asString(null);
    String callId = Ids.next("tc_");
    ToolCall call = new ToolCall(callId, pair.tool().name(), pair.connectionName(), arguments, null);
    JsonNode a = r.path("action");
    List<ActionPreview.Field> impact = new ArrayList<>();
    a.path("impact").forEach(f -> impact.add(new ActionPreview.Field(f.path("label").asString(),
        f.path("value").asString())));
    ActionPreview preview = ActionPreview.of(ActionPreview.Kind.valueOf(a.path("kind").asString()),
        a.path("language").asString(), a.path("text").asString(), a.path("target").asString(),
        a.path("summary").asString()).withImpact(impact).withPayload(arguments.deepCopy());
    AuditWriter.Call auditCall = auditCall(callId, slug, pair, caller, arguments, task);
    // A call of an agent's task came with the client's traceparent; the request keeps it like the call path does.
    String traceparent = task == null ? null : "00-" + auditCall.traceId() + "-" + auditCall.spanId() + "-01";
    ApprovalService.Ticket ticket = approvals.requestOrReuse(new ApprovalService.NewRequest(caller, s.id(), slug,
        callId, call, pair.toolKey(), pair.config(), preview, null, note, r.path("rule").asString(),
        Classification.Kind.MUTATION, traceparent, auditCall, AuditWriter.Delivery.HANDLE));
    JsonNode decide = r.path("decide");
    if (decide.path("decision").asString("").equals("deny")) {
      approvals.decide(ticket.approval().id(), ApprovalService.Decision.DENY, "app",
          decide.path("comment").asString(null), null);
    }
  }

  /** A logged call of the day: its rows as the call path writes them (a denial has only {@code received}). */
  private void call(JsonNode c, JsonNode who, Task task) {
    String slug = c.path("endpoint").asString();
    EndpointSnapshot.Pair pair = pair(snapshots.bySlug(slug).orElseThrow(), c.path("tool").asString(),
        c.path("connection").asString());
    ObjectNode arguments = (ObjectNode) c.path("arguments").deepCopy();
    AuditWriter.Call auditCall = auditCall(Ids.next("tc_"), slug, pair, caller(who), arguments, task);
    String decision = c.path("decision").asString();
    String rule = c.path("rule").asString(null);
    switch (decision) {
      case "denied" -> audit.received(auditCall, AuditWriter.Received.of(AuditWriter.Verdict.DENIED,
          Classification.Kind.DENY, rule));
      case "allowed", "error" -> {
        audit.received(auditCall, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
        boolean error = decision.equals("error");
        String text = error ? c.path("error").asString() : c.path("result").asString();
        long duration = c.path("durationMs").asLong(0);
        long resource = c.path("resourceMs").asLong(0);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        audit.completed(auditCall, new AuditWriter.Completion(error ? AuditWriter.Verdict.ERROR
            : AuditWriter.Verdict.ALLOWED, null, null, null, null, duration, duration - resource, resource, null,
            (long) bytes.length, CanonicalJson.sha256Hex(JsonNodeFactory.instance.stringNode(text)),
            error ? null : text, 0, false, error ? c.path("errorKind").asString("RESOURCE_ERROR") : null,
            error ? text : null, null, null, null));
      }
      default -> throw new IllegalArgumentException("Unknown decision " + decision + " in " + RESOURCE);
    }
  }

  /**
   * The audit identity of a seeded call. A call of an agent's task carries the client's trace id and the task's name,
   * as a client that sends {@code traceparent} and {@code ai.msrv.gaterook/task} does: Activity › Traces lists it.
   */
  private AuditWriter.Call auditCall(String callId, String slug, EndpointSnapshot.Pair pair, Caller caller,
      ObjectNode arguments, Task task) {
    ObjectNode facets = JsonNodeFactory.instance.objectNode();
    facets.put("nature", pair.tool().nature().name().toLowerCase(Locale.ROOT));
    if (task != null) {
      facets.put("task", task.title());
    }
    if (arguments.path("runbook").isString()) {
      facets.put("runbook", arguments.path("runbook").asString());
    }
    ObjectNode args = arguments.deepCopy();
    args.put("connection", pair.connectionName());
    return new AuditWriter.Call(callId, slug, pair.tool().name(), pair.toolKey(), pair.connectionName(),
        pair.config().env(), caller, caller.clientHint(), null, "2025-11-25", task == null
            ? HexFormat.of().formatHex(bytes(16)) : task.traceId(), HexFormat.of().formatHex(bytes(8)), task != null,
        args, CanonicalJson.sha256Hex(args), facets);
  }

  private static Caller caller(JsonNode who) {
    return new Caller(Caller.Kind.BRIDGE, who.path("principal").asString(), who.path("label").asString(),
        who.path("client").asString(), Ingress.LOCAL, "127.0.0.1");
  }

  private static EndpointSnapshot.Pair pair(EndpointSnapshot s, String tool, String connection) {
    return s.pairs().stream().filter(p -> p.tool().name().equals(tool) && p.connectionName().equals(connection))
        .findFirst().orElseThrow(() -> new IllegalStateException(RESOURCE + ": no " + tool + " on " + connection
            + " at " + s.slug()));
  }

  private static byte[] bytes(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }
}
