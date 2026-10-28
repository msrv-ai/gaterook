package ai.msrv.gaterook.core.admin.connections;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Modes;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.connections.ConnectionUsage;
import ai.msrv.gaterook.core.connections.HealthMonitor;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connections and the connection wizard (ARCHITECTURE.md §6 connections, connection-new, {@code ?duplicate=},
 * {@code ?edit=}). Secrets go one way: a form sends a value once ({@code secrets: {"/credentials/password":
 * {source: "local", value}}}), it is encrypted (or used for one test) and zeroed; every response says only whether a
 * field is stored, where and in which version. A duplicate copies settings, never credentials.
 */
@RestController
@RegisterReflectionForBinding({ConnectionsController.ListPage.class, ConnectionsController.Row.class,
    ConnectionsController.Health.class, ConnectionsController.Facets.class, ConnectionsController.Detail.class,
    ConnectionsController.StoredSecret.class, ConnectionsController.UsedBy.class, ConnectionsController.Draft.class,
    ConnectionsController.Deleted.class, ConnectionsController.TestAllStarted.class,
    ConnectionsController.PolicyChanged.class, TestDtos.Outcome.class, TestDtos.Permissions.class,
    TestDtos.Hint.class, TestDtos.Snippet.class, TestDtos.Check.class, TestDtos.Error.class,
    TestDtos.Confirmation.class, TestDtos.Confirmed.class})
class ConnectionsController {

  /**
   * A secret field as a form sends it: a new value ({@code local}), a reference to an external store
   * ({@code reference}, {@code ref: "vault://kv/stage/db#password"}), or Vault dynamic credentials
   * ({@code vault-dynamic}, {@code ref: "vault://database/creds/<role>"}); {@code store} names the store when more
   * than one of the kind is connected.
   */
  record SecretSource(String source, char[] value, String ref, String store) {

    @Override
    public String toString() {
      return "SecretSource[source=" + source + (value == null ? "" : ", value=‹redacted›") + "]";
    }
  }

  /** {@code POST /api/connections/test}: a draft; {@code existing} lets empty secret fields use what is stored. */
  record TestDraft(String type, String env, JsonNode config, Map<String, SecretSource> secrets, String existing,
      String confirm) {}

  /** The administrative remote folder browser; its entries do not grant agent access. */
  record BrowseFiles(String type, String env, JsonNode config, Map<String, SecretSource> secrets, String existing,
      String confirm, String path) {}

  /** {@code confirm}: {@code danger} after the core asked (409 with {@code confirm}) about a prod user's rights. */
  record Create(String name, String type, String env, JsonNode config, Map<String, SecretSource> secrets,
      String reason, String confirm) {}

  /** {@code PUT /api/connections/{name}}: settings and env; secrets stay as stored. */
  record Update(JsonNode config, String env, Long version, String reason, String confirm) {}

  record Delete(String confirmName, String reason) {}

  record Confirm(String confirmationId, String reason) {}

  record Reason(String reason) {}

  /** {@code kind}: {@code allow-private} (the address a test found private, for this connection only). */
  record PolicySuggestion(String kind, String cidr, String reason) {}

  // ── Responses ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * @param healthIntervalSeconds how often the core checks every connection's health ("checked every minute"), 0 when
   *                              it doesn't check by itself
   */
  record ListPage(List<Row> items, Facets facets, int total, long healthIntervalSeconds) {}

  /**
   * {@code health}: {@code healthy}, {@code degraded}, {@code unreachable} or {@code unknown}; {@code since}: when the
   * checks started saying so ("unreachable since 11:42"), {@code null} before the first check.
   */
  record Health(String state, Long latencyMs, String message, Instant checkedAt, Instant since) {}

  record Facets(Map<String, Integer> health, Map<String, Integer> env, Map<String, Integer> type) {}

