package ai.msrv.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code azure_servicebus_read}, {@code azure_servicebus_change} and {@code azure_keyvault_read} against a fake
 * Resource Manager, a fake Service Bus namespace and a fake vault: dead letters are locked and unlocked again,
 * resubmits run only as approved and complete only what they sent, and a vault gives names and dates, never values.
 */
class AzureMessagingAndVaultTest {

  private static final String SUB = FakeAzure.SUB;
  private static final String NS = "/subscriptions/" + SUB + "/resourceGroups/stage-bus/providers/"
      + "Microsoft.ServiceBus/namespaces/acme-bus";
  private static final String HOST = "/acme-bus.servicebus.windows.net";
  private static final String DLQ = HOST + "/orders/$DeadLetterQueue/messages";
  private static final String VAULT = "/subscriptions/" + SUB + "/resourceGroups/stage-sec/providers/"
      + "Microsoft.KeyVault/vaults/acme-kv";
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(AzureConnector.class, "azure");
  private FakeAzure azure;
  private AzureConnector connector;
  private final ConnectionConfig cfg = tested(Configs.stage());
  /** The dead-letter queue of the fake: the messages in order; a locked one is skipped until unlocked. */
  private final Deque<long[]> deadLetters = new ArrayDeque<>();
  private final java.util.Set<Long> locked = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Set<Long> completed = java.util.concurrent.ConcurrentHashMap.newKeySet();

  @BeforeEach
  void start() throws Exception {
    azure = new FakeAzure();
    connector = new AzureConnector(azure.cloud, Clock.systemUTC());
    azure.on("GET " + NS, r -> FakeAzure.json(200, "{\"id\":\"" + NS + "\",\"name\":\"acme-bus\",\"location\":"
        + "\"westeurope\",\"properties\":{\"serviceBusEndpoint\":\"https://acme-bus.servicebus.windows.net:443/\"}}"));
    azure.on("GET " + NS + "/queues/orders", r -> FakeAzure.json(200, "{\"id\":\"" + NS + "/queues/orders\","
        + "\"properties\":{\"countDetails\":{\"activeMessageCount\":7,\"deadLetterMessageCount\":3}}}"));
    for (long seq : new long[] {41, 42, 43}) {
      deadLetters.add(new long[] {seq});
    }
    azure.on("POST " + DLQ + "/head", r -> {
      for (long[] m : deadLetters) {
        long seq = m[0];
        if (!locked.contains(seq) && !completed.contains(seq)) {
          locked.add(seq);
          return new MockResponse.Builder().code(201)
              .addHeader("BrokerProperties", "{\"MessageId\":\"m-" + seq + "\",\"LockToken\":\"lock-" + seq
                  + "\",\"SequenceNumber\":" + seq + ",\"DeliveryCount\":10,\"EnqueuedTimeUtc\":\"Mon, 09 Nov 2026 "
                  + "10:00:00 GMT\",\"Label\":\"order\",\"CorrelationId\":\"c-" + seq + "\",\"DeadLetterSource\":"
                  + "\"orders\",\"State\":\"Active\"}")
              .addHeader("Content-Type", "application/json")
              .addHeader("DeadLetterReason", "\"MaxDeliveryCountExceeded\"")
              .addHeader("DeadLetterErrorDescription", "\"Message could not be consumed after 10 delivery "
                  + "attempts.\"")
              .addHeader("tenant", "\"acme\"")
              .addHeader("ApiToken", "\"cnry-sb-property-token\"")
              .body("{\"order\":" + seq + "}").build();
        }
      }
      return new MockResponse.Builder().code(204).build();
    });
    azure.on("PUT " + DLQ + "/", r -> {
      locked.remove(seq(r));
      return new MockResponse.Builder().code(200).build();
    });
    azure.on("DELETE " + DLQ + "/", r -> {
      long seq = seq(r);
      locked.remove(seq);
      completed.add(seq);
      return new MockResponse.Builder().code(200).build();
    });
    azure.on("POST " + HOST + "/orders/messages", r -> new MockResponse.Builder().code(201).build());
  }

  /** {@code …/messages/m-42/lock-42} → 42. */
  private static long seq(RecordedRequest r) {
    String path = r.getUrl().encodedPath();
    return Long.parseLong(path.substring(path.lastIndexOf("lock-") + 5));
  }

