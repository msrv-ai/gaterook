package ai.msrv.gaterook.core.mcpservers;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.cloudauth.AwsSigV4;
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
import java.util.function.Consumer;
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
import tools.jackson.databind.node.MissingNode;
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

  /**
   * What will run, before it runs; over HTTP also how the server wants clients to sign in ({@code auth}, from an
   * unauthenticated look at it), {@code null} for a command.
   */
  public record ReviewView(McpServerRuntime.Review review, String name, String catalogEntry, String protocol,
      McpServerSignIns.AuthView auth) {}

  /**
   * A server to review or add: a config as the Add MCP server form sends it (with {@code name}) and the values of its
   * new secrets by JSON pointer ({@code /env/0/secretRef}), or a catalog entry ({@code github-mcp@0.6.2}).
   *
   * @param signIn the finished sign-in of a server with {@code auth.type: "oauth"} ({@code POST …/sign-ins}), to add it
   */
  public record Source(JsonNode config, Map<String, SecretInput> secrets, String catalog, String env, String signIn) {

    public Source(JsonNode config, Map<String, SecretInput> secrets, String catalog, String env) {
      this(config, secrets, catalog, env, null);
    }
  }

  /** The secret field of an OAuth app's client secret ({@code auth.clientSecret} of an HTTP server). */
  static final String CLIENT_SECRET = "/auth/clientSecret";
  /** The secret field of a Google service account key ({@code auth.credentials} of a server on Google Cloud, §7.6). */
  static final String SERVICE_ACCOUNT_KEY = "/auth/credentials/serviceAccountKey";
  /** The secret field of an AWS secret access key ({@code auth.credentials} of a server on AWS, §7.6). */
  static final String SECRET_ACCESS_KEY = "/auth/credentials/secretAccessKey";

  /** What the review says about a URL that carries a key. */
  static final String KEY_IN_URL = "The URL carries a key. GateRook stores it with the server's settings, not as an "
      + "encrypted secret, and the provider or a proxy may log it. If the server accepts the key in a header, add that "
      + "header instead.";

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
  private final McpServerSignIns signIns;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public McpServerService(McpServerRepository repository, McpServerRows rows, McpServerSource source,
      ObjectProvider<McpServerRuntime> runtime, ObjectProvider<McpServerCatalog> catalog,
      ConnectionService connections, ConnectionConfigs configs, ConnectionTester tester, SecretService secrets,
      CallContexts contexts, EnvironmentPolicies policies, ConnectorRegistry registry, IntegrityAlerts alerts,
      Masker masker, ObjectProvider<SetupChangeRecorder> setupChanges, McpServerSignIns signIns, JsonMapper json,
      Clock clock, PlatformTransactionManager transactions) {
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
    this.signIns = signIns;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  // ── Review and add ──────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code POST /api/mcp-servers/review}: the command as it will run, its variables, sandbox and warnings; over HTTP
   * also how the server wants clients to sign in, from one unauthenticated request to it.
   */
  public ReviewView review(Source request) {
    Draft d = draft(request, false);
    McpServerRuntime.Review r = requireRuntime().review(d.cfg());
    McpServerSignIns.AuthView auth = null;
    if ("http".equals(d.config().path("transport").asString(null))) {
      try {
        auth = signIns.discover(d.cfg(), httpUrl(d.config()));
      } catch (ProblemException e) {
        auth = new McpServerSignIns.AuthView("unknown", null, null, null, null, null, e.getMessage());
      }
      r = withoutUrlKey(r);
    }
    return new ReviewView(r, d.cfg().name(), d.catalogEntry(), null, auth);
  }

  /**
   * A review whose URL carries a key, as some servers take it ({@code https://mcp.example.com/<key>/mcp},
   * {@code ?apiKey=…}): the URL shown without it, and a warning. The person may still add the server.
   */
  private McpServerRuntime.Review withoutUrlKey(McpServerRuntime.Review r) {
    String shown = r.url() == null ? null : masker.url(r.url());
    if (shown == null || shown.equals(r.url())) {
      return r;
    }
    List<String> warnings = new ArrayList<>(r.warnings());
    warnings.add(KEY_IN_URL);
    return new McpServerRuntime.Review(r.transport(), r.argv(), r.resolvedCommand(), r.node(), shown, r.variables(),
        r.inherited(), r.notPinned(), r.sandbox(), r.workDir(), r.log(), warnings);
  }

  /**
   * {@code POST /api/mcp-servers/sign-ins}: a sign-in to a server before it is added, with the settings of the review
   * ({@code auth.type: "oauth"}); its client secret, if the person's OAuth app has one, at {@code /auth/clientSecret}.
   */
  public McpServerSignIns.SignInView signIn(Source request) {
    try {
      Draft d = draft(request, false);
      if (!McpServerSignIns.oauth(d.cfg())) {
        throw ProblemException.unprocessable("No sign-in", "Only a server over HTTP set to sign in with OAuth signs "
            + "in.", "/config/auth");
      }
      char[] secret = null;
      SecretInput given = request.secrets() == null ? null : request.secrets().get(CLIENT_SECRET);
      if (given != null && given.isLocal()) {
        secret = given.value().clone();
      }
      return signIns.start(new McpServerSignIns.Target(d.cfg(), null, d.cfg().name(), httpUrl(d.config()),
          d.config().path("auth"), secret));
    } finally {
      clearSecrets(request);
    }
  }

  /** {@code GET …/sign-ins/{id}}. */
  public McpServerSignIns.SignInView signInState(String id) {
    return signIns.view(id);
  }

  /** {@code POST …/sign-ins/{id}/address}: the address the browser ended on, when it couldn't come back. */
  public McpServerSignIns.SignInView signInAddress(String id, String address) {
    return signIns.paste(id, address);
  }

  /** {@code DELETE …/sign-ins/{id}}. */
  public void cancelSignIn(String id) {
    signIns.cancel(id);
  }

  /** The page Electron opens in the browser for the sign-in {@code id}. */
  public String authorizationUrl(String id) {
    return signIns.authorizationUrl(id);
  }

  /** {@code POST /api/mcp-servers/{name}/sign-in}: signs in to a stored server again (or for the first time). */
  public McpServerSignIns.SignInView signIn(String name) {
    Stored s = stored(name);
    if (!McpServerSignIns.oauth(s.cfg())) {
      throw ProblemException.unprocessable("No sign-in", name + " doesn't sign in with OAuth.", null);
    }
    char[] secret = null;
    try (var resolver = secrets.resolver(s.cfg(), new Taint())) {
      if (resolver.isSet(CLIENT_SECRET)) {
        try (SecretResolver.Secret value = resolver.secret(CLIENT_SECRET)) {
          secret = value.chars().clone();
        }
      }
    } catch (ConnectorException e) {
      throw ProblemException.unprocessable("Client secret unavailable", "The client secret of " + name + " can't be "
          + "read: " + e.getMessage(), null);
    }
    return signIns.start(new McpServerSignIns.Target(s.cfg(), s.cfg().id(), name, httpUrl(s.cfg().config()),
        s.cfg().config().path("auth"), secret));
  }

  /** {@code GET /api/mcp-servers/{name}/sign-in}. */
  public McpServerSignIns.StatusView signInStatus(String name) {
    return signIns.status(stored(name).cfg());
  }

  /** {@code POST /api/mcp-servers/{name}/sign-out}: the tokens are revoked where possible and forgotten. */
  public McpServerSignIns.StatusView signOut(String name, String reason) {
    Stored s = stored(name);
    signIns.signOut(s.cfg(), reason);
    return signIns.status(s.cfg());
  }

  private static java.net.URI httpUrl(JsonNode config) {
    String url = config.path("url").asString(null);
    if (url == null) {
      throw ProblemException.unprocessable("URL missing", "Give the server's URL.", "/config/url");
    }
    try {
      return java.net.URI.create(url);
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Invalid URL", "That isn't a URL.", "/config/url");
    }
  }

  private static void clearSecrets(Source request) {
    if (request.secrets() != null) {
      request.secrets().values().forEach(v -> {
        if (v != null) {
          v.clear();
        }
      });
    }
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
      McpServerSignIns.DraftSignIn signIn = null;
      if (McpServerSignIns.oauth(d.cfg())) {
        if (request.signIn() != null && !request.signIn().isBlank()) {
          signIn = signIns.take(request.signIn(), d.cfg());
        } else if (!McpServerSignIns.optional(d.cfg())) {
          throw ProblemException.unprocessable("Sign in first", "Sign in to " + d.cfg().name() + " in your browser "
              + "before it connects.", "/signIn");
        }
      }
      McpServerRuntime.Handshake h = probe(d, request.secrets(), signIn);
      return store(d, h, request.secrets(), reason, signIn);
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
    String prefix = config.path("toolPrefix").isString() ? config.path("toolPrefix").asString()
        : freePrefix(name, p -> registry.reservedPrefixes().contains(p)
            || pluginWithNamespace(registry.types(), p).isPresent() || repository.findByToolPrefix(p).isPresent());
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
    if (config.path("auth").isObject()) {
      signIn(config, credentialMembers(registry.requireManifest(TYPE).configSchema()), key -> {
        checked.add(key);
        if (given.containsKey(key)) {
          fields.add(key);
        } else if (forCreate) {
          throw ProblemException.unprocessable("Secret missing", (SERVICE_ACCOUNT_KEY.equals(key)
              ? "Enter the service account key (JSON)." : "Enter the secret access key.") + " GateRook stores it as "
              + "a secret of this server.", "/secrets");
        }
      });
      if (given.containsKey(CLIENT_SECRET)) {
        fields.add(CLIENT_SECRET);
        checked.add(CLIENT_SECRET);
      }
    }
    // Checked as the connection will be: the type's schema, the env's locks.
    connections.validate(registry.requireManifest(TYPE), config, forCreate ? fields : checked, env);
    ConnectionConfig cfg = new ConnectionConfig("draft-" + Ids.uuid(), name, TYPE, env, 0, config.deepCopy(),
        Set.copyOf(fields), null);
    return new Draft(cfg, config, prefix, entry, entry == null ? null : entry.ref(), null);
  }

  /**
   * Checks the sign-in of a server's config as adding and editing it both do (§7.5, §7.6): only a server at a URL signs
   * in, never beside an Authorization header of its own, and cloud credentials belong to a cloud sign-in only. The
   * client secret or cloud key typed into the config goes (it is a secret of the server, given beside the config), and
   * so do cloud credentials of another sign-in that hold their defaults alone, as the connection form sends them.
   *
   * @param credentialMembers the schemas of the members of {@code auth.credentials}, with their defaults
   * @param key               told the secret field of the cloud key the credentials come from, when they need one
   */
  static void signIn(ObjectNode config, JsonNode credentialMembers, Consumer<String> key) {
    if (!(config.path("auth") instanceof ObjectNode auth)) {
      return;
    }
    if (!"http".equals(config.path("transport").asString("stdio"))) {
      throw ProblemException.unprocessable("Sign-in over HTTP only", "A command on this computer gets its keys as "
          + "variables; only a server at a URL signs in.", "/config/auth");
    }
    // The client secret of the person's OAuth app is a secret of the server, given beside the config.
    auth.remove("clientSecret");
    String type = auth.path("type").asString(null);
    String way = switch (type == null ? "" : type) {
      case "oauth" -> "The OAuth sign-in sends";
      case "google" -> "Google Cloud credentials send";
      case "aws" -> "AWS credentials send";
      default -> null;
    };
    if (way != null) {
      for (JsonNode h : config.path("headers")) {
        if ("authorization".equalsIgnoreCase(h.path("name").asString(""))) {
          throw ProblemException.unprocessable("Two sign-ins", way + " the Authorization header. Remove the "
              + "Authorization header, or sign in another way.", "/config/headers");
        }
      }
    }
    if ("google".equals(type) || "aws".equals(type)) {
      cloudCredentials(type, auth, config, key);
      return;
    }
    if (auth.path("credentials") instanceof ObjectNode credentials && onlyDefaults(credentials, credentialMembers)) {
      auth.remove("credentials");
    }
    if (auth.has("credentials") || auth.has("service") || auth.has("region")) {
      throw ProblemException.unprocessable("Credentials for a cloud sign-in", "Cloud credentials, a service and a "
          + "region belong to a server that signs in with Google Cloud or AWS credentials.", "/config/auth");
    }
  }

  /** Whether every member of {@code value} is its schema's {@code default} (an empty object too). */
  private static boolean onlyDefaults(ObjectNode value, JsonNode members) {
    for (Map.Entry<String, JsonNode> e : value.properties()) {
      JsonNode d = members.path(e.getKey()).path("default");
      if (d.isMissingNode() || !d.equals(e.getValue())) {
        return false;
      }
    }
    return true;
  }

  /** The schemas of the members of {@code auth.credentials}, wherever a branch of the type's schema declares them. */
  static JsonNode credentialMembers(JsonNode schema) {
    if (!schema.isObject()) {
      return MissingNode.getInstance();
    }
    JsonNode members = schema.path("properties").path("auth").path("properties").path("credentials")
        .path("properties");
    if (members.isObject()) {
      return members;
    }
    List<JsonNode> parts = new ArrayList<>();
    for (String k : List.of("allOf", "anyOf", "oneOf")) {
      schema.path(k).forEach(parts::add);
    }
    parts.add(schema.path("then"));
    parts.add(schema.path("else"));
    for (JsonNode part : parts) {
      JsonNode found = credentialMembers(part);
      if (found.isObject()) {
        return found;
      }
    }
    return MissingNode.getInstance();
  }

  /**
   * Checks the cloud credentials of a server that signs in with them (§7.6): where they come from, the key that is a
   * secret of the server ({@link #SERVICE_ACCOUNT_KEY}, {@link #SECRET_ACCESS_KEY}) and, for AWS, the service and
   * region a request is signed for. The key is given beside the config, never in it.
   */
  private static void cloudCredentials(String type, ObjectNode auth, ObjectNode config, Consumer<String> key) {
    boolean google = "google".equals(type);
    if (!(auth.path("credentials") instanceof ObjectNode credentials)) {
      throw ProblemException.unprocessable("Credentials missing", "Choose where GateRook takes the "
          + (google ? "Google Cloud" : "AWS") + " credentials from.", "/config/auth/credentials");
    }
    credentials.remove(google ? "serviceAccountKey" : "secretAccessKey");
    String source = credentials.path("source").asString(google ? "adc" : "profile");
    if (google ? !List.of("adc", "serviceAccountKey").contains(source) : !List.of("profile", "accessKey")
        .contains(source)) {
      throw ProblemException.unprocessable("Check the credentials", source + " isn't where " + (google
          ? "Google Cloud credentials come from: use Google sign-in on this computer or a service account key."
          : "AWS credentials come from: use a profile on this computer or an access key."), "/config/auth/credentials");
    }
    credentials.put("source", source);
    if (source.equals(google ? "serviceAccountKey" : "accessKey")) {
      key.accept(google ? SERVICE_ACCOUNT_KEY : SECRET_ACCESS_KEY);
    }
    try {
      if (google) {
        ai.msrv.gaterook.connectors.cloudauth.GcpSpec.fromConfig(config, "/auth/credentials");
      } else {
        java.net.URI url = httpUrl(config);
        AwsSigV4.Target target = AwsSigV4.target(auth.path("service").asString(null),
            auth.path("region").asString(null), url.getHost()).orElseThrow(() -> ProblemException.unprocessable(
                "Service and region missing", url.getHost() + " doesn't name an AWS service and region. Enter them "
                    + "in Sign-in options, such as aws-mcp and us-east-1.", "/config/auth/service"));
        ai.msrv.gaterook.connectors.cloudauth.AwsSpec.fromConfig(config, "/auth/credentials", target.region());
      }
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Check the credentials", e.getMessage(), "/config/auth/credentials");
    }
  }

  /**
   * The prefix of a server the person didn't give one: from its name ({@code github-mcp} → {@code github_}), else its
   * whole name when that one is taken ({@code aws-mcp} → {@code awsmcp_}: {@code aws_} is GateRook's own family), else
   * that with a digit. The first one when every candidate is taken: adding then says by whom.
   *
   * @param taken whether a prefix is a built-in family, a plugin's namespace or another server's prefix
   */
  static String freePrefix(String name, java.util.function.Predicate<String> taken) {
    String first = defaultPrefix(name);
    String whole = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    String base = whole.isEmpty() || !Character.isLetter(whole.charAt(0)) ? first.substring(0, first.length() - 1)
        : whole.length() > 15 ? whole.substring(0, 15) : whole;
    List<String> candidates = new ArrayList<>(List.of(first, base + "_"));
    for (int i = 2; i <= 9; i++) {
      candidates.add((base.length() > 14 ? base.substring(0, 14) : base) + i + "_");
    }
    for (String c : candidates) {
      if (PREFIX.matcher(c).matches() && !taken.test(c)) {
        return c;
      }
    }
    return first;
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

  /** Starts the draft once with the typed values (and its sign-in, if any) and stops it again. */
  private McpServerRuntime.Handshake probe(Draft d, Map<String, SecretInput> given,
      McpServerRuntime.Authorization signIn) {
    Taint taint = new Taint();
    if (signIn != null) {
      signIn.secrets().forEach(taint::add);
    }
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
      return requireRuntime().probe(cfg, resolver, ctx, signIn);
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

  private Started store(Draft d, McpServerRuntime.Handshake h, Map<String, SecretInput> given, String reason,
      McpServerSignIns.DraftSignIn signIn) {
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
      if (signIn != null) {
        // The tokens of the person's sign-in become the server's encrypted secret, renewed in place from now on.
        signIns.store(created.id(), d.cfg().name(), signIn.bundle());
      }
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MCP_SERVER,
          "MCP server " + d.cfg().name() + " added", null, h.tools().size() + " tools · " + version(h)
              + (d.catalogEntry() == null ? "" : " · " + d.catalogEntry())
              + (signIn == null ? "" : " · signed in with " + signInHost(signIn.issuer())), reason, d.cfg().env(),
          null, d.cfg().name(), details(h)));
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
   * A historical synthetic server of the demo world (GW_DEMO=1: {@code github-mcp}): its
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
      return store(d, handshake, values, reason, null);
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

  /**
   * {@code PUT /api/connections/{name}} of a server (Connections › Edit): its sign-in checked as adding checks it, with
   * its stored secrets in place of the values given there, then saved as any connection is. Its tools stay published
   * under the prefix it was added with, and only the catalog names the entry it came from.
   */
  public ConnectionService.Summary update(String name, Env env, JsonNode config, long version, String reason,
      String confirm) {
    ConnectionConfigs.Stored c = configs.byName(name).filter(x -> TYPE.equals(x.config().type()))
        .orElseThrow(() -> notFound(name));
    if (!c.verified() || !(config instanceof ObjectNode request)) {
      // Refused there as any connection's would be: changed outside GateRook, or no settings.
      return connections.update(name, env, config, version, reason, confirm);
    }
    String prefix = stored(name).snapshot().toolPrefix();
    ObjectNode next = request.deepCopy();
    if (next.hasNonNull("toolPrefix") && !prefix.equals(next.path("toolPrefix").asString(null))) {
      throw ProblemException.unprocessable("Prefix can't change", "Its tools are published under " + prefix + ", and "
          + "endpoints and agents know them by it. To use another prefix, add the server again.", "/config/toolPrefix");
    }
    next.put("toolPrefix", prefix);
    next.remove("catalogEntry");
    JsonNode entry = c.config().config().path("catalogEntry");
    if (entry.isString()) {
      next.put("catalogEntry", entry.asString());
    }
    signIn(next, credentialMembers(registry.requireManifest(TYPE).configSchema()), key -> {
      if (!c.secretIds().containsKey(key)) {
        // Said at the source, where the person changes it: Edit replaces a stored key but has none to add.
        boolean google = SERVICE_ACCOUNT_KEY.equals(key);
        throw ProblemException.unprocessable("Secret missing", name + " has no " + (google ? "service account key"
            : "secret access key") + " stored: GateRook takes it when a server is added. Use " + (google
            ? "Google sign-in on this computer" : "an AWS profile on this computer") + ", or add the server again "
            + "with its key.", "/config/auth/credentials/source");
      }
    });
    return connections.update(name, env, next, version, reason, confirm);
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

  private static String signInHost(String issuer) {
    try {
      String host = java.net.URI.create(issuer).getHost();
      return host == null ? issuer : host;
    } catch (IllegalArgumentException e) {
      return issuer;
    }
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