  /**
   * One row of Connections.
   *
   * @param host          where it connects, in the kit's words ("loki.dev.internal:3100", "k8s.dev.internal:6443 · ns
   *                      app-dev", "eu-central-1 · acme-stage", "https://billing.stage.acme.dev")
   * @param typeLabel     the Type column ("PostgreSQL 16", "Loki", "SSH")
   * @param variant       the form variant ({@code loki}, {@code gcp-logging}), or {@code null}
   * @param serverVersion what the last successful test called the server, or {@code null}
   * @param secretSource  {@code encrypted}, {@code vault}, {@code gcp-sm}, {@code aws-sm}, {@code short-lived} or
   *                      {@code null} without secrets
   * @param badges        labels the connector marks in its form ({@code x-badge}: "Runbooks only") and what its last
   *                      test found ("Read replica")
   */
  record Row(String name, String type, String typeName, String env, String host, List<String> badges,
      String secretSource, Health health, List<String> usedBy, boolean verified, long version, String typeLabel,
      String variant, String serverVersion) {}

  /** A stored secret field: never its value, length or hash. {@code state}: {@code stored}, {@code needs-value}, … */
  record StoredSecret(String field, String secretId, String name, String state, Integer version, String source) {}

  record UsedBy(String tool, String endpoint, String mode) {}

  /**
   * {@code GET /api/connections/{name}}: the edit form.
   *
   * @param metadata the facts of the last successful test that the form lists options from ({@code x-options-from:
   *                 metadata.kubeconfigContexts}); only those keys, never a secret
   */
  record Detail(String name, String type, String typeName, String env, JsonNode config, long version,
      List<StoredSecret> secrets, Health health, List<Long> spark24h, List<UsedBy> usedBy, boolean verified,
      JsonNode metadata, String host, String typeLabel, String variant, String serverVersion,
      long healthIntervalSeconds) {}

  /** {@code ?duplicate=}: settings to start from; {@code notice} is the kit's info alert. */
  record Draft(String type, String env, JsonNode config, List<String> emptySecretFields, String notice) {}

  record Deleted(List<String> stoppedTools, int rejectedApprovals, List<String> templates, List<String> clients,
      List<String> secretsDestroyed) {}

  record TestAllStarted(int connections) {}

  record PolicyChanged(String env, long version, List<String> allowedPrivateRanges) {}

  private final ConnectionService connections;
  private final ConnectionConfigs configs;
  private final ConnectionTester tester;
  private final HealthMonitor health;
  private final ConnectorRegistry registry;
  private final PolicyService policies;
  private final Masker masker;

  ConnectionsController(ConnectionService connections, ConnectionConfigs configs, ConnectionTester tester,
      HealthMonitor health, ConnectorRegistry registry, PolicyService policies, Masker masker) {
    this.connections = connections;
    this.configs = configs;
    this.tester = tester;
    this.health = health;
    this.registry = registry;
    this.policies = policies;
    this.masker = masker;
  }

