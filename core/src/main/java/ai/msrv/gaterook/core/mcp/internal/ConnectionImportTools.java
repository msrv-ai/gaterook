package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.endpoints.ConnectionImportService;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Discovery tools may stage data for a person; no MCP operation can commit it or resolve a credential hint. */
@Component
public class ConnectionImportTools {
  private final ConnectionImportService imports;
  private final PolicyEngine engine;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final Calls calls;

  public ConnectionImportTools(ConnectionImportService imports, PolicyEngine engine, ToolGuard guard,
      AuditWriter audit, Calls calls) {
    this.imports = imports;
    this.engine = engine;
    this.guard = guard;
    this.audit = audit;
    this.calls = calls;
  }

  public ObjectNode call(McpRequest request, String name, ObjectNode arguments) {
    PolicyEngine.Rates rates = engine.rates(request.snapshot().env());
    ToolGuard.Admission admission = guard.admit(request.caller().principalId(), name, rates.perToken(), rates.perTool());
    if (!admission.allowed()) return ResultWriter.toolError(request.env(), "Too many discovery calls. Retry later.", null);
    if (engine.unverified(request.snapshot().env(), null) != null) {
      return ResultWriter.toolError(request.env(), "Review the endpoint's policy integrity in GateRook first.", null);
    }
    boolean submit = CoreTools.SUBMIT_CONNECTION_IMPORT.equals(name);
    boolean progress = CoreTools.CONNECTION_IMPORT_PROGRESS.equals(name);
    boolean open = "open_connection_import".equals(name);
    ToolDefinition definition = submit ? CoreTools.submitConnectionImport()
        : open ? CoreTools.openConnectionImport() : progress ? CoreTools.connectionImportProgress()
        : CoreTools.connectionImportSchema();
    String callId = Ids.next("tc_");
    // The capability and the discovery body never enter audit arguments, previews, hashes or tracing tags.
    ObjectNode summary = JsonNodeFactory.instance.objectNode().put("sessionId", arguments.path("sessionId").asString());
    AuditWriter.Call call = calls.call(request, callId, name, name, null, request.snapshot().env(), definition,
        summary, null);
    try { audit.received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, null, null)); }
    catch (AuditUnavailableException e) { throw new McpException(JsonRpc.INTERNAL_ERROR, ToolCallHandler.AUDIT_UNAVAILABLE); }
    long started = System.nanoTime();
    ToolResult result;
    try {
      ObjectNode data = open ? imports.open(arguments.path("sessionId").asString(), request.snapshot().id(),
              request.caller().principalId()) : submit
          ? imports.submit(arguments.path("sessionId").asString(), arguments.path("submissionToken").asString(),
              request.snapshot().id(), request.caller().principalId(), arguments.get("manifest"))
          : progress ? imports.progress(arguments.path("sessionId").asString(), arguments.path("submissionToken").asString(),
              request.snapshot().id(), arguments.path("message").asString(null))
          : imports.schema(arguments.path("sessionId").asString(), arguments.path("submissionToken").asString(),
              request.snapshot().id(), arguments.path("type").asString(null));
      // Some clients expose only text content to their model, so it must contain the complete schema too.
      result = submit || open ? ConnectionImportCards.result(data)
          : ToolResult.json(data, CanonicalJson.text(data));
    } catch (ProblemException e) { result = ToolResult.error(e.getMessage()); }
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    String preview = result.isError() ? "Discovery request refused." : submit ? "Discovery staged for review."
        : open ? "Import review opened." : progress ? "Import progress updated." : "Connector schemas returned.";
    try {
      audit.completed(call, new AuditWriter.Completion(result.isError() ? AuditWriter.Verdict.ERROR : AuditWriter.Verdict.ALLOWED,
          null, null, null, null, elapsed, elapsed, 0L, null, null, null, preview, 0, false,
          result.isError() ? "IMPORT_REFUSED" : null, result.isError() ? preview : null, null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit could not record discovery. Retry the same request later.");
    }
    return ResultWriter.callToolResult(request.env(), result, callId, request.span().traceId(), null);
  }
}