  @AfterEach
  void stop() {
    connector.close();
    azure.close();
  }

  private static ConnectionConfig tested(ConnectionConfig c) {
    return new ConnectionConfig(c.id(), c.name(), c.type(), c.env(), c.version(), c.config(), c.secretFields(),
        TestCtx.JSON.createObjectNode().put("tenant", FakeAzure.TENANT));
  }

  private static ToolResult valid(String schema, ToolResult r) {
    Schema s = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:" + schema), SCHEMAS.load(schema));
    assertThat(s.validate(r.structuredContent())).as(r.structuredContent().toString()).isEmpty();
    return r;
  }

  private ToolResult run(String tool, String args, PolicyView policy) throws ConnectorException {
    ToolCall call = Configs.call(tool, cfg.name(), args);
    Classification k = connector.classify(call, cfg, policy);
    assertThat(k.kind()).as(k.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, Configs.secrets(), TestCtx.of());
  }

  // ── Service Bus ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aDeadLetterPeekLocksAndUnlocksEveryMessage() throws Exception {
    ToolResult r = valid("azure_servicebus_read.output", run(ServiceBusRequest.READ, "{\"namespace\":\"" + NS
        + "\",\"queue\":\"orders\",\"count\":2}", Configs.policy(Env.STAGE)));

