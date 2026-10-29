package ai.msrv.gaterook.e2e.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/connector-types} of the assembled core with the real built-in connectors against the kit's type
 * picker (design/pages/connection-new.html, step 1 "Choose a connector type"; M1.6 "the /api/connector-types catalogue matches the
 * kit"): the groups in the kit's order, each card's name, icon, description and tool count, each log provider as its own connector, OpenSearch under Databases, the external MCP server last with its tools from the server. The kit's catalog plugins (ClickHouse,
 * Jira Service Desk) arrive with the plugin host and the catalog (K4, K6), OpenAPI import with its template kind (C9).
 * Over the real listener, signed as Electron signs. Connectors › Installed keeps the manifests' longer texts.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectorCatalogApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("connector-catalog-it-secret-0123".getBytes(StandardCharsets.US_ASCII));

  /** One card of the kit: group, name, icon, description, the meta line's tool count ({@code null}: from the server). */
  private record KitCard(String group, String type, String variant, String name, String icon, String kind,
      Integer tools, String description) {}

  private static final List<KitCard> KIT = List.of(
      new KitCard("Databases", "postgres", null, "PostgreSQL", "database", "built-in", 4,
          "Read queries, schema, EXPLAIN. Writes wait for approval."),
      new KitCard("Databases", "mysql", null, "MySQL", "database", "built-in", 4,
          "MySQL 8 and MariaDB. Read queries, schema, EXPLAIN."),
      new KitCard("Databases", "oracle", null, "Oracle Database", "database", "built-in", 4,
          "Oracle Database 19c and later. Read queries and schema."),
      new KitCard("Databases", "mongodb", null, "MongoDB", "leaf", "built-in", 4,
          "Find, aggregate and update documents. Updates require approval by default."),
      new KitCard("Databases", "redis", null, "Redis", "layers", "built-in", 2,
          "Read keys and scan by pattern. Read only."),
      new KitCard("Databases", "opensearch", null, "OpenSearch", "database", "built-in", 3,
          "Query OpenSearch indices and use its REST API. Administrative requests require approval by default."),
      new KitCard("Remote access", "ssh", null, "SSH", "terminal", "built-in", 2,
          "Runbooks with typed parameters, through an SSH CA or a key."),
      new KitCard("Logs", "gcp-logging", null, "GCP Cloud Logging", "scroll-text", "built-in", 2,
          "Search Google Cloud logs with native Logs Explorer filters and scoped credentials."),
      new KitCard("Logs", "cloudwatch", null, "AWS CloudWatch", "scroll-text", "built-in", 2,
          "Search AWS CloudWatch log groups with Logs Insights or filter patterns."),
      new KitCard("Logs", "loki", null, "Loki", "scroll-text", "built-in", 2,
          "Search and tail Loki logs with LogQL and scoped credentials."),
      new KitCard("Logs", "ssh-logs", null, "SSH log files", "file-text", "built-in", 3,
          "Read and search selected log files over SSH with bounded server-side commands."),
      new KitCard("Kubernetes", "kubernetes", null, "Kubernetes", "ship-wheel", "built-in", 4,
          "Pods, logs and events; rollouts and apply with approval."),
      new KitCard("Cloud", "gcp", null, "GCP", "cloud", "built-in", 4,
          "Resources, statuses, metrics and Cloud Logging, read with the connection's Google identity."),
      new KitCard("Cloud", "aws", null, "AWS", "cloud", "built-in", 2,
          "Resources, statuses and metrics, read with AssumeRole."),
      new KitCard("HTTP and MCP", "http", null, "HTTP API", "globe", "built-in", 2,
          "One base URL with an allowlist. GET reads; every other method waits for approval."),
      new KitCard("HTTP and MCP", "mcp-server", null, "External MCP server", "plug", "mcp-server", null,
          "Put any MCP server behind modes, approvals and audit."));

  private static ConfigurableApplicationContext core;
  private static int port;
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final SecureRandom RANDOM = new SecureRandom();

  @BeforeAll
  static void start() {
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + newHome(), "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s");
    port = core.getBean(LocalListener.class).port();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  @Order(1)
  void theTypePickerIsTheKits() throws Exception {
    JsonNode catalog = get("/api/connector-types");

    assertThat(each(catalog.path("groups"), "name")).containsExactly("Databases", "Remote access",
        "Logs", "Kubernetes", "Cloud", "HTTP and MCP");
    List<KitCard> served = new ArrayList<>();
    for (JsonNode group : catalog.path("groups")) {
      for (JsonNode card : group.path("cards")) {
        served.add(new KitCard(group.path("name").asString(), card.path("type").asString(),
            card.path("variant").isNull() ? null : card.path("variant").asString(), card.path("name").asString(),
            card.path("icon").asString(), card.path("kind").asString(),
            card.path("tools").isNull() ? null : card.path("tools").asInt(), card.path("description").asString()));
        assertThat(card.path("available").asBoolean()).as(card.toString()).isTrue();
        assertThat(card.path("connections").asInt()).isZero();
      }
    }
    assertThat(served).containsExactlyElementsOf(KIT);

    JsonNode mcp = card(catalog, "mcp-server", null);
    assertThat(mcp.path("toolsFrom").asString()).as("\"Tools from the server\"").isEqualTo("server");
    JsonNode loki = card(catalog, "loki", null);
    assertThat(loki.path("variant").isNull()).as("each service is a concrete connector").isTrue();

    // Connectors › Installed: one row per type, the MCP server type isn't one (its servers are, with K5).
    List<String> installed = each(catalog.path("installed"), "id");
    assertThat(installed).containsExactly("postgres", "mysql", "oracle", "mongodb", "redis", "opensearch", "ssh", "gcp-logging", "cloudwatch", "loki", "ssh-logs", "kubernetes",
        "gcp", "aws", "http");
    for (JsonNode row : catalog.path("installed")) {
      assertThat(row.path("state").asString()).isEqualTo("enabled");
      assertThat(row.path("kind").asString()).isEqualTo("built-in");
    }
    assertThat(catalog.path("installed").get(0).path("description").asString())
        .isEqualTo("Schemas, tables and queries on PostgreSQL 12 and later. Writes wait for approval.");
    assertThat(catalog.path("installed")).allSatisfy(row -> assertThat(row.path("variants").isEmpty()).isTrue());
    assertThat(installed).doesNotContain("logs");
  }

  @Test
  void draftToolPreviewsBelongToTheSpecificServiceAndKeepLegacyConnectionsUsable() throws Exception {
    JsonNode google = preview("gcp-logging", "{\"credentials\":{\"source\":\"adc\"}}");
    assertThat(each(google, "name")).containsExactly("gcp_logging_search", "gcp_logging_tail");
    assertThat(google).allSatisfy(tool -> assertThat(tool.path("description").asString())
        .contains("Google Cloud Logging").doesNotContain("OpenSearch", "Loki", "CloudWatch"));
    JsonNode legacy = preview("logs", "{\"backend\":\"gcp-logging\",\"credentials\":{\"source\":\"adc\"}}");
    assertThat(each(legacy, "name")).containsExactly("logs_search", "logs_tail");
    assertThat(legacy).allSatisfy(tool -> assertThat(tool.path("description").asString())
        .contains("Google Cloud Logging").doesNotContain("OpenSearch", "Loki", "CloudWatch"));
    JsonNode cloud = preview("gcp", "{}");
    assertThat(each(cloud, "name")).containsExactly("gcp_describe", "gcp_metrics", "logs_search", "logs_tail");
    assertThat(cloud).allSatisfy(tool -> assertThat(tool.path("description").asString())
        .doesNotContain("OpenSearch", "Loki", "CloudWatch"));
    assertThat(each(preview("loki", "{}"), "name")).containsExactly("loki_search", "loki_tail");
    assertThat(each(preview("cloudwatch", "{}"), "name"))
        .containsExactly("cloudwatch_logs_search", "cloudwatch_logs_tail");
    JsonNode search = preview("opensearch", "{\"url\":\"https://search.example.test\",\"auth\":{\"mode\":\"none\"}}");
    assertThat(each(search, "name")).containsExactly("opensearch_indices", "opensearch_query", "opensearch_request");
    JsonNode limited = preview("logs", """
        {"backend":"opensearch","url":"https://search.example.test","auth":{"mode":"none"},
         "apiAccess":"logs","indices":["logs-app-*"]}""");
    assertThat(each(limited, "name")).containsExactly("logs_search", "logs_tail", "opensearch_indices");
    assertThat(send("POST", "/api/connector-types/logs/tools", "{\"config\":null}").status()).isEqualTo(422);
  }

  @Test
  void anEndpointOffersOnlyTheToolsOfEachConcreteConnection() throws Exception {
    Answer google = send("POST", "/api/connections", """
        {"name":"catalog-google","type":"gcp-logging","env":"dev",
         "config":{"backend":"gcp-logging","resourceNames":["projects/example-dev"],"credentials":{"source":"adc"}}}""");
    assertThat(google.status()).as(google.body()).isEqualTo(201);
    Answer search = send("POST", "/api/connections", """
        {"name":"catalog-search","type":"opensearch","env":"dev",
         "config":{"backend":"opensearch","url":"https://search.example.test","auth":{"mode":"none"}}}""");
    assertThat(search.status()).as(search.body()).isEqualTo(201);
    assertThat(send("POST", "/api/endpoints", "{\"slug\":\"catalog-mixed\",\"startFrom\":\"empty\"}").status())
        .isEqualTo(201);
    for (String name : List.of("catalog-google", "catalog-search")) {
      assertThat(send("POST", "/api/endpoints/catalog-mixed/connections/" + name, "{}").status()).isEqualTo(200);
    }
    JsonNode groups = get("/api/endpoints/catalog-mixed/tools");
    assertThat(each(groups, "connection")).contains("catalog-google", "catalog-search");
    for (JsonNode group : groups) {
      if (group.path("connection").asString("").equals("catalog-google")) {
        assertThat(each(group.path("tools"), "name")).containsExactly("gcp_logging_search", "gcp_logging_tail");
      } else if (group.path("connection").asString("").equals("catalog-search")) {
        assertThat(each(group.path("tools"), "name"))
            .containsExactly("opensearch_indices", "opensearch_query", "opensearch_request");
      }
    }
    ToolCatalog.Catalog published = core.getBean(ToolCatalog.class)
        .forEndpoint(core.getBean(EndpointService.class).snapshot("catalog-mixed"));
    assertThat(published.problems()).isEmpty();
    assertThat(published.tool("gcp_logging_search")).isPresent().get().satisfies(tool -> {
      assertThat(tool.inputSchema().at("/properties/connection/enum").toString()).isEqualTo("[\"catalog-google\"]");
      assertThat(tool.description()).doesNotContain("OpenSearch", "catalog-search");
    });
    assertThat(published.tool("opensearch_query")).isPresent().get().satisfies(tool -> {
      assertThat(tool.inputSchema().at("/properties/connection/enum").toString()).isEqualTo("[\"catalog-search\"]");
      assertThat(tool.description()).doesNotContain("Google Cloud", "catalog-google");
    });
    assertThat(published.tool("logs_search")).isEmpty();
  }

  @Test
  void aPrivateAddressSuggestionAllowsItForThatConnectionOnly() throws Exception {
    Answer created = send("POST", "/api/connections", """
        {"name":"billing-api","type":"http","env":"dev",
         "config":{"baseUrls":["https://billing.internal.test/v1"],"auth":{"type":"none"}},"reason":"billing"}""");
    assertThat(created.status()).as(created.body()).isEqualTo(201);

    Answer suggested = send("POST", "/api/connections/billing-api/policy-suggestion",
        "{\"kind\":\"allow-private\",\"cidr\":\"10.30.4.12/32\",\"reason\":\"billing runs inside\"}");
    assertThat(suggested.status()).as(suggested.body()).isEqualTo(200);
    assertThat(JSON.readTree(suggested.body()).path("allowedPrivateRanges").get(0).asString())
        .isEqualTo("10.30.4.12/32");
    Answer again = send("POST", "/api/connections/billing-api/policy-suggestion",
        "{\"kind\":\"allow-private\",\"cidr\":\"10.30.4.12/32\"}");
    assertThat(again.status()).isEqualTo(200);

    JsonNode dev = get("/api/policies/dev");
    JsonNode ranges = dev.path("sections").path("http").path("allowedPrivateRanges");
    assertThat(ranges.size()).as("the same range for the same connection isn't added twice").isEqualTo(1);
    assertThat(ranges.get(0).path("cidr").asString()).isEqualTo("10.30.4.12/32");
    assertThat(ranges.get(0).path("connections").get(0).asString()).isEqualTo("billing-api");

    EnvironmentPolicies policies = core.getBean(EnvironmentPolicies.class);
    assertThat(policies.allowedPrivateRanges(Env.DEV, "billing-api")).containsExactly("10.30.4.12/32");
    assertThat(policies.allowedPrivateRanges(Env.DEV, "other-api")).isEmpty();
    assertThat(policies.allowedPrivateRanges(Env.STAGE, "billing-api")).as("never inherited").isEmpty();

    Answer bad = send("POST", "/api/connections/billing-api/policy-suggestion",
        "{\"kind\":\"allow-private\",\"cidr\":\"10.30.4.12\"}");
    assertThat(bad.status()).isEqualTo(422);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static JsonNode preview(String type, String config) throws Exception {
    Answer answer = send("POST", "/api/connector-types/" + type + "/tools", "{\"config\":" + config + "}");
    assertThat(answer.status()).as(answer.body()).isEqualTo(200);
    return JSON.readTree(answer.body()).path("tools");
  }

  /** {@code field} of each element of {@code array}, not of what they hold. */
  private static List<String> each(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(e -> out.add(e.path(field).asString()));
    return out;
  }

  private static JsonNode card(JsonNode catalog, String type, String variant) {
    for (JsonNode group : catalog.path("groups")) {
      for (JsonNode card : group.path("cards")) {
        boolean sameVariant = variant == null ? card.path("variant").isNull()
            : variant.equals(card.path("variant").asString(null));
        if (card.path("type").asString().equals(type) && sameVariant) {
          return card;
        }
      }
    }
    throw new AssertionError("no card " + type + "/" + variant);
  }

  private record Answer(int status, String body) {}

  private static JsonNode get(String target) throws Exception {
    Answer a = send("GET", target, null);
    assertThat(a.status()).as("GET " + target + ": " + a.body()).isEqualTo(200);
    return JSON.readTree(a.body());
  }

  /** A request as Electron's main process sends it: signed over method, path and query with the launch secret. */
  private static Answer send(String method, String target, String json) throws Exception {
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    String auth = LaunchSecret.fromBase64Url(LAUNCH_SECRET).sign(method, target, System.currentTimeMillis(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
        .timeout(Duration.ofSeconds(30)).header(LaunchSecret.HEADER, auth)
        .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json));
    if (json != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static String newHome() {
    try {
      Path parent = Path.of("target", "gateway-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, "connector-catalog-").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
