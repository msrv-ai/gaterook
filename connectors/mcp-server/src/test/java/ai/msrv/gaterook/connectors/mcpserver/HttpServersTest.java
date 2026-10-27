package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP transport against the MCP Java SDK's Streamable HTTP server in an embedded Tomcat on this computer: the
 * handshake, a call, the session id and protocol headers, a secret header, a refusal, and the egress guard that keeps
 * a server on a private address unreachable until the environment's policy allows it.
 */
class HttpServersTest {

  private static final String TOKEN = "Bearer http-token-1f6c2b9d0e";

  @TempDir
  Path home;

  private Tomcat tomcat;
  private McpSyncServer server;
  private int port;
  private final List<String> sessions = new CopyOnWriteArrayList<>();
  private final List<String> versions = new CopyOnWriteArrayList<>();
  private EgressClients clients;
  private McpServers runtime;

  @BeforeEach
  void start() throws Exception {
    HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build())).mcpEndpoint("/mcp").build();
    server = McpServer.sync(transport).serverInfo("http-fake", "4.5.6")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .tools(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder().name("lookup").description("Looks a key up.")
                .inputSchema(Map.of("type", "object", "properties", Map.of("key", Map.of("type", "string"))))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null)).build())
            .callHandler((ex, req) -> McpSchema.CallToolResult.builder()
                .addTextContent("value of " + req.arguments().get("key")).build())
            .build())
        .build();
    tomcat = new Tomcat();
    tomcat.setPort(0);
    tomcat.setBaseDir(home.resolve("tomcat").toString());
    Context ctx = tomcat.addContext("", null);
    Tomcat.addServlet(ctx, "mcp", transport).setAsyncSupported(true);
    ctx.addServletMappingDecoded("/mcp", "mcp");
    // The upstream's own authorization: a static header the gateway sends from a secret.
    Filter auth = (request, response, chain) -> {
      HttpServletRequest r = (HttpServletRequest) request;
      if (!TOKEN.equals(r.getHeader("Authorization"))) {
        ((HttpServletResponse) response).sendError(401);
        return;
      }
      if (r.getHeader("Mcp-Session-Id") != null) {
        sessions.add(r.getHeader("Mcp-Session-Id"));
      }
      if (r.getHeader("MCP-Protocol-Version") != null) {
        versions.add(r.getHeader("MCP-Protocol-Version"));
      }
      chain.doFilter(request, response);
    };
    FilterDef def = new FilterDef();
    def.setFilterName("auth");
    def.setFilter(auth);
    def.setAsyncSupported("true");
    ctx.addFilterDef(def);
    FilterMap map = new FilterMap();
    map.setFilterName("auth");
    map.addURLPattern("/*");
    ctx.addFilterMap(map);
    tomcat.getConnector();
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(), HostResolver.SYSTEM));
    runtime = new McpServers(Map.of("PATH", "/usr/bin:/bin"), System.getProperty("os.name"), false,
        Clock.systemUTC(), Duration.ofMillis(200), McpServerConnector.typeManifest());
    runtime.attach(new Fixtures.Host(home));
  }

  @AfterEach
  void stop() throws Exception {
    runtime.close();
    server.close();
    tomcat.stop();
    tomcat.destroy();
    clients.close();
  }

  private ConnectionConfig cfg() {
    return Fixtures.cfg("cn_http", "lookup-api", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp","headers":[{"name":"Authorization"}]}""".formatted(port)));
  }

  private Fixtures.Context ctx(String... allowedPrivate) {
    GuardedEgress guard = clients.egress(EgressSpec.of("cn_http",
        AddressPolicy.builder().allowPrivate(allowedPrivate).build()));
    return new Fixtures.Context(Duration.ofSeconds(30), false, null, guard);
  }

  @Test
  void aStreamableHttpServerAnswersOverTheGuardedClient() throws Exception {
    Fixtures.Secrets secrets = new Fixtures.Secrets().with("/headers/0/secretRef", TOKEN);
    McpServerRuntime.Handshake h = runtime.probe(cfg(), secrets, ctx("127.0.0.1/32"));

    assertThat(h.serverVersion()).isEqualTo("4.5.6");
    assertThat(h.protocolVersion()).isEqualTo("2025-11-25");
    assertThat(h.tools()).extracting(t -> t.path("exposed").asString()).containsExactly("lookup_lookup");

    // The probe's session is its own; the stored server opens another one and keeps to it.
    sessions.clear();
    McpServerRuntime.Snapshot snapshot = new McpServerRuntime.Snapshot("lookup_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null);
    Connector c = runtime.connector(cfg(), snapshot);
    Fixtures.Context ctx = ctx("127.0.0.1/32");
    String text = c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k1\"}"), cfg(), secrets, ctx).content()
        .getFirst().text();

    assertThat(text).isEqualTo("value of k1");
    assertThat(sessions).isNotEmpty().allMatch(s -> s.equals(sessions.getFirst()));
    assertThat(versions).isNotEmpty().allMatch("2025-11-25"::equals);
    assertThat(ctx.taints).contains(TOKEN);
  }

  @Test
  void aRefusedHeaderSaysSo() {
    Fixtures.Secrets wrong = new Fixtures.Secrets().with("/headers/0/secretRef", "Bearer wrong");

    assertThatThrownBy(() -> runtime.probe(cfg(), wrong, ctx("127.0.0.1/32")))
        .hasMessageContaining("refused the request (HTTP 401)");
  }

  @Test
  void aPrivateAddressNeedsThePolicyAndTheTestOffersIt() {
    Fixtures.Secrets secrets = new Fixtures.Secrets().with("/headers/0/secretRef", TOKEN);
    McpServerConnector type = new McpServerConnector(runtime);

    TestResult t = type.test(cfg(), secrets, ctx());

    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("mcp.private-address");
    assertThat(t.suggestedPolicyChange().path("kind").asString()).isEqualTo("allow-private");
    assertThat(t.suggestedPolicyChange().path("cidr").asString()).isEqualTo("127.0.0.1/32");
  }

  @Test
  void onlyHttpsOrThisComputer() {
    ConnectionConfig plain = Fixtures.cfg("cn_x", "plain", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://example.com/mcp"}"""));

    assertThatThrownBy(() -> runtime.probe(plain, new Fixtures.Secrets(), ctx()))
        .hasMessageContaining("Use an https:// URL");
    // A backslash is a slash to OkHttp, and the URL it reads is the one judged: no 500 from another parser.
    ConnectionConfig slash = Fixtures.cfg("cn_y", "slash", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://example.com\\\\mcp"}"""));
    assertThatThrownBy(() -> runtime.probe(slash, new Fixtures.Secrets(), ctx()))
        .isInstanceOf(ai.msrv.gaterook.connector.api.ConnectorException.class)
        .hasMessageContaining("Use an https:// URL");
  }
}
