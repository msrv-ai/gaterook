package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two tools of SSH (report 06 §3, kit endpoint-detail). Neither publishes an outputSchema: both may answer
 * with an approval handle. {@code ssh_run} is MIXED: classify() decides per runbook (read only or not).
 */
final class SshTools {

  static final String RUN = "ssh_run";
  static final String EXEC = "ssh_exec";

  private SshTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        // LIMITED egress: fixed commands with enum/int/bool parameters; RiskAnalyzer raises it to CHANNEL per endpoint.
        ToolDefinition.builder(RUN)
            .title("Run a runbook")
            .description("Run a saved runbook with typed parameters, such as compose-ps.")
            .inputSchema(schemas.load("ssh_run.input"))
            .annotations(Annotations.write(false))
            .nature(Nature.MIXED)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.TEXT)
            .risk(new Risk(true, true, Egress.LIMITED))
            .build(),
        ToolDefinition.builder(EXEC)
            .title("Run a shell command")
            .description("Run any shell command on the host.")
            .inputSchema(schemas.load("ssh_exec.input"))
            .annotations(Annotations.write(true))
            .nature(Nature.MUTATION)
            .defaultMode(Mode.OFF, Mode.OFF)
            .resultShape(ResultShape.TEXT)
            .risk(new Risk(true, true, Egress.CHANNEL))
            .build());
  }

  /**
   * {@code ssh_run} as one endpoint publishes it (report 06 §3.1): {@code runbook} is an enum of every runbook of the
   * endpoint's SSH connections, an if/then per connection narrows it to that connection's runbooks and gives each
   * runbook its {@code params} schema, and the description repeats the signatures, which clients that ignore if/then
   * still read. {@code ssh_exec} is the manifest's.
   */
  static List<ToolDefinition> forEndpoint(List<ToolDefinition> manifest, EndpointView view, Runbooks runbooks) {
    ToolDefinition run = manifest.get(0);
    ObjectNode input = (ObjectNode) run.inputSchema().deepCopy();
    ArrayNode allOf = JsonNodeFactory.instance.arrayNode();
    TreeSet<String> all = new TreeSet<>();
    StringBuilder description = new StringBuilder("Run a saved runbook with typed parameters.");
    List<ConnectionConfig> connections = new ArrayList<>(view.connections());
    connections.sort(Comparator.comparing(ConnectionConfig::name));
    for (ConnectionConfig cfg : connections) {
      List<Runbooks.Entry> own = runbooks.of(cfg.id()).stream().filter(e -> !e.ambiguous()).toList();
      ObjectNode rule = allOf.addObject();
      ObjectNode when = rule.putObject("if");
      when.putObject("properties").putObject("connection").put("const", cfg.name());
      when.putArray("required").add("connection");
      ObjectNode runbookHere = rule.putObject("then").putObject("properties").putObject("runbook");
      List<String> signatures = new ArrayList<>();
      if (own.isEmpty()) {
        runbookHere.set("not", JsonNodeFactory.instance.objectNode()); // an enum may not be empty
        signatures.add("no runbooks yet");
      } else {
        ArrayNode names = runbookHere.putArray("enum");
        for (Runbooks.Entry e : own) {
          Runbook rb = e.runbook();
          all.add(rb.name());
          names.add(rb.name());
          signatures.add(rb.signature() + " — " + (rb.readOnly() ? "read only" : "needs approval"));
          ObjectNode per = allOf.addObject();
          ObjectNode cond = per.putObject("if");
          ObjectNode props = cond.putObject("properties");
          props.putObject("connection").put("const", cfg.name());
          props.putObject("runbook").put("const", rb.name());
          cond.putArray("required").add("connection").add("runbook");
          ObjectNode then = per.putObject("then");
          then.putObject("properties").set("params", rb.paramsSchema());
          if (rb.hasRequiredParams()) {
            then.putArray("required").add("params");
          }
        }
      }
      description.append(' ').append(cfg.name()).append(": ").append(String.join("; ", signatures)).append('.');
    }
    if (!all.isEmpty()) {
      ArrayNode e = ((ObjectNode) input.get("properties").get("runbook")).putArray("enum");
      all.forEach(e::add);
    }
    if (!allOf.isEmpty()) {
      input.set("allOf", allOf);
    }
    long seconds = Long.MAX_VALUE;
    long approvedSeconds = Long.MAX_VALUE;
    long outputKb = Long.MAX_VALUE;
    for (Limits l : view.limitsByConnection().values()) {
      seconds = Math.min(seconds, SshConnector.commandBudget(l, false).toSeconds());
      approvedSeconds = Math.min(approvedSeconds, SshConnector.commandBudget(l, true).toSeconds());
      outputKb = Math.min(outputKb, l.maxOutputBytes() / 1024);
    }
    if (seconds != Long.MAX_VALUE) {
      description.append(" A run stops after ").append(seconds).append(" s");
      if (approvedSeconds > seconds) {
        // Past what a client waits for an answer: only a run the gateway makes after a person approved it.
        description.append(" (an approved run after ").append(approvedSeconds).append(" s)");
      }
      description.append("; output is cut at ").append(outputKb).append(" KB.");
    }
    ToolDefinition endpointRun = ToolDefinition.builder(run.name()).title(run.title())
        .description(description.toString()).inputSchema(input).annotations(run.annotations()).nature(run.nature())
        .defaultMode(run.defaultMode(), run.prodDefaultMode()).resultShape(run.resultShape()).risk(run.risk())
        .build();
    return List.of(endpointRun, manifest.get(1));
  }
}