  // ── List ────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * @param kind {@code connection} lists the resources (the kit's Connections: "12 connections"), {@code mcp-server}
   *             the external MCP servers (Connectors › MCP servers); without it, both
   */
  @GetMapping("/api/connections")
  ListPage list(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "env", required = false) String env,
      @RequestParam(name = "health", required = false) String healthState,
      @RequestParam(name = "kind", required = false) String kind) {
    if (kind != null && !kind.isBlank() && !"connection".equals(kind) && !"mcp-server".equals(kind)) {
      throw ApiRequestException.unprocessable("Unknown kind", "List connection or mcp-server rows.", "/kind");
    }
    Map<String, ConnectionConfigs.Stored> stored = configs.all().stream()
        .collect(Collectors.toMap(ConnectionConfigs.Stored::id, Function.identity()));
    List<Row> all = connections.list().stream()
        .filter(s -> kind == null || kind.isBlank() || "mcp-server".equals(kind) == mcpServer(s.type()))
        .map(s -> row(s, stored.get(s.id()))).toList();
    String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
    List<Row> shown = all.stream()
        .filter(r -> query.isEmpty() || r.name().contains(query) || (r.host() != null
            && r.host().toLowerCase(Locale.ROOT).contains(query)) || r.typeName().toLowerCase(Locale.ROOT)
            .contains(query))
        .filter(r -> type == null || type.isBlank() || r.type().equals(type))
        .filter(r -> env == null || env.isBlank() || r.env().equals(env))
        .filter(r -> healthState == null || healthState.isBlank() || r.health().state().equals(healthState))
        .toList();
    Map<String, Integer> byHealth = new LinkedHashMap<>();
    for (String h : List.of("healthy", "degraded", "unreachable", "unknown")) {
      byHealth.put(h, 0);
    }
    Map<String, Integer> byEnv = new LinkedHashMap<>();
    Map<String, Integer> byType = new LinkedHashMap<>();
    for (Row r : all) {
      byHealth.merge(r.health().state(), 1, Integer::sum);
      byEnv.merge(r.env(), 1, Integer::sum);
      byType.merge(r.type(), 1, Integer::sum);
    }
    return new ListPage(shown, new Facets(byHealth, byEnv, byType), all.size(), health.interval().toSeconds());
  }

  @PostMapping("/api/connections/test-all")
  ResponseEntity<TestAllStarted> testAll() {
    int count = health.testAll();
    if (count < 0) {
      throw ApiRequestException.conflict("Already testing", "Test all is still running; results arrive one by one.");
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new TestAllStarted(count));
  }

  // ── Wizard ──────────────────────────────────────────────────────────────────────────────────────────────────

  @PostMapping("/api/connections/test")
  TestDtos.Outcome testDraft(@RequestBody TestDraft request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      return TestDtos.outcome(tester.testDraft(request.type(), env(request.env()), request.config(), secrets,
          request.existing(), request.confirm()));
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @PostMapping("/api/connections/test/confirm")
  TestDtos.Confirmed confirmDraft(@RequestBody Confirm request) {
    return TestDtos.confirmed(tester.confirm(request.confirmationId(), Reasons.check(request.reason())));
  }

  @PostMapping("/api/connections/databases")
  TestDtos.Outcome discoverDatabases(@RequestBody TestDraft request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      return TestDtos.outcome(tester.discoverDatabases(request.type(), env(request.env()), request.config(), secrets,
          request.existing(), request.confirm()));
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @PostMapping("/api/connections/browse-files")
  TestDtos.Outcome browseFiles(@RequestBody BrowseFiles request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      return TestDtos.outcome(tester.browseFiles(request.type(), env(request.env()), request.config(), secrets,
          request.existing(), request.confirm(), request.path()));
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @PostMapping("/api/connections")
  ResponseEntity<Row> create(@RequestBody Create request) {
    Map<String, SecretInput> secrets = secrets(request.secrets());
    try {
      ConnectionService.Summary s = connections.create(request.name(), request.type(), env(request.env()),
          request.config(), secrets, Reasons.check(request.reason()), request.confirm());
      return ResponseEntity.status(HttpStatus.CREATED).body(row(s, configs.byId(s.id()).orElse(null)));
    } finally {
      clear(secrets, request.secrets());
    }
  }

  @GetMapping("/api/connections/{name}/duplicate")
  Draft duplicate(@PathVariable String name) {
    ConnectionService.Draft d = connections.duplicate(name);
    ConnectionService.Detail detail = connections.get(name);
    return new Draft(d.type(), d.env().id(), d.config(), detail.secrets().stream()
        .map(ConnectionService.SecretField::field).toList(), "Settings are copied from " + name + ". Secrets are "
        + "never copied: enter them again.");
  }

  // ── Edit ────────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/connections/{name}")
  Detail get(@PathVariable String name) {
    ConnectionService.Detail d = connections.get(name);
    ConnectionService.Summary s = connections.summary(name);
    ConnectorManifest manifest = registry.manifest(d.type()).orElse(null);
    String typeName = manifest == null ? d.type() : manifest.name();
    return new Detail(d.name(), d.type(), typeName, d.env().id(), d.config(), d.version(),
        d.secrets().stream().map(f -> new StoredSecret(f.field(), f.secretId(), f.name(), f.state(), f.version(),
            f.source())).toList(), health(d.health()), List.of(),
        d.usedBy().stream().map(u -> new UsedBy(u.tool(), u.endpoint(), Modes.id(u.mode()))).toList(), d.verified(),
        formMetadata(manifest, configs.byName(name).map(c -> c.config().metadata()).orElse(null)), s.target(),
        s.typeLabel(), s.variant(), s.serverVersion(), health.interval().toSeconds());
  }

  /**
   * The keys of a connection's metadata its form lists options from ({@code x-options-from: metadata.<key>}): the
   * kubeconfig contexts of Kubernetes. The rest (discovery, built-in functions) is for {@code classify} only.
   */
  static JsonNode formMetadata(ConnectorManifest manifest, JsonNode metadata) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (manifest == null || metadata == null || !metadata.isObject()) {
      return out;
    }
    for (String path : ConnectionForms.optionsFrom(manifest.configSchema())) {
      String key = path.split("\\.", 2)[0];
      if (metadata.has(key)) {
        out.set(key, metadata.get(key).deepCopy());
      }
    }
    return out;
  }

  @PutMapping("/api/connections/{name}")
  Row update(@PathVariable String name, @RequestBody Update request) {
    if (request.version() == null) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/version");
    }
    ConnectionService.Summary s = connections.update(name, env(request.env()), request.config(), request.version(),
        Reasons.check(request.reason()), request.confirm());
    return row(s, configs.byId(s.id()).orElse(null));
  }

  @DeleteMapping("/api/connections/{name}")
  Deleted delete(@PathVariable String name, @RequestBody(required = false) Delete request) {
    ConnectionService.DeleteSummary d = connections.delete(name, request == null ? null : request.confirmName(),
        request == null ? null : Reasons.check(request.reason()));
    return new Deleted(d.stoppedTools(), d.rejectedApprovals(), d.templates(), d.clients(), d.secretsDestroyed());
  }

  @PostMapping("/api/connections/{name}/test")
  TestDtos.Outcome test(@PathVariable String name) {
    return TestDtos.outcome(tester.testStored(name));
  }

  @PostMapping("/api/connections/{name}/test/confirm")
  TestDtos.Confirmed confirm(@PathVariable String name, @RequestBody Confirm request) {
    connections.summary(name);
    return TestDtos.confirmed(tester.confirm(request.confirmationId(), Reasons.check(request.reason())));
  }

  /** The person reviewed a connection changed outside GateRook and keeps it as stored. */
  @PostMapping("/api/connections/{name}/confirm-integrity")
  Row confirmIntegrity(@PathVariable String name, @RequestBody(required = false) Reason request) {
    ConnectionService.Summary s = connections.confirmIntegrity(name,
        request == null ? null : Reasons.check(request.reason()));
    return row(s, configs.byId(s.id()).orElse(null));
  }

  /**
   * A test found the host on a private address: allow that address for this connection in its environment's HTTP
   * egress ({@code http.allowedPrivateRanges}), as a policy change with its own Setup change.
   */
  @PostMapping("/api/connections/{name}/policy-suggestion")
  PolicyChanged policySuggestion(@PathVariable String name, @RequestBody PolicySuggestion request) {
    if (!"allow-private".equals(request.kind())) {
      throw ApiRequestException.unprocessable("Unknown suggestion", "The only suggestion is allow-private.", "/kind");
    }
    String cidr = request.cidr() == null ? "" : request.cidr().strip();
    if (!cidr.matches("[0-9a-fA-F:.]{2,45}/[0-9]{1,3}")) {
      throw ApiRequestException.unprocessable("Invalid range", "Give an address range like 10.30.4.12/32.", "/cidr");
    }
    ConnectionService.Summary c = connections.summary(name);
    PolicyService.EnvPolicy current = policies.get(c.env());
    ObjectNode sections = JsonNodeFactory.instance.objectNode();
    ArrayNode ranges = sections.putObject("http").putArray("allowedPrivateRanges");
    boolean present = false;
    for (JsonNode existing : current.sections().path("http").path("allowedPrivateRanges")) {
      ranges.add(existing.deepCopy());
      present |= cidr.equals(existing.path("cidr").asString(null)) && names(existing.path("connections"))
          .contains(name);
    }
    if (!present) {
      ObjectNode entry = ranges.addObject();
      entry.put("cidr", cidr);
      entry.putArray("connections").add(name);
    }
    PolicyService.EnvPolicy updated = policies.update(c.env(), sections, current.version(),
        Reasons.check(request.reason()), null);
    List<String> after = new ArrayList<>();
    updated.sections().path("http").path("allowedPrivateRanges").forEach(r -> after.add(r.path("cidr").asString()));
    return new PolicyChanged(updated.env().id(), updated.version(), List.copyOf(after));
  }

  private static List<String> names(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString("")));
    return out;
  }

  /**
   * What an agent sees of the connection in {@code describe_connection}, without the connector's live part: the
   * settings its form marks {@code x-agent-visible}, the environment's limits, its tools, and that its credentials are
   * stored. Masked with the environment's policy, as the tool's answer is.
   */
  @GetMapping("/api/connections/{name}/describe")
  JsonNode describe(@PathVariable String name) {
    ConnectionConfigs.Stored s = configs.byName(name).orElseThrow(() -> ai.msrv.gaterook.core.platform.ProblemException
        .notFound("Connection not found", "There is no connection " + name + "."));
    ConnectionConfig cfg = s.config();
    ConnectorManifest manifest = registry.requireManifest(cfg.type());
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("name", cfg.name());
    out.put("type", cfg.type());
    out.put("typeName", manifest.name());
    out.put("env", cfg.env().id());
    out.put("description", manifest.description());
    ObjectNode visible = out.putObject("settings");
    if (manifest.configSchema() != null) {
      collectVisible(manifest.configSchema(), "", cfg, visible);
    }
    Limits l = policies.limits(cfg.env());
    ObjectNode limits = out.putObject("limits");
    limits.put("maxRows", l.maxRows());
    limits.put("maxLines", l.maxLines());
    limits.put("maxResponseKb", l.maxResponseBytes() / 1024);
    limits.put("statementTimeoutMs", l.statementTimeout().toMillis());
    limits.put("toolTimeoutSeconds", l.toolTimeout().toSeconds());
    ArrayNode tools = out.putArray("tools");
    manifest.tools().forEach(t -> tools.addObject().put("name", t.name()).put("title", t.title()));
    ObjectNode masked = (ObjectNode) masker.tree(out, new Taint(), policies.maskPolicy(cfg.env())).tree();
    ObjectNode credentials = masked.putObject("credentials");
    cfg.secretFields().forEach(f -> credentials.put(f, s.secretIds().containsKey(f) ? "stored" : "not set"));
    return masked;
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private Row row(ConnectionService.Summary s, ConnectionConfigs.Stored stored) {
    List<String> badges = new ArrayList<>();
    ConnectorManifest manifest = registry.manifest(s.type()).orElse(null);
    if (stored != null && manifest != null && manifest.configSchema() != null) {
      collectBadges(manifest.configSchema(), "", stored.config().config(), s.env(), badges);
    }
    // A database the last test found in recovery (PostgreSQL) or read only answers reads only: the kit's badge.
    if (stored != null && stored.config().metadata() != null
        && stored.config().metadata().path("replica").asBoolean(false) && !badges.contains("Read replica")) {
      badges.add("Read replica");
    }
    return new Row(s.name(), s.type(), s.typeName(), s.env().id(), s.target(), List.copyOf(badges), s.secretSource(),
        health(s.health()), s.usedBy(), s.verified(), s.version(), s.typeLabel(), s.variant(), s.serverVersion());
  }

  private boolean mcpServer(String type) {
    return registry.manifest(type).map(m -> m.kind() == ConnectorManifest.Kind.MCP_SERVER).orElse(false);
  }

  private static Health health(ConnectionConfigs.Health h) {
    if (h == null || h.state() == null) {
      return new Health("unknown", null, null, h == null ? null : h.checkedAt(), h == null ? null : h.since());
    }
    return new Health(h.state().name().toLowerCase(Locale.ROOT), h.latencyMs(), h.message(), h.checkedAt(),
        h.since());
  }

  /**
   * {@code x-badge: "Runbooks only"} on a boolean property: the badge shows while the setting is on — the config's
   * value, else the environment's default ({@code x-env-default}: SSH on prod runs runbooks only), else the default.
   */
  private static void collectBadges(JsonNode schema, String pointer, JsonNode config, Env env, List<String> out) {
    for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
      String child = pointer + "/" + p.getKey();
      JsonNode def = p.getValue();
      if (def.path("x-badge").isString() && on(def, config.at(child), env)) {
        out.add(def.get("x-badge").asString());
      }
      if (def.path("properties").isObject()) {
        collectBadges(def, child, config, env, out);
      }
    }
  }

  private static boolean on(JsonNode def, JsonNode value, Env env) {
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    JsonNode envDefault = def.path("x-env-default").path(env.id());
    return envDefault.isBoolean() ? envDefault.asBoolean() : def.path("default").asBoolean(false);
  }

  private static void collectVisible(JsonNode schema, String pointer, ConnectionConfig cfg, ObjectNode out) {
    for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
      String child = pointer + "/" + p.getKey();
      JsonNode def = p.getValue();
      if (def.path("x-agent-visible").asBoolean(false) && !def.path("writeOnly").asBoolean(false)) {
        JsonNode v = cfg.config().at(child);
        if (!v.isMissingNode()) {
          out.set(child.substring(1).replace('/', '.'), v);
        }
      }
      if (def.path("properties").isObject()) {
        collectVisible(def, child, cfg, out);
      }
    }
  }

  static Env env(String id) {
    if (id == null || id.isBlank()) {
      throw ApiRequestException.unprocessable("Environment missing", "Choose dev, stage or prod.", "/env");
    }
    try {
      return Env.of(id);
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Unknown environment", "Choose dev, stage or prod.", "/env");
    }
  }

  /** The form's secret fields as the connections module takes them; a reference is bound to its store there. */
  private static Map<String, SecretInput> secrets(Map<String, SecretSource> given) {
    Map<String, SecretInput> out = new LinkedHashMap<>();
    if (given == null) {
      return out;
    }
    given.forEach((field, s) -> {
      if (s == null) {
        return;
      }
      String source = s.source() == null ? (s.value() != null ? "local" : "reference") : s.source();
      switch (source) {
        case "local" -> out.put(field, SecretInput.local(s.value() == null ? new char[0] : s.value().clone()));
        case "reference" -> out.put(field, SecretInput.reference(s.ref() == null ? "" : s.ref(), s.store()));
        // The form's source cards name the store kind; the reference itself says the same by its scheme.
        case "vault", "gcp-sm", "aws-sm" -> {
          if (s.ref() == null || !s.ref().startsWith(source + "://")) {
            throw ApiRequestException.unprocessable("Invalid reference", "A " + source + " reference starts with "
                + source + "://.", "/secrets");
          }
          out.put(field, SecretInput.reference(s.ref(), s.store()));
        }
        case "vault-dynamic" -> out.put(field, SecretInput.dynamic(s.ref() == null ? "" : s.ref(), s.store()));
        default -> throw ApiRequestException.unprocessable("Unknown secret source", "A secret is stored here "
            + "(local), referenced in an external store (reference) or issued by Vault (vault-dynamic).",
            "/secrets");
      }
    });
    return out;
  }

  private static void clear(Map<String, SecretInput> secrets, Map<String, SecretSource> given) {
    secrets.values().forEach(SecretInput::clear);
    if (given != null) {
      given.values().forEach(s -> {
        if (s != null && s.value() != null) {
          Arrays.fill(s.value(), '\0');
        }
      });
    }
  }
}
