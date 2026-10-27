package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TemplateKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Templates of kind {@code ssh-runbook} (ARCHITECTURE.md §3): the core stores them, versions them and binds them to
 * one SSH connection ("Runs on"); a runbook has no tool of its own but contributes to {@code ssh_run} of that
 * connection. Compiling is {@link RunbookCompiler}; the preview is the exact command line, with no I/O.
 */
final class RunbookKind implements TemplateKind {

  static final String KIND = "ssh-runbook";

  private final JsonNode schema;

  RunbookKind(JsonNode schema) {
    this.schema = schema;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public List<String> connectionTypes() {
    return List.of(SshConnector.TYPE);
  }

  @Override
  public JsonNode definitionSchema() {
    return schema;
  }

  @Override
  public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
    RunbookCompiler.Outcome o = RunbookCompiler.compile(definition);
    if (!o.ok()) {
      return new CompileResult(List.of(), List.of(), o.errors(), o.warnings(), null);
    }
    ObjectNode contribution = o.runbook().toJson();
    return new CompileResult(List.of(), List.of(contribution), List.of(), o.warnings(), sha256(contribution));
  }

  /** {@code arguments}: the runbook's parameters, flat or under {@code params} as {@code ssh_run} takes them. */
  @Override
  public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
    RunbookCompiler.Outcome o = RunbookCompiler.compile(definition);
    String target = runsOn.name();
    SshSettings settings = null;
    try {
      settings = SshSettings.of(runsOn);
      target = runsOn.name() + " · " + settings.targetLabel();
    } catch (ConnectorException ignored) {
      // A preview of a template whose connection is incomplete still shows the command.
    }
    if (!o.ok()) {
      return ActionPreview.of(ActionPreview.Kind.RUNBOOK, "sh", "", target, "runbook").withWarnings(o.errors());
    }
    Runbook rb = o.runbook();
    JsonNode params = arguments != null && arguments.has("params") ? arguments.get("params") : arguments;
    if (params != null && params.isObject() && arguments.has("runbook") && !arguments.has("params")) {
      ObjectNode flat = (ObjectNode) params.deepCopy();
      flat.remove("runbook");
      flat.remove("note");
      params = flat;
    }
    try {
      Map<String, JsonNode> values = rb.values(params);
      boolean remote = settings != null && settings.advanced().remoteDeadline();
      String wire = rb.render(values, remote, SshActions.remoteSeconds(rb));
      return SshActions.runbookPreview(rb, values, wire, target, runsOn, settings).withWarnings(o.warnings());
    } catch (Runbook.InvalidParams e) {
      return ActionPreview.of(ActionPreview.Kind.RUNBOOK, "sh", "", target, "runbook " + rb.name())
          .withWarnings(List.of(e.getMessage()));
    }
  }

  private static String sha256(JsonNode node) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(node.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