    JsonNode s = r.structuredContent();
    assertThat(s.path("messages")).hasSize(2);
    assertThat(s.at("/messages/0/sequenceNumber").asLong()).isEqualTo(41);
    assertThat(s.at("/messages/0/deadLetterReason").asString()).isEqualTo("MaxDeliveryCountExceeded");
    assertThat(s.at("/messages/0/text").asString()).isEqualTo("{\"order\":41}");
    assertThat(s.at("/messages/0/properties/tenant").asString()).isEqualTo("acme");
    assertThat(s.at("/messages/0/properties/ApiToken").asString()).isEqualTo(Redactor.MASK);
    assertThat(s.at("/counts/deadLetterMessageCount").asLong()).isEqualTo(3);
    assertThat(r.content().getFirst().text()).doesNotContain("lock-41").doesNotContain("cnry-sb-property-token");
    assertThat(locked).isEmpty();
    assertThat(azure.to("PUT " + DLQ)).hasSize(2);
    assertThat(azure.to("DELETE ")).isEmpty();
    assertThat(azure.to("POST " + HOST + "/orders/messages")).isEmpty();
    assertThat(azure.scopes()).contains("https://servicebus.azure.net/.default");
  }

  @Test
  void aResubmitRunsOnlyAsApprovedAndCompletesWhatItSent() throws Exception {
    ToolCall call = Configs.call(ServiceBusRequest.CHANGE, cfg.name(), "{\"namespace\":\"" + NS + "\",\"queue\":"
        + "\"orders\",\"sequenceNumbers\":[42,99]}");
    Classification k = connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThat(k.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("only after the person approves");
    assertThat(azure.to("POST " + DLQ)).isEmpty();

    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.fields()).anySatisfy(f -> assertThat(f.value()).isEqualTo("42, 99"));
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("receive these messages again"));
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    ToolResult r = connector.execute(call, cfg, Configs.secrets(), TestCtx.of().approved(preview.payload()));

    JsonNode s = r.structuredContent();
    assertThat(s.path("resubmitted")).extracting(JsonNode::asLong).containsExactly(42L);
    assertThat(s.path("notFound")).extracting(JsonNode::asLong).containsExactly(99L);
    assertThat(completed).containsExactly(42L);
    assertThat(locked).isEmpty();
    RecordedRequest sent = azure.to("POST " + HOST + "/orders/messages").getFirst();
    assertThat(sent.getBody().utf8()).isEqualTo("{\"order\":42}");
    JsonNode broker = TestCtx.JSON.readTree(sent.getHeaders().get("BrokerProperties"));
    assertThat(broker.path("MessageId").asString()).isEqualTo("m-42");
    assertThat(broker.path("CorrelationId").asString()).isEqualTo("c-42");
    assertThat(broker.has("LockToken")).isFalse();
    assertThat(broker.has("SequenceNumber")).isFalse();
    assertThat(broker.has("DeadLetterSource")).isFalse();
    assertThat(sent.getHeaders().get("tenant")).isEqualTo("\"acme\"");
    assertThat(sent.getHeaders().names()).contains("ApiToken");
    assertThat(sent.getHeaders().get("DeadLetterReason")).isNull();
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("application/json");
  }

  @Test
  void aTopicSubscriptionsResubmitWarnsThatEverySubscriptionGetsIt() {
    ToolCall call = Configs.call(ServiceBusRequest.CHANGE, cfg.name(), "{\"namespace\":\"" + NS + "\",\"topic\":"
        + "\"events\",\"topicSubscription\":\"billing\",\"sequenceNumbers\":[7]}");
    assertThat(connector.classify(call, cfg, Configs.policy(Env.STAGE)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).contains("/events/subscriptions/billing/$DeadLetterQueue/messages/head")
        .contains("POST https://acme-bus.servicebus.windows.net/events/messages");
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("every subscription whose rules match"));
  }

  @Test
  void serviceBusArgumentsAndThePolicyAreChecked() {
    assertThat(connector.classify(Configs.call(ServiceBusRequest.READ, cfg.name(), "{\"namespace\":\"" + NS
        + "\",\"queue\":\"orders\",\"topic\":\"events\"}"), cfg, Configs.policy(Env.STAGE)).ruleId())
        .isEqualTo("stage.cloud.bad_input");
    assertThat(connector.classify(Configs.call(ServiceBusRequest.READ, cfg.name(), "{\"namespace\":\"" + NS
        + "\",\"queue\":\"orders\"}"), cfg, Configs.policy(Env.STAGE, "{\"allowedServices\":[\"storage\"]}"))
        .ruleId()).isEqualTo("stage.cloud.service_not_allowed");
    assertThat(connector.classify(Configs.call(ServiceBusRequest.READ, cfg.name(), "{\"namespace\":\"" + NS
        + "\",\"queue\":\"orders\"}"), cfg, Configs.policy(Env.STAGE, "{\"resources\":[\"" + NS
        + "/queues/billing\"]}")).ruleId()).isEqualTo("stage.cloud.resource_not_allowed");
    assertThat(connector.classify(Configs.call(ServiceBusRequest.CHANGE, cfg.name(), "{\"namespace\":\"" + NS
        + "\",\"queue\":\"orders\",\"sequenceNumbers\":[]}"), cfg, Configs.policy(Env.STAGE)).ruleId())
        .isEqualTo("stage.cloud.bad_input");
  }

  // ── Key Vault ───────────────────────────────────────────────────────────────────────────────────────────────

  private static final PolicyView VAULT_ALLOWED = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"keyvault\"]}");

  private void vault() {
    String body = "{\"id\":\"" + VAULT + "\",\"name\":\"acme-kv\",\"location\":\"westeurope\",\"properties\":"
        + "{\"vaultUri\":\"https://acme-kv.vault.azure.net/\"}}";
    azure.on("GET " + VAULT, r -> FakeAzure.json(200, body));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.KeyVault/vaults", r -> FakeAzure.json(200,
        "{\"value\":[" + body + "]}"));
  }

  @Test
  void keyVaultIsSensitiveUntilThePolicyNamesIt() {
    Classification c = connector.classify(Configs.call(KeyVaultRequest.TOOL, cfg.name(), "{\"vault\":\"" + VAULT
        + "\"}"), cfg, Configs.policy(Env.STAGE));
    assertThat(c.ruleId()).isEqualTo("stage.cloud.sensitive_service");
    assertThat(c.reason()).contains("names keyvault");
  }

  @Test
  void aVaultListsNamesVersionsAndExpiryNeverValues() throws Exception {
    vault();
    long soon = Instant.now().plusSeconds(10 * 86_400).getEpochSecond();
    azure.on("GET /acme-kv.vault.azure.net/secrets", r -> FakeAzure.json(200, "{\"value\":[{\"id\":"
        + "\"https://acme-kv.vault.azure.net/secrets/db-password\",\"attributes\":{\"enabled\":true,\"exp\":" + soon
        + ",\"created\":1762000000,\"updated\":1762000000,\"recoveryLevel\":\"Recoverable+Purgeable\"},\"tags\":"
        + "{\"owner\":\"payments\",\"dbPassword\":\"cnry-tag-secret\"},\"contentType\":\"text/plain\"}],"
        + "\"nextLink\":\"https://acme-kv.vault.azure.net:443/secrets?api-version=7.5&$skiptoken=eyJOZXh0IjoiMSJ9"
        + "&maxresults=25\"}"));

    ToolResult r = valid("azure_keyvault_read.output", run(KeyVaultRequest.TOOL, "{\"vault\":\"" + VAULT + "\"}",
        VAULT_ALLOWED));

    JsonNode s = r.structuredContent();
    assertThat(s.at("/items/0/name").asString()).isEqualTo("db-password");
    assertThat(s.at("/items/0/enabled").asBoolean()).isTrue();
    assertThat(s.at("/items/0/expiresInDays").asLong()).isBetween(9L, 10L);
    assertThat(s.at("/items/0/tags/owner").asString()).isEqualTo("payments");
    assertThat(s.at("/items/0/tags/dbPassword").asString()).isEqualTo(Redactor.MASK);
    assertThat(s.path("notes").toString()).contains("expire within 30 days");
    RecordedRequest list = azure.to("GET /acme-kv.vault.azure.net/secrets").getFirst();
    assertThat(FakeAzure.param(list, "api-version")).isEqualTo(KeyVault.API);
    assertThat(azure.scopes()).contains("https://vault.azure.net/.default");

    run(KeyVaultRequest.TOOL, "{\"vault\":\"" + VAULT + "\",\"cursor\":\"" + s.path("nextCursor").asString()
        + "\"}", VAULT_ALLOWED);
    assertThat(FakeAzure.param(azure.to("GET /acme-kv.vault.azure.net/secrets").getLast(), "$skiptoken"))
        .isEqualTo("eyJOZXh0IjoiMSJ9");
    // Only list operations: no request ever reads /secrets/<name> itself.
    List<String> paths = azure.api().stream().map(q -> q.getUrl().encodedPath())
        .filter(p -> p.startsWith("/acme-kv.vault.azure.net")).toList();
    assertThat(paths).allMatch(p -> p.equals("/acme-kv.vault.azure.net/secrets"));
  }

  @Test
  void versionsOfOneCertificateAndTheirThumbprints() throws Exception {
    vault();
    azure.on("GET /acme-kv.vault.azure.net/certificates/api-tls/versions", r -> FakeAzure.json(200, "{\"value\":[{"
        + "\"id\":\"https://acme-kv.vault.azure.net/certificates/api-tls/7f3a\",\"x5t\":\"fLi3U52HunIVNXubkEnf8tP6Wbo\","
        + "\"attributes\":{\"enabled\":true,\"exp\":1800000000}}]}"));

    JsonNode s = run(KeyVaultRequest.TOOL, "{\"vault\":\"acme-kv\",\"action\":\"certificate_versions\",\"name\":"
        + "\"api-tls\"}", VAULT_ALLOWED).structuredContent();

    assertThat(s.at("/items/0/name").asString()).isEqualTo("api-tls");
    assertThat(s.at("/items/0/version").asString()).isEqualTo("7f3a");
    assertThat(s.at("/items/0/thumbprint").asString()).isEqualTo("fLi3U52HunIVNXubkEnf8tP6Wbo");
    assertThat(s.at("/items/0/expires").asString()).isEqualTo("2027-01-15T08:00:00Z");
  }

  @Test
  void aVaultMissingItsRoleIsExplained() throws Exception {
    vault();
    azure.on("GET /acme-kv.vault.azure.net/keys", r -> FakeAzure.json(403, "{\"error\":{\"code\":\"Forbidden\","
        + "\"message\":\"Caller is not authorized to perform action on resource.\",\"innererror\":{\"code\":"
        + "\"ForbiddenByRbac\"}}}"));
    ToolCall call = Configs.call(KeyVaultRequest.TOOL, cfg.name(), "{\"vault\":\"" + VAULT + "\",\"action\":"
        + "\"keys\"}");
    connector.classify(call, cfg, VAULT_ALLOWED);
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("Key Vault Reader").hasMessageContaining("ForbiddenByRbac");
  }
}
