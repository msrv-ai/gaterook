package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.support.egress.AddressPolicy;
import com.gaterook.connectors.support.egress.EgressClients;
import com.gaterook.connectors.support.egress.EgressSpec;
import com.gaterook.connectors.support.egress.GuardedEgress;
import com.gaterook.connectors.support.egress.HostResolver;
import com.gaterook.connectors.support.mcp.McpServerRuntime;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * A server the person signed in to (ARCHITECTURE.md §7.5): every request carries the core's current token, a 401 has
 * the core renew it and the refused request goes once more, a second refusal or a 403 for more permissions asks for a
 * new sign-in without counting as a crash, and a server that wants a sign-in says so instead of "check the headers".
 */
class HttpSignInTest {

  @TempDir
  Path home;

  private Tomcat tomcat;
  private McpSyncServer server;
  private int port;
  private volatile String accepted = "Bearer at-2";
  private volatile boolean wantMoreScope;
  /** The server answers without a sign-in too, with less access. */
  private volatile boolean anonymous;
  private final List<String> sent = new CopyOnWriteArrayList<>();
  /** The Authorization of each POST: a closed session's DELETE may arrive later. */
  private final List<String> posted = new CopyOnWriteArrayList<>();
  private final AtomicInteger calls = new AtomicInteger();
  private EgressClients clients;
  private McpServers runtime;
  private Fixtures.Host host;

  /** The core's side, as a test sees it: a token, renewed to the next one when refused. */
  private final class SignIn implements McpServerRuntime.Authorization {
    volatile String current = "Bearer at-1";
    final List<String> refusals = new CopyOnWriteArrayList<>();
    volatile boolean renewable = true;

    @Override
    public String header() {
      return current;
    }

    @Override
    public String refused(String header, String challenge, int status) throws McpServerRuntime.SignInRequired {
      refusals.add(status + " " + challenge);
      if (status == 403 || !renewable) {
        throw new McpServerRuntime.SignInRequired("lookup-api needs you to sign in again.");
      }
      int n = Integer.parseInt(current.substring("Bearer at-".length()));
      current = "Bearer at-" + (n + 1);
      return current;
    }

    @Override
    public List<String> secrets() {
      return List.of(current.substring(7));
    }
  }

