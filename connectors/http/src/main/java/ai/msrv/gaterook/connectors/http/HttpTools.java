package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The two tools of HTTP API (report 10 §3, kit endpoint-detail). A GET can have side effects and opens a channel
 * out, so both are open-world with CHANNEL egress; {@code http_get} starts Off on prod (ARCHITECTURE.md §9).
 * {@code http_request} is MIXED: GET and HEAD classify as READ, yet its mode is always Approval.
 */
final class HttpTools {

  static final String GET = "http_get";
  static final String REQUEST = "http_request";

  private HttpTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        ToolDefinition.builder(GET)
            .title("HTTP GET")
            .description("GET or HEAD on the allowed paths of the API.")
            .inputSchema(schemas.load("http_get.input"))
            .outputSchema(schemas.load("http_get.output"))
            .annotations(new Annotations(true, false, true, true))
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.OFF)
            .resultShape(ResultShape.JSON)
            .risk(new Risk(true, true, Egress.CHANNEL))
            .build(),
        ToolDefinition.builder(REQUEST)
            .title("HTTP request (needs approval)")
            .description("POST, PUT, PATCH or DELETE on the allowed paths. You approve the exact request.")
            .inputSchema(schemas.load("http_request.input"))
            .annotations(new Annotations(false, true, false, true))
            .nature(Nature.MIXED)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .resultShape(ResultShape.JSON)
            .risk(new Risk(true, true, Egress.CHANNEL))
            .build());
  }
}
