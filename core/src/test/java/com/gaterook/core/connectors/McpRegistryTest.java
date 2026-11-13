package com.gaterook.core.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connectors.support.egress.EgressClients;
import com.gaterook.core.connectors.plugins.CatalogDownloads;
import com.gaterook.core.platform.ProblemException;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Registry search for Add MCP server (ARCHITECTURE.md §7.5): the read API of the Official MCP Registry, the latest
 * versions only, and every entry treated as third-party data — names checked, texts cleaned and cut, only
 * {@code https://} remotes, deleted entries skipped, values of headers and variables never taken.
 */
class McpRegistryTest {

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("MCP registry test CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").signedBy(CA).build();

  /** Shaped like the official registry's answer to {@code search=linear&version=latest} (2026-11-02). */
  private static final String LINEAR = """
      {"servers": [
        {"server": {"name": "app.linear/linear", "title": "Linear", "version": "1.0.2",
           "description": "Manage issues,\\nprojects and cycles\\u202e in Linear\\u200b.",
           "websiteUrl": "https://linear.app/docs/mcp",
           "remotes": [
             {"type": "streamable-http", "url": "https://mcp.linear.app/mcp"},
             {"type": "streamable-http", "url": "https://mcp.linear.app/mcp/readonly",
              "headers": [{"name": "Authorization", "description": "Bearer token", "isRequired": true,
                           "isSecret": true, "value": "Bearer {token}"}]},
             {"type": "streamable-http", "url": "http://mcp.linear.app/plain"},
             {"type": "websocket", "url": "https://mcp.linear.app/ws"}]},
         "_meta": {"io.modelcontextprotocol.registry/official": {"status": "active", "isLatest": true}}},
        {"server": {"name": "io.github.someone/linear-mcp", "version": "0.3.2",
           "description": "Linear from the command line.",
           "repository": {"url": "https://github.com/someone/linear-mcp", "source": "github"},
           "packages": [
             {"registryType": "npm", "identifier": "@someone/linear-mcp", "version": "0.3.2", "runtimeHint": "npx",
              "transport": {"type": "stdio"},
              "environmentVariables": [
                {"name": "LINEAR_API_KEY", "description": "Personal API key", "isRequired": true, "isSecret": true,
                 "default": "lin_api_example"},
                {"name": "bad name", "isSecret": true}],
              "runtimeArguments": [{"type": "named", "name": "--yes"}],
              "packageArguments": [
                {"type": "named", "name": "--workspace", "valueHint": "workspace", "isRequired": true},
                {"type": "positional", "value": "stdio"},
                {"type": "named", "name": "; rm -rf /", "value": "x"}]}]},
         "_meta": {"io.modelcontextprotocol.registry/official": {"status": "deprecated"}}},
        {"server": {"name": "io.github.gone/linear", "version": "1.0.0",
           "remotes": [{"type": "streamable-http", "url": "https://gone.example/mcp"}]},
         "_meta": {"io.modelcontextprotocol.registry/official": {"status": "deleted"}}},
        {"server": {"name": "not a registry name", "version": "1.0.0"}},
        {"server": {"name": "com.example/tenant", "version": "2.0.0",
           "remotes": [{"type": "sse", "url": "https://{tenant_id}.example.com/sse",
             "variables": {"tenant_id": {"description": "Your tenant", "isRequired": true}, "bad-name": {}}}]}}
      ],
      "metadata": {"nextCursor": "com.example/tenant:2.0.0", "count": 5}}
      """;

  private MockWebServer server;
  private EgressClients egress;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    egress = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(7465, 7466),
        host -> List.of(InetAddress.getByName("127.0.0.1"))));
  }

  @AfterEach
  void stop() {
    server.close();
    egress.close();
  }

  @Test
  void searchesTheLatestVersionsByName() throws Exception {
    answer(200, LINEAR);
    McpRegistry.Page page = registry().search("  linear ", null);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getUrl().encodedPath()).isEqualTo("/v0.1/servers");
    assertThat(request.getUrl().queryParameter("search")).isEqualTo("linear");
    assertThat(request.getUrl().queryParameter("version")).isEqualTo("latest");
    assertThat(request.getUrl().queryParameter("limit")).isEqualTo("20");
    assertThat(request.getUrl().queryParameter("cursor")).isNull();

    assertThat(page.registry()).isEqualTo("127.0.0.1");
    assertThat(page.nextCursor()).isEqualTo("com.example/tenant:2.0.0");
    assertThat(page.servers()).extracting(McpRegistry.Server::name)
        .as("the deleted entry and the one without a registry name are skipped")
        .containsExactly("app.linear/linear", "io.github.someone/linear-mcp", "com.example/tenant");

    McpRegistry.Server linear = page.servers().getFirst();
    assertThat(linear.title()).isEqualTo("Linear");
    assertThat(linear.status()).isEqualTo("active");
    assertThat(linear.websiteUrl()).isEqualTo("https://linear.app/docs/mcp");
    assertThat(linear.listing()).as("the official registry adds no GateRook facts").isNull();
    assertThat(linear.remotes()).as("plain http and unknown transports are dropped")
        .extracting(McpRegistry.Remote::url)
        .containsExactly("https://mcp.linear.app/mcp", "https://mcp.linear.app/mcp/readonly");
    assertThat(linear.remotes().get(1).headers())
        .containsExactly(new McpRegistry.Input("Authorization", "Bearer token", true, true));
  }

  @Test
  void packagesKeepNamesAndArgumentsButNoValuesOrUnsafeNames() {
    answer(200, LINEAR);
    McpRegistry.Server cli = registry().search("linear", null).servers().get(1);

    assertThat(cli.status()).isEqualTo("deprecated");
    assertThat(cli.repositoryUrl()).isEqualTo("https://github.com/someone/linear-mcp");
    McpRegistry.Package npm = cli.packages().getFirst();
    assertThat(npm).extracting(McpRegistry.Package::registryType, McpRegistry.Package::identifier,
        McpRegistry.Package::version, McpRegistry.Package::runtimeHint, McpRegistry.Package::transport)
        .containsExactly("npm", "@someone/linear-mcp", "0.3.2", "npx", "stdio");
    assertThat(npm.environmentVariables()).as("names only: the default value stays in the registry")
        .containsExactly(new McpRegistry.Input("LINEAR_API_KEY", "Personal API key", true, true));
    assertThat(npm.runtimeArguments()).containsExactly(new McpRegistry.Argument("named", "--yes", null, null, false));
    assertThat(npm.packageArguments()).containsExactly(
        new McpRegistry.Argument("named", "--workspace", null, "workspace", true),
        new McpRegistry.Argument("positional", null, "stdio", null, false));
  }

  @Test
  void urlTemplatesKeepTheirVariables() {
    answer(200, LINEAR);
    McpRegistry.Remote remote = registry().search("linear", null).servers().get(2).remotes().getFirst();

    assertThat(remote.type()).isEqualTo("sse");
    assertThat(remote.url()).isEqualTo("https://{tenant_id}.example.com/sse");
    assertThat(remote.variables()).containsExactly(new McpRegistry.Input("tenant_id", "Your tenant", true, false));
  }

  @Test
  void textsLoseControlAndFormatCharactersAndAreCut() {
    answer(200, LINEAR);
    assertThat(registry().search("linear", null).servers().getFirst().description())
        .as("the newline collapses; the bidi override and the zero-width space go")
        .isEqualTo("Manage issues, projects and cycles in Linear.");

    String long_ = "{\"servers\": [{\"server\": {\"name\": \"com.example/long\", \"description\": \""
        + "word ".repeat(100) + "\"}}]}";
    answer(200, long_);
    String description = registry().search(null, null).servers().getFirst().description();
    assertThat(description).hasSize(300).endsWith("…");
  }

  @Test
  void gateRooksFactsComeFromItsMeta() {
    answer(200, """
        {"servers": [
          {"server": {"name": "app.linear/linear"},
           "_meta": {"com.gaterook/catalog": {"verification": "vendor", "featured": true,
                                                   "category": "Project management"}}},
          {"server": {"name": "com.example/x",
                      "_meta": {"com.gaterook/catalog": {"verification": "official!", "featured": "yes"}}}}
        ]}
        """);
    List<McpRegistry.Server> servers = registry().search("linear", null).servers();

    assertThat(servers.get(0).listing()).isEqualTo(new McpRegistry.Listing("vendor", true, "Project management"));
    assertThat(servers.get(1).listing()).as("an unknown level is not shown as a verification")
        .isEqualTo(new McpRegistry.Listing(null, false, null));
  }

  @Test
  void theCursorOfThePreviousPageIsPassedOn() throws Exception {
    answer(200, "{\"servers\": [], \"metadata\": {\"count\": 0}}");
    McpRegistry.Page page = registry().search("linear", "app.linear/linear:1.0.2");

    assertThat(server.takeRequest().getUrl().queryParameter("cursor")).isEqualTo("app.linear/linear:1.0.2");
    assertThat(page.servers()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void aSearchTheRegistryWontTakeIsRefusedWithoutARequest() {
    for (String query : List.of("ab", "x".repeat(101), "linear\u0000")) {
      assertThatThrownBy(() -> registry().search(query, null)).as(query).isInstanceOf(ProblemException.class)
          .extracting(e -> ((ProblemException) e).kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
    }
    assertThatThrownBy(() -> registry().search("linear", "c".repeat(513))).isInstanceOf(ProblemException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void anAnswerThatIsNotAPageMakesTheRegistryUnavailable() {
    answer(503, "{}");
    assertThatThrownBy(() -> registry().search("linear", null)).isInstanceOf(McpRegistry.UnavailableException.class)
        .hasMessageContaining("HTTP 503");

    answer(200, "<html>Just a moment...</html>");
    assertThatThrownBy(() -> registry().search("linear", null)).isInstanceOf(McpRegistry.UnavailableException.class)
        .hasMessageContaining("didn't answer with JSON");

    answer(200, "{\"servers\": {}}");
    assertThatThrownBy(() -> registry().search("linear", null)).isInstanceOf(McpRegistry.UnavailableException.class)
        .hasMessageContaining("list of servers");
  }

  @Test
  void withoutAnAddressSearchIsOff() {
    for (String url : new String[] {"", "http://registry.example"}) {
      McpRegistry off = new McpRegistry(new CatalogDownloads(egress, List.of(CA.certificate())), url);
      assertThatThrownBy(() -> off.search("linear", null)).as(url).isInstanceOf(ProblemException.class)
          .extracting(e -> ((ProblemException) e).kind()).isEqualTo(ProblemException.Kind.CONFLICT);
    }
  }

  @Test
  void theReleaseGuardNeverReachesThisComputer() {
    answer(200, LINEAR);
    McpRegistry release = new McpRegistry(new CatalogDownloads(egress, List.of()),
        "https://127.0.0.1:" + server.getPort());
    assertThatThrownBy(() -> release.search("linear", null)).isInstanceOf(McpRegistry.UnavailableException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  private McpRegistry registry() {
    return new McpRegistry(new CatalogDownloads(egress, List.of(CA.certificate())),
        "https://127.0.0.1:" + server.getPort() + "/");
  }

  private void answer(int code, String body) {
    server.enqueue(new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        .build());
  }
}
