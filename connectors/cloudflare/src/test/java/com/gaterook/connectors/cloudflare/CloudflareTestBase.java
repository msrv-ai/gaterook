package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** One fake Cloudflare and one connector per test, with the helpers every test class uses. */
abstract class CloudflareTestBase {

  static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
  static final String OTHER_ACCOUNT = "fedcba9876543210fedcba9876543210";
  static final String ZONE = "023e105f4ecef8ad9ca31a8372d0c353";
  static final String ZONE2 = "1d5fdc9e88c8a8c4518b068cd94331fe";
  static final String RECORD = "372e67954025e0ba6aaa6d586b9e0b59";

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(CloudflareConnector.class, "cloudflare");
  private static final Map<String, Schema> OUTPUT = new ConcurrentHashMap<>();

  FakeCloudflare cf;
  CloudflareConnector connector;
  TestSecrets secrets;

  @BeforeEach
  void startFake() {
    cf = new FakeCloudflare();
    connector = new CloudflareConnector(cf.base(), TestCtx.CLOCK);
    secrets = new TestSecrets().with(CloudflareSettings.TOKEN, FakeCloudflare.TOKEN);
  }

  @AfterEach
  void stopFake() {
    connector.close();
    cf.close();
    assertThat(cf.problems).as("every request carries the token and asks for JSON").isEmpty();
  }

  static ObjectNode json(String text) {
    return (ObjectNode) FakeCloudflare.JSON.readTree(text);
  }

  /** A dev connection of the account, with these extra settings ({@code "zones": [...]}). */
  static ConnectionConfig cfg(String extra) {
    return cfg(Env.DEV, extra, null);
  }

  static ConnectionConfig cfg(Env env, String extra, JsonNode metadata) {
    ObjectNode config = json("{\"accountId\":\"" + ACCOUNT + "\",\"credentials\":{}" + (extra == null
        || extra.isBlank() ? "" : "," + extra) + "}");
    return new ConnectionConfig("c1", "cf-dev", "cloudflare", env, 7, config, Set.of(CloudflareSettings.TOKEN),
        metadata);
  }

  static ToolCall call(String tool, String args) {
    return new ToolCall("tc_cloudflare_test", tool, "cf-dev", json(args), null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".cloudflare", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  Classification classify(ToolCall call, ConnectionConfig cfg) {
    return connector.classify(call, cfg, policy(cfg.env()));
  }

  /** A read as the core runs it: classified READ, then executed; the answer matches the tool's output schema. */
  ToolResult read(ToolCall call, ConnectionConfig cfg) throws Exception {
    Classification c = classify(call, cfg);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    ToolResult r = connector.execute(call, cfg, secrets, TestCtx.of());
    return valid(call.tool(), r);
  }

  /** A change as the core runs it, up to the approval: classified MUTATION, then prepared. */
  PreparedAction prepare(ToolCall call, ConnectionConfig cfg) throws Exception {
    Classification c = classify(call, cfg);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.MUTATION);
    return connector.prepare(call, cfg, secrets, TestCtx.of());
  }

  /** The approved change: classified again, executed with the approved payload and the prepared state. */
  ToolResult approve(ToolCall call, ConnectionConfig cfg, PreparedAction prepared) throws Exception {
    assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(classify(call, cfg).kind()).isEqualTo(Classification.Kind.MUTATION);
    return connector.execute(call, cfg, secrets, TestCtx.of().approved(prepared.preview().payload(),
        prepared.preparedState()));
  }

  static ToolResult valid(String tool, ToolResult r) {
    assertThat(r.isError()).isFalse();
    JsonNode s = r.structuredContent();
    assertThat(s).as("structuredContent").isNotNull();
    Schema schema = OUTPUT.computeIfAbsent(tool, t -> REGISTRY.getSchema(SchemaLocation.of("urn:cloudflare:" + t),
        SCHEMAS.load(t + ".output")));
    assertThat(schema.validate(s)).as(s.toString()).isEmpty();
    assertThat(r.content()).as("the same answer as text").isNotEmpty();
    return r;
  }

  static JsonNode s(ToolResult r) {
    return r.structuredContent();
  }
}
