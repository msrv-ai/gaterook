package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ManifestCodec;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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
 */
@Component
public class ToolCatalog {

  /** One published tool and the pairs behind it. */
  public record PublishedTool(String name, String title, String description, ObjectNode inputSchema,
      JsonNode outputSchema, ToolDefinition.Annotations annotations, List<EndpointSnapshot.Pair> pairs,
      String declaredBy, boolean core) {}

  /**
   * The list of one endpoint.
   *
   * @param problems tools left out, and why (a plugin's tool that clashes with a built-in one)
   */
  public record Catalog(List<PublishedTool> tools, String listHash, List<String> problems) {

    public Optional<PublishedTool> tool(String name) {
      return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }
  }

  private final PolicyEngine engine;

  public ToolCatalog(PolicyEngine engine) {
    this.engine = engine;
  }

  public Catalog forEndpoint(EndpointSnapshot snapshot) {
    List<PublishedTool> out = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    if (!snapshot.blocked()) {
      Map<String, List<EndpointSnapshot.Pair>> byName = new LinkedHashMap<>();
      for (EndpointSnapshot.Pair p : snapshot.onPairs()) {
        byName.computeIfAbsent(p.tool().name(), k -> new ArrayList<>()).add(p);
      }
      for (Map.Entry<String, List<EndpointSnapshot.Pair>> e : byName.entrySet()) {
        publish(snapshot, e.getKey(), e.getValue(), problems).ifPresent(out::add);
      }
      for (String core : CoreTools.WITH_MODE) {
        Mode mode = snapshot.coreTool(core).map(EndpointSnapshot.CorePair::mode).orElse(Mode.OFF);
        if (mode != Mode.OFF) {
          out.add(coreTool(snapshot, CoreTools.withMode(core)));
        }
      }
    }
    out.add(coreTool(snapshot, CoreTools.approvalStatus()));
    out.sort(java.util.Comparator.comparing(PublishedTool::name));
    return new Catalog(List.copyOf(out), hash(out), List.copyOf(problems));
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
   * name. A connector's tool of the same name still publishes.
   */
  private Optional<PublishedTool> publish(EndpointSnapshot snapshot, String name, List<EndpointSnapshot.Pair> pairs,
      List<String> problems) {
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
    return Optional.of(new PublishedTool(name, first.title(), description(first, kept, template, approval), input,
        output == null ? null : output.deepCopy(), first.annotations(), List.copyOf(kept), declaredBy, false));
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
    return a.compareTo(b) <= 0 ? a : b;
  }

  private static String seconds(Duration d) {
    long ms = d.toMillis();
    return ms % 1000 == 0 ? (ms / 1000) + " s" : String.format(Locale.US, "%.1f s", ms / 1000.0);
  }

  private static PublishedTool coreTool(EndpointSnapshot snapshot, ToolDefinition t) {
    ObjectNode input = (ObjectNode) t.inputSchema().deepCopy();
    if (input.path("properties").path("connection").isObject()) {
      ArrayNode values = ((ObjectNode) input.path("properties").path("connection")).putArray("enum");
      new TreeSet<>(snapshot.connections().stream().filter(EndpointSnapshot.Wired::usable)
          .map(w -> w.config().name()).toList()).forEach(values::add);
    }
    return new PublishedTool(t.name(), t.title(), t.description(), input, null, t.annotations(), List.of(), null,
        true);
  }

  private static String hash(List<PublishedTool> tools) {
    ArrayNode list = JsonNodeFactory.instance.arrayNode();
    for (PublishedTool t : tools) {
      ObjectNode n = list.addObject();
      n.put("name", t.name());
      n.put("title", t.title());
      n.put("description", t.description());
      n.set("inputSchema", t.inputSchema());
      n.set("outputSchema", t.outputSchema());
      n.set("annotations", ManifestCodec.writeTool(ToolDefinition.builder(t.name()).annotations(t.annotations())
          .build()).get("annotations"));
    }
    return CanonicalJson.sha256Hex(list);
  }
}
