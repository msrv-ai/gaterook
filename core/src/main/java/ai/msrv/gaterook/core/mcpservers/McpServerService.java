package ai.msrv.gaterook.core.mcpservers;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.support.egress.EgressDenial;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.connectors.CallContext;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.mcpservers.internal.McpServerRows;
import ai.msrv.gaterook.core.mcpservers.internal.TypedSecrets;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerEntity;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerRepository;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * External MCP servers (ARCHITECTURE.md §7.5, step C10): a server is a connection of type {@code mcp-server} plus its
 * reviewed state in {@code mcp_server}. Adding one starts it once with the values the person typed (nothing is saved
 * if it doesn't start), lists its tools and stores that list as the snapshot agents get; every later start that lists
 * something else replaces the snapshot with a Setup change and a new connection version, so endpoints take new tools
 * in as Off, drop gone ones and ask again for a changed tool's Read only.
 *
 * <p>The values of variables and headers are the connection's secrets: stored and resolved by the {@code secrets}
 * module, never in the config, the snapshot, a log or an answer.
 */
@Service
public class McpServerService {

  public static final String TYPE = "mcp-server";
  static final String STATE_REVIEWED = "reviewed";
  private static final Logger log = LoggerFactory.getLogger(McpServerService.class);
  private static final java.util.regex.Pattern PREFIX = java.util.regex.Pattern.compile("^[a-z][a-z0-9]{0,14}_$");

  /** What adding, testing, starting or stopping answers: its tool count before and after (UI toast). */
  public record Started(String name, String state, Integer tools, Integer previousTools) {}

  /**
   * One reviewed tool: as agents see it ({@code name}), as the server calls it ({@code original}), and what the
   * server declared about it ({@code annotations}), which GateRook doesn't verify.
   *
   * @param nature {@code read} when the server declares it read-only, else {@code mutation}
   */
  public record ToolView(String name, String original, String title, String description, String nature,
      JsonNode annotations, String declaredBy, JsonNode inputSchema) {}

  /** What will run, before it runs. */
  public record ReviewView(McpServerRuntime.Review review, String name, String catalogEntry, String protocol) {}

  /**
   * A server to review or add: a config as the Add MCP server form sends it (with {@code name}) and the values of its
   * new secrets by JSON pointer ({@code /env/0/secretRef}), or a catalog entry ({@code github-mcp@0.6.2}).
   */
  public record Source(JsonNode config, Map<String, SecretInput> secrets, String catalog, String env) {}

  private final McpServerRepository repository;
  private final McpServerRows rows;
  private final McpServerSource source;
  private final ObjectProvider<McpServerRuntime> runtime;
  private final ObjectProvider<McpServerCatalog> catalog;
  private final ConnectionService connections;
  private final ConnectionConfigs configs;
  private final ConnectionTester tester;
  private final SecretService secrets;
  private final CallContexts contexts;
  private final EnvironmentPolicies policies;
  private final ConnectorRegistry registry;
  private final IntegrityAlerts alerts;
  private final Masker masker;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public McpServerService(McpServerRepository repository, McpServerRows rows, McpServerSource source,
      ObjectProvider<McpServerRuntime> runtime, ObjectProvider<McpServerCatalog> catalog,
      ConnectionService connections, ConnectionConfigs configs, ConnectionTester tester, SecretService secrets,
      CallContexts contexts, EnvironmentPolicies policies, ConnectorRegistry registry, IntegrityAlerts alerts,
      Masker masker, ObjectProvider<SetupChangeRecorder> setupChanges, JsonMapper json, Clock clock,
      PlatformTransactionManager transactions) {
    this.repository = repository;
    this.rows = rows;
    this.source = source;
    this.runtime = runtime;
    this.catalog = catalog;
    this.connections = connections;
    this.configs = configs;
    this.tester = tester;
    this.secrets = secrets;
    this.contexts = contexts;
    this.policies = policies;
    this.registry = registry;
    this.alerts = alerts;
    this.masker = masker;
    this.setupChanges = setupChanges;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  // ── Review and add ──────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/mcp-servers/review}: the command as it will run, its variables, sandbox and warnings. */
  public ReviewView review(Source request) {
    Draft d = draft(request, false);
    McpServerRuntime.Review r = requireRuntime().review(d.cfg());
    return new ReviewView(r, d.cfg().name(), d.catalogEntry(), null);
  }

  /**
   * {@code POST /api/mcp-servers}: installs a catalog server's pinned package, starts the server once with the typed
   * values, and only when it answered stores the connection, its secrets and the snapshot of its tools.
   *
   * @throws ProblemException 422 when it isn't acknowledged, its settings are wrong or it doesn't start; 409 for a
   *                          name or prefix in use
   */
  public Started create(Source request, boolean acknowledge, String reason) {
    try {
      if (!acknowledge) {
        throw ProblemException.unprocessable("Review it first", "Read the review and confirm that this server may run "
            + "on this computer with the secrets you gave it.", "/acknowledge");
      }
      Draft d = draft(request, true);
      McpServerRuntime rt = requireRuntime();
      if (d.entry() != null) {
        try {
          McpServerRuntime.NpmInstall installed = rt.install(d.cfg().name(), d.entry().pkg(), Duration.ofMinutes(5));
          ArrayNode command = d.config().putArray("command");
          installed.command().forEach(command::add);
          d.entry().args().forEach(command::add);
          d = d.withConfig(d.config(), installed.integrity());
        } catch (ConnectorException e) {
          throw ProblemException.unprocessable("Not installed", e.getMessage(), "/catalog");
        }
      }
      McpServerRuntime.Handshake h = probe(d, request.secrets());
      return store(d, h, request.secrets(), reason);
    } finally {
      if (request.secrets() != null) {
        request.secrets().values().forEach(s -> {
          if (s != null) {
            s.clear();
          }
        });
      }
    }
  }

  /** The server of a request, before anything is saved: its config as the connection will store it. */
  private Draft draft(Source request, boolean forCreate) {
    Env env = request.env() == null || request.env().isBlank() ? Env.DEV : env(request.env());
    ObjectNode config;
    String name;
    McpServerCatalog.Entry entry = null;
    if (request.catalog() != null && !request.catalog().isBlank()) {
      McpServerCatalog c = catalog.getIfAvailable();
      if (c == null) {
        throw ProblemException.unprocessable("Catalog not available", "This build of GateRook has no catalog of "
            + "MCP servers yet. Add the server with its command instead.", "/catalog");
      }
      entry = c.entry(request.catalog()).orElseThrow(() -> ProblemException.unprocessable("Not in the catalog",
          request.catalog() + " isn't in the catalog.", "/catalog"));
      if (!entry.signed()) {
        throw ProblemException.unprocessable("Not signed", entry.ref() + " isn't signed by the catalog key, so it "
            + "isn't installed.", "/catalog");
      }
      JsonNode given = request.config();
      name = given != null && given.path("name").isString() ? given.path("name").asString() : entry.name();
      config = JsonNodeFactory.instance.objectNode();
      config.put("transport", "stdio");
      ArrayNode command = config.putArray("command");
      command.add("node");
      command.add(binOf(entry.pkg()));
      entry.args().forEach(command::add);
      ArrayNode vars = config.putArray("env");
      entry.variables().forEach(v -> vars.addObject().put("name", v));
      config.put("catalogEntry", entry.ref());
      if (given != null && given.path("toolPrefix").isString()) {
        config.put("toolPrefix", given.path("toolPrefix").asString());
      }
    } else {
      if (request.config() == null || !request.config().isObject()) {
        throw ProblemException.unprocessable("Settings missing", "Give the server's command or URL.", "/config");
      }
      config = (ObjectNode) request.config().deepCopy();
      name = config.path("name").isString() ? config.path("name").asString() : null;
      config.remove("name");
      // Only the catalog sets these: a person's command is never pinned by an entry it didn't come from.
      config.remove("catalogEntry");
    }
    if (name == null || !ConnectionService.NAME.matcher(name).matches() || name.length() > 64) {
      throw ProblemException.unprocessable("Invalid name", "Use lower-case letters, digits and hyphens, like "
          + "github-mcp.", "/config/name");
    }
    String prefix = config.path("toolPrefix").isString() ? config.path("toolPrefix").asString() : defaultPrefix(name);
    if (!PREFIX.matcher(prefix).matches()) {
      throw ProblemException.unprocessable("Invalid prefix", "A tool prefix is a lower-case letter, up to 14 letters "
          + "or digits and an underscore, like github_.", "/config/toolPrefix");
    }
    config.put("toolPrefix", prefix);
    String transport = config.path("transport").asString("stdio");
    String bindings = "http".equals(transport) ? "headers" : "env";
    Set<String> fields = new java.util.LinkedHashSet<>();
    Set<String> checked = new java.util.LinkedHashSet<>();
    Map<String, SecretInput> given = request.secrets() == null ? Map.of() : request.secrets();
    for (int i = 0; i < config.path(bindings).size(); i++) {
      JsonNode b = config.path(bindings).get(i);
      String field = "/" + bindings + "/" + i + "/secretRef";
      if (b instanceof ObjectNode o && o.has("secretRef")) {
        // A name of an existing secret: the values a server gets are its own secrets, stored with it.
        o.remove("secretRef");
      }
      // A review shows what would run before the values are typed: every binding counts as given there.
      checked.add(field);
      if (given.containsKey(field)) {
        fields.add(field);
      } else if (forCreate) {
        throw ProblemException.unprocessable("Secret missing", "Enter the value of " + b.path("name").asString(
            "variable " + i) + ". GateRook stores it as a secret of this server.", "/secrets");
      }
    }
    if (forCreate) {
      if (configs.byName(name).isPresent()) {
        throw ProblemException.conflict("Name in use", "A connection named " + name + " already exists.");
      }
      Optional<McpServerEntity> taken = repository.findByToolPrefix(prefix);
      if (taken.isPresent()) {
        String owner = configs.byId(taken.get().getConnectionId()).map(ConnectionConfigs.Stored::name)
            .orElse("another server");
        throw ProblemException.conflict("Prefix in use", prefix + " is the tool prefix of " + owner + ". Choose "
            + "another one.");
      }
      if (registry.reservedPrefixes().contains(prefix)) {
        throw ProblemException.unprocessable("Reserved prefix", prefix + " is the family of GateRook's built-in "
            + "tools. Choose another prefix.", "/config/toolPrefix");
      }
      Optional<String> plugin = pluginWithNamespace(registry.types(), prefix);
      if (plugin.isPresent()) {
        throw ProblemException.conflict("Prefix in use", prefix + " is the namespace of the tools of the plugin "
            + plugin.get() + ". Choose another prefix.");
      }
    }
    // Checked as the connection will be: the type's schema, the env's locks.
    connections.validate(registry.requireManifest(TYPE), config, forCreate ? fields : checked, env);
    ConnectionConfig cfg = new ConnectionConfig("draft-" + Ids.uuid(), name, TYPE, env, 0, config.deepCopy(),
        Set.copyOf(fields), null);
    return new Draft(cfg, config, prefix, entry, entry == null ? null : entry.ref(), null);
  }

  /** What a draft runs as: its throw-away connection, the stored config, the prefix, the catalog entry. */
  private record Draft(ConnectionConfig cfg, ObjectNode config, String prefix, McpServerCatalog.Entry entry,
      String catalogEntry, String npmIntegrity) {

    Draft withConfig(ObjectNode next, String integrity) {
      ConnectionConfig c = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), 0, next.deepCopy(),
          cfg.secretFields(), null);
      return new Draft(c, next, prefix, entry, catalogEntry, integrity);
    }
  }

  /** Starts the draft once with the typed values and stops it again. */
  private McpServerRuntime.Handshake probe(Draft d, Map<String, SecretInput> given) {
    Taint taint = new Taint();
    Map<String, char[]> values = new HashMap<>();
    Map<String, SecretService.DraftReference> references = new HashMap<>();
    if (given != null) {
      given.forEach((field, input) -> {
        if (input == null || !d.cfg().secretFields().contains(field)) {
          return;
        }
        if (input.isLocal()) {
          values.put(field, input.value().clone());
        } else {
          references.put(field, new SecretService.DraftReference(input.reference(), input.dynamic(), input.store()));
        }
      });
    }
    ConnectionConfig cfg = d.cfg();
    Duration budget = startBudget(cfg);
    CallContext ctx = contexts.builder(Ids.next("tst_"), cfg, policies.limits(cfg.env())).timeout(budget)
        .taint(taint).build();
    SecretResolver fallback = references.isEmpty() ? null : secrets.draftResolver(cfg, references, taint);
    try (TypedSecrets resolver = new TypedSecrets(values, fallback, taint)) {
      return requireRuntime().probe(cfg, resolver, ctx);
    } catch (ConnectorException e) {
      EgressDenial denial = EgressDenial.find(e);
      if (denial != null && denial.verdict().suggestedRange().isPresent()) {
        // A server on this computer or the LAN: the environment's policy names the private ranges a connection may
        // reach, and it can do so before the connection exists.
        throw ProblemException.unprocessable("Private address", denial.host() + " is a private address. Allow "
            + denial.verdict().suggestedRange().get() + " for " + cfg.name() + " in Policies › " + cfg.env().id()
            + " (HTTP: allowed private ranges), then add the server again.", "/config/url");
      }
      throw ProblemException.unprocessable("It didn't start", mask(e.getMessage(), taint), null);
    } finally {
      values.values().forEach(v -> java.util.Arrays.fill(v, '\0'));
      contexts.evict(cfg.id());
    }
  }

  private Started store(Draft d, McpServerRuntime.Handshake h, Map<String, SecretInput> given, String reason) {
    List<Map<String, Object>> tools = toolMaps(h.tools());
    String sha = CanonicalJson.sha256Hex(rows.tree(tools));
    String id = writeTx.execute(s -> {
      // The person reviewed the server after one start with these values; no second test inside this transaction.
      ConnectionService.Summary created = connections.create(d.cfg().name(), TYPE, d.cfg().env(), d.config(),
          given == null ? Map.of() : given, reason, ConnectionService.CONFIRM_DANGER);
      McpServerEntity row = new McpServerEntity(created.id(), d.prefix(), tools, STATE_REVIEWED, clock.instant());
      row.handshake(serverInfo(h), h.protocolVersion(), clock.instant());
      row.snapshot(tools, sha, clock.instant());
      row.setNpmIntegrity(d.npmIntegrity());
      row.setCatalogEntry(d.catalogEntry());
      row = repository.saveAndFlush(row);
      rows.sign(row);
      connections.recordMetadata(created.id(), metadata(h, sha));
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MCP_SERVER,
          "MCP server " + d.cfg().name() + " added", null, h.tools().size() + " tools · " + version(h)
              + (d.catalogEntry() == null ? "" : " · " + d.catalogEntry()), reason, d.cfg().env(), null,
          d.cfg().name(), details(h)));
      return created.id();
    });
    source.forget(id);
    configs.byId(id).ifPresent(stored -> {
      if ("with-core".equals(stored.config().config().path("start").asString(null))) {
        Thread.ofVirtual().name("mcp-start-" + stored.name()).start(() -> warm(id));
      }
    });
    return new Started(d.cfg().name(), state(id), h.tools().size(), null);
  }

  // ── The demo world ──────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A server of the demo world (GW_DEMO=1, design/BLUEPRINT.md §9: {@code github-mcp} from the catalog): its
   * connection, the secrets of its variables and the reviewed snapshot of its tools stored as an install stores them,
   * after a handshake the seed gives instead of a real one. Nothing is installed and nothing starts: the seed stands in
   * for the person's review and the first start, and the server starts like any other when a call needs it.
   *
   * @param config     the server's config as an install of the catalog entry writes it ({@code command},
   *                   {@code env} names, {@code toolPrefix}, {@code catalogEntry})
   * @param values     the secrets of its variables by JSON Pointer ({@code /env/0/secretRef}), stored encrypted
   * @param handshake  what its first start would have answered: server info, protocol, the snapshot of its tools
   */
  public Started seedDemo(String name, Env env, ObjectNode config, Map<String, SecretInput> values,
      McpServerRuntime.Handshake handshake, String npmIntegrity, String reason) {
    try {
      // It stands in for the person's review and acknowledge: never outside the demo of a development build.
      if (ai.msrv.gaterook.core.platform.BuildFlags.RELEASE) {
        throw new IllegalStateException("A release build has no demo world");
      }
      ObjectNode stored = config.deepCopy();
      String prefix = stored.path("toolPrefix").asString(defaultPrefix(name));
      stored.put("toolPrefix", prefix);
      ConnectionConfig cfg = new ConnectionConfig("draft-" + Ids.uuid(), name, TYPE, env, 0, stored.deepCopy(),
          Set.copyOf(values.keySet()), null);
      Draft d = new Draft(cfg, stored, prefix, null, stored.path("catalogEntry").asString(null), npmIntegrity);
      return store(d, handshake, values, reason);
    } finally {
      values.values().forEach(v -> {
        if (v != null) {
          v.clear();
        }
      });
    }
  }

  // ── Stored servers ──────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/mcp-servers/{name}/test}: Test connection of the connection (health, tools listed again). */
  public Started test(String name) {
    Stored s = stored(name);
    ConnectionTester.Outcome outcome = tester.testStored(name);
    TestResult r = outcome.result();
    if (!r.ok()) {
      throw ProblemException.unprocessable("Test failed", r.error() == null ? "It didn't answer." : r.error().message(),
          null);
    }
    Integer tools = r.details() != null && r.details().path("tools").isNumber() ? r.details().path("tools").asInt()
        : null;
    return new Started(name, state(s.cfg().id()), tools, s.snapshot().tools().size());
  }

  /** {@code POST /api/mcp-servers/{name}/start|restart}: starts it (or lists its tools again when it runs). */
  public Started start(String name, boolean restart) {
    Stored s = stored(name);
    Taint taint = new Taint();
    CallContext ctx = contexts.builder(Ids.next("mcp_"), s.cfg(), policies.limits(s.cfg().env()))
        .timeout(startBudget(s.cfg())).taint(taint).build();
    try (var resolver = secrets.resolver(s.cfg(), taint)) {
      McpServerRuntime.Handshake h = requireRuntime().start(s.cfg(), s.snapshot(), resolver, ctx, restart);
      return new Started(name, state(s.cfg().id()), h.tools().size(), s.snapshot().tools().size());
    } catch (ConnectorException e) {
      throw ProblemException.unprocessable("It didn't start", mask(e.getMessage(), taint), null);
    }
  }

  /** {@code POST /api/mcp-servers/{name}/stop}: the process and everything it started stop. */
  public Started stop(String name) {
    Stored s = stored(name);
    requireRuntime().stop(s.cfg().id());
    return new Started(name, state(s.cfg().id()), s.snapshot().tools().size(), null);
  }

  /** {@code GET /api/mcp-servers/{name}/tools}: the reviewed snapshot, "Declared by …, not verified". */
  public List<ToolView> tools(String name) {
    Stored s = stored(name);
    List<ToolView> out = new ArrayList<>();
    for (JsonNode t : s.snapshot().tools()) {
      JsonNode a = t.path("annotations");
      boolean read = a.isObject() && a.path("readOnlyHint").asBoolean(false) && !a.path("destructiveHint")
          .asBoolean(false);
      out.add(new ToolView(t.path("exposed").asString(), t.path("original").asString(),
          t.path("title").asString(null), t.path("description").asString(""), read ? "read" : "mutation",
          a.isObject() ? a : null, name, t.path("inputSchema")));
    }
    return out;
  }

  /** {@code DELETE /api/mcp-servers/{name}}: the connection, its secrets and its snapshot go; the process stops. */
  public ConnectionService.DeleteSummary delete(String name, String reason) {
    stored(name);
    return connections.delete(name, name, reason);
  }

  /** The person reviewed a row changed outside GateRook and keeps it as it is. */
  public Started confirmIntegrity(String name, String reason) {
    ConnectionConfigs.Stored c = configs.byName(name).filter(x -> TYPE.equals(x.config().type()))
        .orElseThrow(() -> notFound(name));
    writeTx.executeWithoutResult(s -> {
      McpServerEntity row = repository.findByConnectionId(c.id()).orElseThrow(() -> notFound(name));
      if (rows.verify(row)) {
        return;
      }
      rows.sign(row);
      alerts.resolve("mcp-server", c.id());
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MCP_SERVER,
          "MCP server " + name + " kept as changed outside GateRook", null, null, reason, c.config().env(), null,
          name, null));
    });
    source.forget(c.id());
    return new Started(name, state(c.id()), null, null);
  }

  // ── From the runtime ────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A stored server started or listed its tools again. Nothing is written when it says what the snapshot says;
   * otherwise the snapshot is replaced (Setup change, new connection version) and the endpoints follow.
   */
  void recordHandshake(String connectionId, McpServerRuntime.Handshake h) {
    McpServerEntity current = repository.findByConnectionId(connectionId).orElse(null);
    if (current == null) {
      return;
    }
    List<Map<String, Object>> tools = toolMaps(h.tools());
    String sha = CanonicalJson.sha256Hex(rows.tree(tools));
    Map<String, Object> info = serverInfo(h);
    if (sha.equals(current.getSnapshotSha256()) && Objects.equals(info, current.getServerInfo())
        && Objects.equals(h.protocolVersion(), current.getProtocolVersion())) {
      return;
    }
    ConnectionConfigs.Stored c = configs.byId(connectionId).orElse(null);
    if (c == null || !c.verified()) {
      return;
    }
    writeTx.executeWithoutResult(s -> {
      McpServerEntity row = repository.findById(current.getId()).orElse(null);
      if (row == null) {
        return;
      }
      if (!rows.verify(row)) {
        alerts.report("mcp-server", connectionId, c.name(), "MCP server row");
        return;
      }
      String before = row.getToolsSnapshot().size() + " tools · " + String.valueOf(row.getServerInfo() == null ? "?"
          : row.getServerInfo().get("version"));
      String change = diff(rows.tree(row.getToolsSnapshot()), h.tools());
      row.snapshot(tools, sha, clock.instant());
      row.handshake(info, h.protocolVersion(), clock.instant());
      row = repository.saveAndFlush(row);
      rows.sign(row);
      connections.recordMetadata(connectionId, metadata(h, sha));
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MCP_SERVER,
          "MCP server " + c.name() + (change == null ? " reports another version" : " lists other tools"), before,
          h.tools().size() + " tools · " + version(h) + (change == null ? "" : " · " + change), null, c.config().env(),
          null, c.name(), details(h)));
    });
    source.forget(connectionId);
  }

  /** A server set to start with the core: started as a call would start it (never past a backoff or Failed). */
  void warm(String connectionId) {
    McpServerRuntime rt = runtime.getIfAvailable();
    ConnectionConfigs.Stored c = configs.byId(connectionId).orElse(null);
    if (rt == null || c == null || !c.verified() || !TYPE.equals(c.config().type())
        || !registry.usable(TYPE)) {
      return;
    }
    Optional<McpServerRuntime.Snapshot> snapshot = source.snapshot(c.config());
    if (snapshot.isEmpty()) {
      return;
    }
    Taint taint = new Taint();
    CallContext ctx = contexts.builder(Ids.next("mcp_"), c.config(), policies.limits(c.config().env()))
        .timeout(startBudget(c.config())).taint(taint).build();
    try (var resolver = secrets.resolver(c.config(), taint)) {
      rt.warm(c.config(), snapshot.get(), resolver, ctx);
    } catch (ConnectorException | RuntimeException e) {
      log.warn("MCP server {} didn't start with the core: {}", c.name(), mask(String.valueOf(e.getMessage()), taint));
    }
  }

  /** Every stored server set to start with the core. */
  List<String> startWithCore() {
    List<String> out = new ArrayList<>();
    for (ConnectionConfigs.Stored c : configs.all()) {
      if (TYPE.equals(c.config().type()) && c.verified()
          && "with-core".equals(c.config().config().path("start").asString(null))) {
        out.add(c.id());
      }
    }
    return out;
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private record Stored(ConnectionConfig cfg, McpServerRuntime.Snapshot snapshot) {}

  private Stored stored(String name) {
    ConnectionConfigs.Stored c = configs.byName(name).filter(x -> TYPE.equals(x.config().type()))
        .orElseThrow(() -> notFound(name));
    if (!c.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", name + " was changed outside GateRook. "
          + "Review it in Connections first.");
    }
    McpServerRuntime.Snapshot snapshot = source.snapshot(c.config()).orElseThrow(() -> ProblemException.conflict(
        "Changed outside GateRook", "The reviewed tools of " + name + " were changed outside GateRook. Review "
            + "them in Connectors › MCP servers."));
    return new Stored(c.config(), snapshot);
  }

  private McpServerRuntime requireRuntime() {
    McpServerRuntime r = runtime.getIfAvailable();
    if (r == null) {
      throw ProblemException.unprocessable("Not available", "This build of GateRook runs no external MCP servers.",
          null);
    }
    return r;
  }

  private String state(String connectionId) {
    McpServerRuntime r = runtime.getIfAvailable();
    return r == null ? null : r.process(connectionId).map(v -> v.state().name().toLowerCase(Locale.ROOT))
        .orElse("stopped");
  }

  /** The start timeout of the config and a margin for the tools list. */
  private static Duration startBudget(ConnectionConfig cfg) {
    int seconds = cfg.config().path("startTimeoutSeconds").asInt(30);
    return Duration.ofSeconds(Math.clamp(seconds, 5, 300) + 30L);
  }

  private String mask(String text, Taint taint) {
    return text == null ? null : masker.text(text, taint, MaskPolicy.SECRETS_ONLY).text();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> toolMaps(List<JsonNode> tools) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode t : tools) {
      out.add(json.treeToValue(t, LinkedHashMap.class));
    }
    return out;
  }

  private static Map<String, Object> serverInfo(McpServerRuntime.Handshake h) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (h.serverInfo() != null) {
      for (String key : List.of("name", "title", "version")) {
        if (h.serverInfo().path(key).isString()) {
          out.put(key, h.serverInfo().path(key).asString());
        }
      }
    }
    return out;
  }

  private static String version(McpServerRuntime.Handshake h) {
    return h.serverVersion() == null ? "no version" : h.serverVersion();
  }

  /** What {@code classify} and the tool descriptions read: the server's version, protocol and snapshot hash. */
  private static JsonNode metadata(McpServerRuntime.Handshake h, String sha) {
    ObjectNode m = JsonNodeFactory.instance.objectNode();
    if (h.serverVersion() != null) {
      m.put("serverVersion", h.serverVersion());
    }
    if (h.serverInfo() != null && h.serverInfo().path("name").isString()) {
      m.put("serverName", h.serverInfo().path("name").asString());
    }
    m.put("protocol", h.protocolVersion());
    m.put("tools", h.tools().size());
    m.put("snapshotSha256", sha);
    return m;
  }

  private static JsonNode details(McpServerRuntime.Handshake h) {
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    d.put("protocol", h.protocolVersion());
    ArrayNode names = d.putArray("tools");
    h.tools().forEach(t -> names.add(t.path("exposed").asString()));
    ArrayNode rejected = d.putArray("notPublished");
    h.rejected().forEach(r -> rejected.addObject().put("name", r.name()).put("reason", r.reason()));
    return d;
  }

  /** "1 new, 2 changed, 1 gone" against the stored snapshot; {@code null} when the tools are the same. */
  static String diff(JsonNode before, List<JsonNode> after) {
    Map<String, String> had = new HashMap<>();
    for (JsonNode t : before) {
      had.put(t.path("original").asString(), t.path("definitionHash").asString());
    }
    int added = 0;
    int changed = 0;
    Set<String> now = new HashSet<>();
    for (JsonNode t : after) {
      String o = t.path("original").asString();
      now.add(o);
      if (!had.containsKey(o)) {
        added++;
      } else if (!had.get(o).equals(t.path("definitionHash").asString())) {
        changed++;
      }
    }
    int gone = (int) had.keySet().stream().filter(o -> !now.contains(o)).count();
    if (added + changed + gone == 0) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    if (added > 0) {
      parts.add(added + " new");
    }
    if (changed > 0) {
      parts.add(changed + " changed");
    }
    if (gone > 0) {
      parts.add(gone + " gone");
    }
    return String.join(", ", parts);
  }

  /** {@code node_modules/<name>/<bin>} from the lockfile's entry of the package (npm records each bin there). */
  private static String binOf(McpServerRuntime.NpmPackage pkg) {
    String base = "node_modules/" + pkg.name();
    try {
      JsonNode lock = JsonMapper.builder().build().readTree(pkg.lockfile());
      JsonNode bin = lock.path("packages").path(base).path("bin");
      String rel = bin.isString() ? bin.asString() : bin.path(pkg.bin()).asString(null);
      if (rel != null && !rel.contains("..")) {
        return base + "/" + rel.replaceFirst("^\\./", "");
      }
    } catch (RuntimeException e) {
      // The install says which file it is; the review shows the package folder.
    }
    return base;
  }

  /**
   * The installed plugin whose tools are named under {@code prefix} ({@code jira_}), if any (report 11 §3.2 reserves a
   * plugin's namespace): on an endpoint with both, only the first tool of a name is published, so a server's
   * {@code jira_add_comment} could stand in for the plugin's.
   */
  static Optional<String> pluginWithNamespace(java.util.Collection<ConnectorType> types, String prefix) {
    for (ConnectorType t : types) {
      if (t.kind() != ConnectorManifest.Kind.PLUGIN) {
        continue;
      }
      for (ToolDefinition tool : t.manifest().tools()) {
        if (tool.name().startsWith(prefix)) {
          return Optional.of(t.manifest().name());
        }
      }
    }
    return Optional.empty();
  }

  /** The name up to its first hyphen, letters and digits ({@code github-mcp} → {@code github_}), as the UI shows it. */
  static String defaultPrefix(String name) {
    String base = name.split("-", 2)[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
      base = "mcp" + base;
    }
    return (base.length() > 15 ? base.substring(0, 15) : base) + "_";
  }

  private static Env env(String id) {
    try {
      return Env.of(id);
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Unknown environment", "Choose dev, stage or prod.", "/env");
    }
  }

  private static ProblemException notFound(String name) {
    return ProblemException.notFound("MCP server not found", "There is no MCP server " + name + ".");
  }
}
