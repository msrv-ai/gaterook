package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ManifestCodec;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools an endpoint publishes ({@code tools/list}, ARCHITECTURE.md §5 step 4): every tool with at least one pair
 * that is not Off; its {@code connection} enum lists the connections of those pairs; same-named tools of two
 * connector types (PostgreSQL's and MySQL's {@code sql_query}) are one tool whose enum is the union, dispatched by the
 * type of the connection chosen. {@code outputSchema} is left out when any pair of the tool waits for approval: a
 * pending handle doesn't match it, and clients validate {@code structuredContent}. Order is by name; the list's hash
 * changes with every mode change.
 *
 * <p>MCP clients get each schema without that enum, or any other that follows the endpoint's connections
 * ({@link PublishedTool#clientInputSchema}); the description names the values instead. A client that keeps the list
 * it loaded first, and checks arguments against it, still accepts a connection added later: Claude Desktop's Code and
 * Cowork sessions keep theirs for as long as the extension runs. A call is checked against the endpoint as it is then.
 *
 * <p>A long list is searched instead of listed (§5.7, {@code settings.toolList}): {@code tools/list} then shows the
 * gateway's own tools, {@code find_tools}, {@code call_tool} and {@code call_read_tool}, and the tools of the
 * connections the person pins; every published tool stays callable by its name. The catalog of a snapshot is built
 * once: a snapshot is immutable and replaced on every change that could change its catalog (endpoint, connection,
 * policy, a temporary bypass's end), so every request on it reuses the same catalog. Nothing may change its trees.
 */
@Component
public class ToolCatalog {

  /** {@code auto} lists every tool up to this many, and up to {@link #SEARCH_ABOVE_BYTES} of definitions. */
  public static final int SEARCH_ABOVE_TOOLS = 40;

  /** About 25,000 tokens: what an AI app that loads every definition then puts in the model's context. */
  public static final long SEARCH_ABOVE_BYTES = 100 * 1024;

  public static final String ALL = "all";
  public static final String SEARCH = "search";
  public static final String AUTO = "auto";

  /**
   * One published tool and the pairs behind it.
   *
   * @param inputSchema       every value the endpoint allows now: {@code connection} lists the connections of the
   *                          pairs as its enum, and the Playground offers them
   * @param clientInputSchema what MCP clients get: no enum that follows the endpoint's connections
   * @param meta              the tool's {@code _meta} as its connector gives it (an MCP server's app: {@code ui}), or
   *                          {@code null}
   * @param appCallable       whether an MCP App the endpoint shows may call it: the tool belongs to a connection
   *                          whose app the endpoint shows ({@link #appConnections}), as MCP Apps lets a server's app
   *                          call that server's tools
   */
  public record PublishedTool(String name, String title, String description, ObjectNode inputSchema,
      ObjectNode clientInputSchema, JsonNode outputSchema, ToolDefinition.Annotations annotations,
      List<EndpointSnapshot.Pair> pairs, String declaredBy, boolean core, JsonNode meta, boolean appCallable) {

    public PublishedTool(String name, String title, String description, ObjectNode inputSchema,
        ObjectNode clientInputSchema, JsonNode outputSchema, ToolDefinition.Annotations annotations,
        List<EndpointSnapshot.Pair> pairs, String declaredBy, boolean core) {
      this(name, title, description, inputSchema, clientInputSchema, outputSchema, annotations, pairs, declaredBy,
          core, null, false);
    }

    /** Whether only an MCP App may call it ({@code ui.visibility: ["app"]}): a client without apps doesn't get it. */
    public boolean appOnly() {
      return keepsFromModel(meta);
    }

    /**
     * The {@code _meta.ui} an AI app that renders MCP Apps gets for this tool (not for the gateway's own tools, whose
     * cards {@code McpDispatcher} names), or {@code null} for none. To an AI app the endpoint is one MCP server, and
     * MCP Apps lets every app a server shows call that server's tools unless a tool's {@code visibility} leaves out
     * {@code "app"}. So the tool of a connection whose app the endpoint shows keeps what its server declared (its app,
     * and who may call it), and every other tool says {@code visibility: ["model"]}: the AI app refuses an app's call
     * of it. A tool that only an app may call and that no app of its own connection can reach is not published at all
     * ({@link ToolCatalog#forEndpoint}). An app URI outside the tool's own connection's namespace, which no read
     * reaches, is left out.
     */
    public ObjectNode clientUi() {
      ObjectNode ui = meta != null && meta.path("ui") instanceof ObjectNode own ? own.deepCopy()
          : JsonNodeFactory.instance.objectNode();
      if (pairs.stream().noneMatch(p -> appUri(p) != null)) {
        ui.remove("resourceUri");
      }
      if (!appCallable) {
        ui.putArray("visibility").add("model");
      }
      return ui.isEmpty() ? null : ui;
    }
  }

  /** Whether a tool's {@code _meta} keeps it from the model ({@code ui.visibility} without {@code "model"}). */
  private static boolean keepsFromModel(JsonNode meta) {
    JsonNode visibility = meta == null ? null : meta.path("ui").path("visibility");
    if (visibility == null || !visibility.isArray() || visibility.isEmpty()) {
      return false;
    }
    for (JsonNode v : visibility) {
      if ("model".equals(v.asString(""))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The app a pair's tool shows: the {@code _meta.ui.resourceUri} of an external MCP server's tool, published under
   * that tool's own connection's app namespace ({@code ui://gaterook/connections/<name>/…}). {@code null} for a tool
   * that shows none, or names a URI outside its connection's namespace, which no read reaches.
   */
  public static String appUri(EndpointSnapshot.Pair p) {
    JsonNode meta = p.tool().meta();
    String uri = meta == null ? null : meta.path("ui").path("resourceUri").asString(null);
    String own = ResourceDescriptor.appPrefix(p.connectionName());
    return uri != null && uri.startsWith(own) && uri.length() > own.length() ? uri : null;
  }

  /**
   * The connections whose app the endpoint shows: those with a pair that is on and shows one ({@link #appUri}). Only
   * their tools may be called by an app (ARCHITECTURE.md §5.4).
   */
  public static Set<String> appConnections(EndpointSnapshot snapshot) {
    Set<String> out = new HashSet<>();
    if (snapshot.blocked()) {
      return out;
    }
    for (EndpointSnapshot.Pair p : snapshot.onPairs()) {
      if (appUri(p) != null) {
        out.add(p.connectionName());
      }
    }
    return out;
  }

  /**
   * How {@code tools/list} shows the tools ({@code settings.toolList}, §5.7).
   *
   * @param mode   {@link #ALL}, {@link #SEARCH} or {@link #AUTO}, as the person set it ({@code auto} unless set)
   * @param search whether tools/list shows only {@code listed}, and {@code find_tools} finds the rest
   * @param pinned the connections whose tools are listed in search
   * @param listed the names tools/list shows (before what a client without MCP Apps doesn't get)
   * @param tools  how many tools the model may call ({@link ToolSearch#searchable})
   * @param bytes  their names, titles, descriptions and input schemas as clients get them, in UTF-8 bytes: what an AI
   *               app that loads every definition puts in the model's context
   */
  public record ToolList(String mode, boolean search, List<String> pinned, Set<String> listed, int tools,
      long bytes) {}

  /**
   * The list of one endpoint: every tool it publishes, callable by its name, and which of them {@code tools/list}
   * shows.
   */
  public static final class Catalog {

    private final List<PublishedTool> tools;
    private final String listHash;
    private final List<String> problems;
    private final ToolList list;
    private final Map<String, PublishedTool> byName = new HashMap<>();
    private volatile ToolSearch search;

    Catalog(List<PublishedTool> tools, String listHash, List<String> problems, ToolList list) {
      this.tools = tools;
      this.listHash = listHash;
      this.problems = problems;
      this.list = list;
      tools.forEach(t -> byName.putIfAbsent(t.name(), t));
    }

    /** By name. */
    public List<PublishedTool> tools() {
      return tools;
    }

    /** Over what tools/list shows: it changes with every change clients would see in it. */
    public String listHash() {
      return listHash;
    }

    /** Tools left out, and why (a plugin's tool that clashes with a built-in one). */
    public List<String> problems() {
      return problems;
    }

    public ToolList list() {
      return list;
    }

    public Optional<PublishedTool> tool(String name) {
      return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    /** Whether tools/list shows it. */
    public boolean listed(String name) {
      return list.listed().contains(name);
    }

    /** The search of its tools, built on first use. */
    public ToolSearch search() {
      ToolSearch s = search;
      if (s == null) {
        synchronized (this) {
          s = search;
          if (s == null) {
            s = ToolSearch.of(tools);
            search = s;
          }
        }
      }
      return s;
    }
  }

  private record Memo(EndpointSnapshot snapshot, Catalog catalog) {}

  private final PolicyEngine engine;
  private final Map<String, Memo> memo = new ConcurrentHashMap<>();

  public ToolCatalog(PolicyEngine engine) {
    this.engine = engine;
  }

  /** The catalog of {@code snapshot}: built once for each snapshot (by identity), then reused. */
  public Catalog forEndpoint(EndpointSnapshot snapshot) {
    String id = snapshot.id();
    Memo m = id == null ? null : memo.get(id);
    if (m != null && m.snapshot() == snapshot) {
      return m.catalog();
    }
    Catalog built = build(snapshot);
    if (id != null) {
      memo.put(id, new Memo(snapshot, built));
    }
    return built;
  }

  /** A replaced snapshot's catalog is never asked for again; a request that still holds it builds its own. */
  @EventListener
  public void onCatalogInvalidated(EndpointCatalogInvalidated e) {
    if (e.endpointId() == null) {
      memo.clear();
    } else {
      memo.remove(e.endpointId());
    }
  }

  private Catalog build(EndpointSnapshot snapshot) {
    List<PublishedTool> out = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    JsonNode setting = snapshot.settings() == null ? null : snapshot.settings().path("toolList");
    String listMode = mode(setting);
    List<String> pinned = pinned(setting);
    boolean search = false;
    int count = 0;
    long bytes = 0;
    if (!snapshot.blocked()) {
      boolean listed = snapshot.coreTool(CoreTools.LIST_CONNECTIONS).map(EndpointSnapshot.CorePair::mode)
          .orElse(Mode.OFF) != Mode.OFF;
      Set<String> apps = appConnections(snapshot);
      Map<String, List<EndpointSnapshot.Pair>> byName = new LinkedHashMap<>();
      for (EndpointSnapshot.Pair p : snapshot.onPairs()) {
        byName.computeIfAbsent(p.tool().name(), k -> new ArrayList<>()).add(p);
      }
      for (Map.Entry<String, List<EndpointSnapshot.Pair>> e : byName.entrySet()) {
        publish(snapshot, e.getKey(), e.getValue(), listed, apps, problems).ifPresent(out::add);
      }
      for (String core : CoreTools.WITH_MODE) {
        Mode mode = snapshot.coreTool(core).map(EndpointSnapshot.CorePair::mode).orElse(Mode.OFF);
        if (mode != Mode.OFF) {
          out.add(coreTool(snapshot, CoreTools.withMode(core), listed));
        }
      }
      out.add(coreTool(snapshot, CoreTools.approvalCard(), false));
      out.add(coreTool(snapshot, CoreTools.approvalCardAction(), false));
      out.add(coreTool(snapshot, CoreTools.connectionImportSchema(), false));
      out.add(coreTool(snapshot, CoreTools.connectionImportProgress(), false));
      out.add(coreTool(snapshot, CoreTools.submitConnectionImport(), false));
      out.add(coreTool(snapshot, CoreTools.openConnectionImport(), false));
      for (PublishedTool t : out) {
        if (ToolSearch.searchable(t)) {
          count++;
          bytes += modelBytes(t);
        }
      }
      search = count > 0 && switch (listMode) {
        case ALL -> false;
        case SEARCH -> true;
        default -> count > SEARCH_ABOVE_TOOLS || bytes > SEARCH_ABOVE_BYTES;
      };
      if (search) {
        out.add(coreTool(snapshot, CoreTools.findTools(), false));
        out.add(coreTool(snapshot, CoreTools.callTool(), false));
        out.add(coreTool(snapshot, CoreTools.callReadTool(), false));
      }
    }
    out.add(coreTool(snapshot, CoreTools.approvalStatus(), false));
    out.sort(java.util.Comparator.comparing(PublishedTool::name));
    Set<String> listed = new LinkedHashSet<>();
    List<PublishedTool> shown = new ArrayList<>();
    for (PublishedTool t : out) {
      if (!search || t.core() || t.pairs().stream().anyMatch(p -> pinned.contains(p.connectionName()))) {
        listed.add(t.name());
        shown.add(t);
      }
    }
    return new Catalog(List.copyOf(out), search ? hash(shown) + ":" + SEARCH : hash(shown), List.copyOf(problems),
        new ToolList(listMode, search, pinned, Set.copyOf(listed), count, bytes));
  }

  /** {@code settings.toolList.mode}; {@code auto} when unset or unknown. */
  static String mode(JsonNode setting) {
    String mode = setting == null ? AUTO : setting.path("mode").asString(AUTO);
    return ALL.equals(mode) || SEARCH.equals(mode) ? mode : AUTO;
  }

  /** {@code settings.toolList.pinned}: names of connections. */
  static List<String> pinned(JsonNode setting) {
    List<String> out = new ArrayList<>();
    if (setting != null) {
      for (JsonNode n : setting.path("pinned")) {
        if (n.isString() && !out.contains(n.asString())) {
          out.add(n.asString());
        }
      }
    }
    return List.copyOf(out);
  }

  /** What the model reads of a tool: its name, title, description and input schema. */
  private static long modelBytes(PublishedTool t) {
    long n = utf8(t.name()) + utf8(t.title()) + utf8(t.description());
    return n + (t.clientInputSchema() == null ? 0 : CanonicalJson.bytes(t.clientInputSchema()).length);
  }

  private static long utf8(String s) {
    return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
  }

  /** The pair a call names: the tool on the chosen connection (or the template tool's own connection). */
  public Optional<EndpointSnapshot.Pair> resolve(Catalog catalog, String tool, String connection) {
    Optional<PublishedTool> published = catalog.tool(tool);
    if (published.isEmpty() || published.get().core()) {
      return Optional.empty();
    }
    List<EndpointSnapshot.Pair> pairs = published.get().pairs();
    // A template's tool and an MCP server's tool run on their own connection and take no connection argument.
    if (pairs.size() == 1 && (pairs.get(0).template() != null || !takesConnection(pairs.get(0).tool()))) {
      return Optional.of(pairs.get(0));
    }
    return pairs.stream().filter(p -> p.connectionName().equals(connection)).findFirst();
  }

  /** Whether a tool's input names the connection it runs on (every built-in tool does, an MCP server's never). */
  private static boolean takesConnection(ToolDefinition t) {
    return t.inputSchema() != null && t.inputSchema().path("properties").path("connection").isObject();
  }

  /**
   * One name of the list, or nothing when no pair of that name can be published. A template's tool runs on its own
   * connection and has no {@code connection} parameter, so a template pair that shares its name with any other pair
   * would make a call ambiguous: it is left out, and so are both templates when two connections each have one of that
   * name. A connector's tool of the same name still publishes. A tool only an MCP App may call is left out when no
   * app of its own connection is shown ({@code apps}): no app the endpoint shows may call it, and the model may not
   * either.
   */
  private Optional<PublishedTool> publish(EndpointSnapshot snapshot, String name, List<EndpointSnapshot.Pair> pairs,
      boolean listed, Set<String> apps, List<String> problems) {
    List<EndpointSnapshot.Pair> candidates = new ArrayList<>();
    for (EndpointSnapshot.Pair p : pairs) {
      if (p.template() != null && pairs.size() > 1) {
        problems.add(name + " of template on " + p.connectionName() + " clashes with another tool of the same name");
      } else {
        candidates.add(p);
      }
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    EndpointSnapshot.Pair firstPair = candidates.get(0);
    ToolDefinition first = firstPair.tool();
    List<EndpointSnapshot.Pair> kept = new ArrayList<>();
    for (EndpointSnapshot.Pair p : candidates) {
      if (!ConnectorRegistry.sameTool(first, p.tool())) {
        problems.add(name + " on " + p.connectionName() + " differs from " + name + " on "
            + firstPair.connectionName() + ": only the first is published");
        continue;
      }
      kept.add(p);
    }
    boolean appCallable = kept.stream().allMatch(p -> apps.contains(p.connectionName()));
    if (!appCallable && keepsFromModel(first.meta())) {
      problems.add(name + " on " + firstPair.connectionName() + " is only for an MCP App, and no tool of "
          + firstPair.connectionName() + " that is on shows one: it is not published");
      return Optional.empty();
    }
    boolean template = kept.size() == 1 && kept.get(0).template() != null;
    ObjectNode input = first.inputSchema() == null ? JsonNodeFactory.instance.objectNode().put("type", "object")
        : (ObjectNode) first.inputSchema().deepCopy();
    TreeSet<String> names = new TreeSet<>();
    kept.forEach(p -> names.add(p.connectionName()));
    if (!template && input.path("properties").path("connection").isObject()) {
      ArrayNode values = ((ObjectNode) input.path("properties").path("connection")).putArray("enum");
      names.forEach(values::add);
    }
    boolean approval = kept.stream().anyMatch(p -> p.mode() == Mode.APPROVAL);
    JsonNode output = approval || first.mutating() ? null : first.outputSchema();
    String declaredBy = kept.stream().map(EndpointSnapshot.Pair::declaredBy).filter(java.util.Objects::nonNull)
        .findFirst().orElse(null);
    ToolDefinition declared = firstPair.manifest() == null ? null : firstPair.manifest().tools().stream()
        .filter(t -> t.name().equals(name)).findFirst().orElse(null);
    ObjectNode client = clientSchema(input, declared, listed
        ? "A connection named in this tool's description, or one added since: list_connections shows the current ones."
        : "A connection named in this tool's description.");
    return Optional.of(new PublishedTool(name, first.title(), description(first, kept, template, approval), input,
        client, output == null ? null : output.deepCopy(), first.annotations(), List.copyOf(kept), declaredBy, false,
        first.meta() == null ? null : first.meta().deepCopy(), appCallable));
  }

  /**
   * {@code input} for MCP clients, without the enums that follow the endpoint's connections, which the description
   * names instead: {@code connection}'s, with {@code hint} as its description unless the tool gives one, and each
   * enum a connector adds for the endpoint where its manifest's tool ({@code declared}) has none, such as an SSH
   * host's runbooks or a GCP connection's scopes. A list loaded earlier then still accepts what was added since.
   */
  private static ObjectNode clientSchema(ObjectNode input, ToolDefinition declared, String hint) {
    ObjectNode out = input.deepCopy();
    if (!(out.path("properties") instanceof ObjectNode properties)) {
      return out;
    }
    JsonNode open = declared == null || declared.inputSchema() == null ? null
        : declared.inputSchema().path("properties");
    for (Map.Entry<String, JsonNode> e : properties.properties()) {
      if (!(e.getValue() instanceof ObjectNode p) || !p.has("enum")) {
        continue;
      }
      if (e.getKey().equals("connection")) {
        p.remove("enum");
        if (!p.has("description")) {
          p.put("description", hint);
        }
      } else if (open != null && !open.path(e.getKey()).has("enum")) {
        p.remove("enum");
      }
    }
    return out;
  }

  private String description(ToolDefinition t, List<EndpointSnapshot.Pair> pairs, boolean template,
      boolean approval) {
    StringBuilder out = new StringBuilder(t.description().strip());
    if (!out.isEmpty() && out.charAt(out.length() - 1) != '.') {
      out.append('.');
    }
    if (template) {
      EndpointSnapshot.Pair p = pairs.get(0);
      out.append(" Runs on ").append(p.connectionName()).append(" (").append(p.config().env().id()).append(").");
    } else {
      out.append(" Connections: ");
      List<String> labels = new ArrayList<>();
      for (EndpointSnapshot.Pair p : pairs) {
        String version = p.config().metadata() == null ? "" : p.config().metadata().path("serverVersion").asString("");
        String kind = version.isBlank() ? p.manifest().name() : version;
        labels.add(p.connectionName() + " (" + kind + ", " + p.config().env().id() + ")");
      }
      // By name, like the connection enum: the text (and the list hash) must not depend on wiring order.
      labels.sort(null);
      out.append(String.join(", ", labels)).append('.');
    }
    Optional<Limits> strictest = strictest(pairs);
    NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.US);
    if (strictest.isPresent()) {
      Limits limits = strictest.get();
      switch (t.resultShape()) {
        case TABLE -> out.append(" Max ").append(numbers.format(limits.maxRows())).append(" rows, ")
            .append(seconds(limits.statementTimeout())).append('.');
        case LOGS -> out.append(" Max ").append(numbers.format(limits.maxLines())).append(" lines.");
        default -> {
          // JSON and text results are cut by size; the agent learns it from "truncated".
        }
      }
    }
    if (approval && !t.mutating() && pairs.stream().allMatch(p -> p.mode() == Mode.APPROVAL)) {
      out.append(" Every call waits until a person approves it in GateRook; you get a handle to poll with "
          + "approval_status.");
    }
    List<String> bypass = pairs.stream().filter(p -> p.mode() == Mode.BYPASS)
        .map(EndpointSnapshot.Pair::connectionName).sorted().toList();
    if (!bypass.isEmpty()) {
      out.append(" Bypass on ").append(String.join(", ", bypass))
          .append(": calls run without approval, including changes; policy limits still apply.");
    }
    return out.toString();
  }

  /** The tightest of every limit over the pairs; empty for no pairs. */
  private Optional<Limits> strictest(List<EndpointSnapshot.Pair> pairs) {
    Limits best = null;
    for (EndpointSnapshot.Pair p : pairs) {
      Limits l = engine.view(p.view(), p.config()).limits();
      if (best == null) {
        best = l;
      } else {
        best = new Limits(Math.min(best.maxRows(), l.maxRows()), Math.min(best.maxLines(), l.maxLines()),
            Math.min(best.maxResponseBytes(), l.maxResponseBytes()),
            Math.min(best.maxRequestBytes(), l.maxRequestBytes()), Math.min(best.maxOutputBytes(), l.maxOutputBytes()),
            Math.min(best.maxStderrBytes(), l.maxStderrBytes()), Math.min(best.maxCellChars(), l.maxCellChars()),
            min(best.statementTimeout(), l.statementTimeout()), min(best.commandTimeout(), l.commandTimeout()),
            min(best.toolTimeout(), l.toolTimeout()), min(best.maxRange(), l.maxRange()));
      }
    }
    return Optional.ofNullable(best);
  }

  private static Duration min(Duration a, Duration b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.compareTo(b) <= 0 ? a : b;
  }

  private static String seconds(Duration d) {
    long ms = d.toMillis();
    return ms % 1000 == 0 ? (ms / 1000) + " s" : String.format(Locale.US, "%.1f s", ms / 1000.0);
  }

  private static PublishedTool coreTool(EndpointSnapshot snapshot, ToolDefinition t, boolean listed) {
    ObjectNode input = (ObjectNode) t.inputSchema().deepCopy();
    if (input.path("properties").path("connection").isObject()) {
      ArrayNode values = ((ObjectNode) input.path("properties").path("connection")).putArray("enum");
      new TreeSet<>(snapshot.connections().stream().filter(EndpointSnapshot.Wired::usable)
          .map(w -> w.config().name()).toList()).forEach(values::add);
    }
    ObjectNode client = clientSchema(input, null, listed ? "A connection that list_connections shows."
        : "The name of a connection on this endpoint.");
    return new PublishedTool(t.name(), t.title(), t.description(), input, client, t.outputSchema(), t.annotations(),
        List.of(), null, true);
  }

  /** Over what clients get: a change that only an enum they never see would show doesn't notify them. */
  private static String hash(List<PublishedTool> tools) {
    ArrayNode list = JsonNodeFactory.instance.arrayNode();
    for (PublishedTool t : tools) {
      ObjectNode n = list.addObject();
      n.put("name", t.name());
      n.put("title", t.title());
      n.put("description", t.description());
      n.set("inputSchema", t.clientInputSchema());
      n.set("outputSchema", t.outputSchema());
      n.set("annotations", ManifestCodec.writeTool(ToolDefinition.builder(t.name()).annotations(t.annotations())
          .build()).get("annotations"));
      if (t.meta() != null) {
        // Only where there is any: the hash of a list without apps stays what it was.
        n.set("meta", t.meta());
      }
    }
    return CanonicalJson.sha256Hex(list);
  }
}
