package gaterook.training;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plugin the host's AOT training run loads ({@code Main --train}, report 16 §3.3): a connector that touches what a
 * real one does — secrets over the callback, the guarded HTTP client, a table result, a log line, classify and
 * describeAction — so the host's classes for all of that land in the cache.
 *
 * <p>It lives outside {@code ai.msrv.gaterook} because a plugin's {@code connectorClass} may not be there (those packages
 * are the host's and the SPI's), and it is one class without nested ones: {@code Train} copies this single class file
 * into the training plugin's jar.
 */
public final class TrainingConnector implements Connector {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ConnectorManifest manifest = ConnectorManifest.builder("aot-training", "1.0.0",
      ConnectorManifest.Kind.PLUGIN)
      .name("AOT training")
      .description("The plugin host's training workload.")
      .configSchema(MAPPER.readTree("""
          {"type":"object","properties":{"url":{"type":"string"},"token":{"type":"string","writeOnly":true}}}"""))
      .tools(List.of(
          ToolDefinition.builder("train_echo").title("Echo")
              .inputSchema(MAPPER.readTree("""
                  {"type":"object","properties":{"text":{"type":"string"},"http":{"type":"boolean"}}}"""))
              .resultShape(ToolDefinition.ResultShape.TABLE)
              .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL)
              .build(),
          ToolDefinition.builder("train_note").title("Note")
              .inputSchema(MAPPER.readTree("""
                  {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""))
              .annotations(ToolDefinition.Annotations.write(false))
              .nature(ToolDefinition.Nature.MUTATION)
              .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL)
              .risk(new ToolDefinition.Risk(true, true, ToolDefinition.Egress.CHANNEL))
              .build(),
          ToolDefinition.builder("train_mixed").title("Mixed")
              .inputSchema(MAPPER.readTree("""
                  {"type":"object","properties":{"write":{"type":"boolean"}}}"""))
              .annotations(new ToolDefinition.Annotations(false, false, false, false))
              .nature(ToolDefinition.Nature.MIXED)
              .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL)
              .risk(new ToolDefinition.Risk(true, true, ToolDefinition.Egress.CHANNEL))
              .build()))
      .build();

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    if ("train_note".equals(call.tool())) {
      return Classification.mutation("Writes a note");
    }
    if ("train_mixed".equals(call.tool()) && call.arguments().path("write").asBoolean(false)) {
      return Classification.mutation("Asked to write");
    }
    return Classification.read();
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String body = call.arguments().toString();
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", "POST /notes\n" + body, cfg.name(), "Training note")
        .withPayload(call.arguments().deepCopy());
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try (SecretResolver.Secret token = secrets.secret("token")) {
      int length = token.chars().length;
      ctx.log().log(System.Logger.Level.DEBUG, "token of " + length + " chars");
      return TestResult.success(1, "1.0.0", "trainer", null);
    } catch (Exception e) {
      return TestResult.failure("training", e.getMessage(), null);
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    int length;
    try (SecretResolver.Secret token = secrets.secret("token")) {
      length = token.chars().length;
    } catch (Exception e) {
      throw ConnectorException.denied("No token: " + e.getMessage());
    }
    ctx.log().log(System.Logger.Level.DEBUG, "{0} with a token of {1} chars", call.tool(), length);
    String url = cfg.string("/url");
    int status = 0;
    if (url != null && call.arguments().path("http").asBoolean(false)) {
      ExecContext.EgressResponse r = ctx.egress().send(new ExecContext.EgressRequest("GET", URI.create(url),
          Map.of("Accept", List.of("application/json")), null, Duration.ofSeconds(5)), 64 * 1024);
      status = r.status();
    }
    if ("train_note".equals(call.tool()) && !ctx.approved()) {
      throw ConnectorException.denied("train_note runs only when approved");
    }
    ArrayNode rows = NODES.arrayNode();
    for (int i = 0; i < 20; i++) {
      ArrayNode row = rows.addArray();
      row.add(i);
      row.add(call.arguments().path("text").asString("") + i);
      row.add(status);
    }
    ObjectNode structured = NODES.objectNode();
    structured.put("rows", rows.size());
    structured.put("status", status);
    return ToolResult.table(new ToolResult.Table(List.of(
        new ToolResult.Column("n", "int", null, null, null),
        new ToolResult.Column("text", "text", null, null, null),
        new ToolResult.Column("status", "int", null, null, null)), rows, null), structured);
  }
}