  @BeforeEach
  void start() throws Exception {
    HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build())).mcpEndpoint("/mcp").build();
    server = McpServer.sync(transport).serverInfo("oauth-fake", "1.0.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .tools(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder().name("lookup").description("Looks a key up.")
                .inputSchema(Map.of("type", "object", "properties", Map.of("key", Map.of("type", "string"))))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null)).build())
            .callHandler((ex, req) -> {
              calls.incrementAndGet();
              return McpSchema.CallToolResult.builder().addTextContent("value of " + req.arguments().get("key"))
                  .build();
            })
            .build())
        .build();
    tomcat = new Tomcat();
    tomcat.setPort(0);
    tomcat.setBaseDir(home.resolve("tomcat").toString());
    Context ctx = tomcat.addContext("", null);
    Tomcat.addServlet(ctx, "mcp", transport).setAsyncSupported(true);
    ctx.addServletMappingDecoded("/mcp", "mcp");
    Filter auth = (request, response, chain) -> {
      HttpServletRequest r = (HttpServletRequest) request;
      HttpServletResponse w = (HttpServletResponse) response;
      String header = r.getHeader("Authorization");
      sent.add(String.valueOf(header));
      if ("POST".equals(r.getMethod())) {
        posted.add(String.valueOf(header));
      }
      if (header == null && anonymous) {
        chain.doFilter(request, response);
        return;
      }
      if (!accepted.equals(header)) {
        w.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", resource_metadata=\"http://127.0.0.1:"
            + port + "/.well-known/oauth-protected-resource/mcp\"");
        w.sendError(401);
        return;
      }
      if (wantMoreScope) {
        w.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"admin\"");
        w.sendError(403);
        return;
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
    host = new Fixtures.Host(home);
    runtime.attach(host);
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
    return Fixtures.cfg("cn_oauth", "lookup-api", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp","auth":{"type":"oauth"}}""".formatted(port)));
  }

  private Fixtures.Context ctx() {
    GuardedEgress guard = clients.egress(EgressSpec.of("cn_oauth",
        AddressPolicy.builder().allowPrivate("127.0.0.1/32").build()));
    return new Fixtures.Context(Duration.ofSeconds(30), false, null, guard);
  }

  @Test
  void aRefusedTokenIsRenewedOnceAndTheSameRequestGoesAgain() throws Exception {
    SignIn signIn = new SignIn();
    McpServerRuntime.Handshake h = runtime.probe(cfg(), new Fixtures.Secrets(), ctx(), signIn);

    assertThat(h.tools()).extracting(t -> t.path("exposed").asString()).containsExactly("lookup_lookup");
    assertThat(signIn.refusals).singleElement().asString().startsWith("401 Bearer error=\"invalid_token\"");
    assertThat(sent.subList(0, 2)).containsExactly("Bearer at-1", "Bearer at-2");
    assertThat(sent.subList(1, sent.size())).allMatch("Bearer at-2"::equals);
  }

  @Test
  void aStoredServerSendsTheCoresTokenOnEveryCallAndMasksIt() throws Exception {
    SignIn signIn = new SignIn();
    signIn.current = "Bearer at-2";
    host.signIns.put("cn_oauth", signIn);
    McpServerRuntime.Handshake h = runtime.probe(cfg(), new Fixtures.Secrets(), ctx(), signIn);
    McpServerRuntime.Snapshot snapshot = new McpServerRuntime.Snapshot("lookup_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null);
    Connector c = runtime.connector(cfg(), snapshot);
    Fixtures.Context ctx = ctx();

    String text = c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k1\"}"), cfg(), new Fixtures.Secrets(), ctx)
        .content().getFirst().text();

    assertThat(text).isEqualTo("value of k1");
    assertThat(ctx.taints).contains("at-2");

    // The token expires: the server refuses it, the core renews it, the call runs once.
    accepted = "Bearer at-3";
    int before = calls.get();
    String again = c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k2\"}"), cfg(), new Fixtures.Secrets(), ctx())
        .content().getFirst().text();
    assertThat(again).isEqualTo("value of k2");
    assertThat(calls.get()).isEqualTo(before + 1);
    assertThat(signIn.current).isEqualTo("Bearer at-3");
  }

  @Test
  void aSecondRefusalAsksForANewSignInAndIsNoCrash() throws Exception {
    SignIn signIn = new SignIn();
    signIn.current = "Bearer at-2";
    host.signIns.put("cn_oauth", signIn);
    McpServerRuntime.Handshake h = runtime.probe(cfg(), new Fixtures.Secrets(), ctx(), signIn);
    Connector c = runtime.connector(cfg(), new McpServerRuntime.Snapshot("lookup_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    runtime.stop("cn_oauth");

    // Revoked at the server: every token is refused, and a renewal can't help.
    accepted = "Bearer none";
    assertThatThrownBy(() -> c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k\"}"), cfg(),
        new Fixtures.Secrets(), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.getMessage()).containsIgnoringCase("sign in to it again").doesNotContain("check the headers");
          assertThat(McpServerRuntime.SignInRequired.in(e)).isNotNull();
        });
    assertThat(runtime.process("cn_oauth").orElseThrow().state()).isEqualTo(McpServerRuntime.State.STOPPED);
    assertThat(runtime.process("cn_oauth").orElseThrow().detail()).startsWith("Needs sign-in");
    assertThat(host.stateNames()).doesNotContain(McpServerRuntime.State.BACKOFF, McpServerRuntime.State.FAILED);
  }

  @Test
  void aServerThatWantsMorePermissionsAsksForANewSignInAndTheCallDoesntRun() throws Exception {
    SignIn signIn = new SignIn();
    signIn.current = "Bearer at-2";
    host.signIns.put("cn_oauth", signIn);
    McpServerRuntime.Handshake h = runtime.probe(cfg(), new Fixtures.Secrets(), ctx(), signIn);
    Connector c = runtime.connector(cfg(), new McpServerRuntime.Snapshot("lookup_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k\"}"), cfg(), new Fixtures.Secrets(), ctx());
    int before = calls.get();

    wantMoreScope = true;
    assertThatThrownBy(() -> c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k\"}"), cfg(),
        new Fixtures.Secrets(), ctx()))
        .hasMessageContaining("needs you to sign in again").hasMessageContaining("didn't run");
    assertThat(calls.get()).isEqualTo(before);
    assertThat(signIn.refusals).anySatisfy(r -> assertThat(r).startsWith("403").contains("insufficient_scope"));
  }

  @Test
  void aStoredServerWithoutASignInSaysSoBeforeSendingAnything() throws Exception {
    SignIn signIn = new SignIn();
    signIn.current = "Bearer at-2";
    McpServerRuntime.Handshake h = runtime.probe(cfg(), new Fixtures.Secrets(), ctx(), signIn);
    Connector c = runtime.connector(cfg(), new McpServerRuntime.Snapshot("lookup_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    sent.clear();

    assertThatThrownBy(() -> c.execute(Fixtures.call("lookup_lookup", "{}"), cfg(), new Fixtures.Secrets(), ctx()))
        .hasMessageContaining("isn't signed in");
    assertThat(sent).isEmpty();
  }

  @Test
  void aServerWhoseSignInIsOptionalConnectsWithoutOneUntilThePersonSignsIn() throws Exception {
    anonymous = true;
    ConnectionConfig optional = Fixtures.cfg("cn_oauth", "lookup-api", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp","auth":{"type":"oauth","optional":true}}"""
        .formatted(port)));
    McpServerRuntime.Handshake h = runtime.probe(optional, new Fixtures.Secrets(), ctx(), null);
    Connector c = runtime.connector(optional, new McpServerRuntime.Snapshot("lookup_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));

    String text = c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k1\"}"), optional, new Fixtures.Secrets(),
        ctx()).content().getFirst().text();
    assertThat(text).isEqualTo("value of k1");
    assertThat(posted).isNotEmpty().allMatch("null"::equals);

    // The person signs in: the core stops the session, and the next one carries the token.
    SignIn signIn = new SignIn();
    signIn.current = "Bearer at-2";
    host.signIns.put("cn_oauth", signIn);
    runtime.stop("cn_oauth");
    posted.clear();
    c.execute(Fixtures.call("lookup_lookup", "{\"key\":\"k2\"}"), optional, new Fixtures.Secrets(), ctx());
    assertThat(posted).isNotEmpty().allMatch("Bearer at-2"::equals);
  }

  @Test
  void aServerThatWantsASignInSaysSoToAConfigWithout() {
    ConnectionConfig plain = Fixtures.cfg("cn_plain", "lookup-api", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp"}""".formatted(port)));
    assertThatThrownBy(() -> runtime.probe(plain, new Fixtures.Secrets(), ctx()))
        .hasMessageContaining("wants you to sign in");
  }
}
