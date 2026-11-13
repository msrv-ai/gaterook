package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
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
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Servers that sign in with cloud credentials (ARCHITECTURE.md §7.6): a Google token from the sign-in of this computer
 * with the billed project on every request, renewed once when refused, and AWS SigV4 that the server can check
 * against the method, URL, host and body it received. A refusal names the credentials and quotes the server.
 */
class HttpCloudCredentialsTest {

  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final Pattern SIGV4 = Pattern.compile("AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/([^/]+)/"
      + "aws4_request, SignedHeaders=([^,]+), Signature=([0-9a-f]{64})");

  @TempDir
  Path home;

  private Tomcat tomcat;
  private McpSyncServer server;
  private int port;
  private EgressClients clients;
  private McpServers runtime;
  private volatile String mode = "google";
  private volatile String acceptedToken = "ya29.t1";
  private volatile boolean forbidden;
  private final AtomicInteger tokens = new AtomicInteger();
  private final List<String> bearer = new CopyOnWriteArrayList<>();
  private final List<String> billed = new CopyOnWriteArrayList<>();
  private final List<String> signatures = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws Exception {
    HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build())).mcpEndpoint("/mcp").build();
    server = McpServer.sync(transport).serverInfo("cloud-fake", "1.0.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .tools(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder().name("list_instances").description("Lists instances.")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null)).build())
            .callHandler((ex, req) -> McpSchema.CallToolResult.builder().addTextContent("instance-1").build())
            .build())
        .build();
    tomcat = new Tomcat();
    tomcat.setPort(0);
    tomcat.setBaseDir(home.resolve("tomcat").toString());
    Context ctx = tomcat.addContext("", null);
    Tomcat.addServlet(ctx, "mcp", transport).setAsyncSupported(true);
    ctx.addServletMappingDecoded("/mcp", "mcp");
    // Google's token endpoint, as the sign-in of this computer renews its token there.
    Tomcat.addServlet(ctx, "token", new HttpServlet() {
      @Override
      protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int n = tokens.incrementAndGet();
        resp.setContentType("application/json");
        resp.getWriter().write("{\"access_token\":\"ya29.t" + n + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
      }
    });
    ctx.addServletMappingDecoded("/token", "token");
    Filter check = (request, response, chain) -> {
      HttpServletRequest r = (HttpServletRequest) request;
      HttpServletResponse w = (HttpServletResponse) response;
      if (!r.getRequestURI().equals("/mcp")) {
        chain.doFilter(request, response);
        return;
      }
      if ("google".equals(mode)) {
        String header = String.valueOf(r.getHeader("Authorization"));
        bearer.add(header);
        billed.add(String.valueOf(r.getHeader("x-goog-user-project")));
        if (!header.equals("Bearer " + acceptedToken)) {
          w.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
          w.sendError(401);
          return;
        }
        chain.doFilter(request, response);
        return;
      }
      Cached cached = new Cached(r);
      String verdict = verify(cached);
      signatures.add(verdict);
      if (forbidden || !verdict.equals("valid")) {
        w.setStatus(403);
        w.setContentType("application/json");
        w.getWriter().write("{\"message\":\"User: arn:aws:iam::123456789012:user/gw is not authorized to perform: "
            + "aws-mcp:InvokeMcp\"}");
        return;
      }
      chain.doFilter(cached, response);
    };
    FilterDef def = new FilterDef();
    def.setFilterName("check");
    def.setFilter(check);
    def.setAsyncSupported("true");
    ctx.addFilterDef(def);
    FilterMap map = new FilterMap();
    map.setFilterName("check");
    map.addURLPattern("/*");
    ctx.addFilterMap(map);
    tomcat.getConnector();
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();

    Path adc = Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":"
        + "\"c.apps.googleusercontent.com\",\"client_secret\":\"d-FL95Q19q7MQmFpd7hHD0Ty\","
        + "\"refresh_token\":\"1//0refresh\"}");
    Path awsConfig = Files.writeString(home.resolve("aws-config"), "[profile gw-mcp]\nregion = us-east-1\n");
    Path awsCredentials = Files.writeString(home.resolve("aws-credentials"), "[gw-mcp]\naws_access_key_id = "
        + "AKIAIOSFODNN7EXAMPLE\naws_secret_access_key = " + SECRET_KEY + "\n");
    CloudCredentials cloud = new CloudCredentials(new CloudEndpoints(java.net.URI.create("http://127.0.0.1:" + port
        + "/token"), null, adc, null, awsConfig, awsCredentials), Clock.systemUTC());
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(), HostResolver.SYSTEM));
    runtime = new McpServers(Map.of("PATH", "/usr/bin:/bin"), System.getProperty("os.name"), false,
        Clock.systemUTC(), Duration.ofMillis(200), McpServerConnector.typeManifest(), cloud);
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

  private ConnectionConfig google() {
    return Fixtures.cfg("cn_bigtable", "bigtable-admin", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp","auth":{"type":"google",
         "credentials":{"source":"adc","quotaProject":"acme-billing"}}}""".formatted(port)));
  }

  private ConnectionConfig aws() {
    return Fixtures.cfg("cn_aws", "aws-mcp", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp","auth":{"type":"aws","service":"aws-mcp",
         "region":"us-east-1","credentials":{"source":"profile","profile":"gw-mcp"}}}""".formatted(port)));
  }

  private Fixtures.Context ctx(String id) {
    GuardedEgress guard = clients.egress(EgressSpec.of(id, AddressPolicy.builder().allowPrivate("127.0.0.1/32")
        .build()));
    return new Fixtures.Context(Duration.ofSeconds(30), false, null, guard);
  }

  @Test
  void aGoogleServerGetsTheTokenOfThisComputersSignInAndTheBilledProject() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(google(), new Fixtures.Secrets(), ctx("cn_bigtable"), null);

    assertThat(h.tools()).extracting(t -> t.path("exposed").asString()).containsExactly("bigtable_list_instances");
    assertThat(bearer).isNotEmpty().allMatch("Bearer ya29.t1"::equals);
    assertThat(billed).allMatch("acme-billing"::equals);
  }

  @Test
  void aRefusedGoogleTokenIsRenewedOnceAndTheCallRunsAndIsMasked() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(google(), new Fixtures.Secrets(), ctx("cn_bigtable"), null);
    Connector c = runtime.connector(google(), new McpServerRuntime.Snapshot("bigtable_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    Fixtures.Context ctx = ctx("cn_bigtable");

    // The call's session gets a token of its own (t2), which Google revoked: only the next one is accepted.
    acceptedToken = "ya29.t3";
    String text = c.execute(Fixtures.call("bigtable_list_instances", "{}"), google(), new Fixtures.Secrets(), ctx)
        .content().getFirst().text();

    assertThat(text).isEqualTo("instance-1");
    assertThat(bearer).containsSubsequence("Bearer ya29.t2", "Bearer ya29.t3");
    assertThat(bearer.getLast()).isEqualTo("Bearer ya29.t3");
    assertThat(ctx.taints).contains("ya29.t3");
  }

  @Test
  void anAwsServerChecksTheSignatureOfEveryRequestItReceived() throws Exception {
    mode = "aws";
    McpServerRuntime.Handshake h = runtime.probe(aws(), new Fixtures.Secrets(), ctx("cn_aws"), null);
    Connector c = runtime.connector(aws(), new McpServerRuntime.Snapshot("aws_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    Fixtures.Context ctx = ctx("cn_aws");

    String text = c.execute(Fixtures.call("aws_list_instances", "{}"), aws(), new Fixtures.Secrets(), ctx).content()
        .getFirst().text();

    assertThat(text).isEqualTo("instance-1");
    assertThat(signatures).isNotEmpty().allMatch("valid"::equals);
    assertThat(ctx.taints).contains(SECRET_KEY);
  }

  @Test
  void anAwsRefusalNamesTheCredentialsAndQuotesTheServer() {
    mode = "aws";
    forbidden = true;
    assertThatThrownBy(() -> runtime.probe(aws(), new Fixtures.Secrets(), ctx("cn_aws"), null))
        .hasMessageContaining("refused GateRook's AWS credentials (HTTP 403)")
        .hasMessageContaining("is not authorized to perform: aws-mcp:InvokeMcp")
        .hasMessageNotContaining("check the headers");
  }

  @Test
  void anAwsServerOnAnotherHostNeedsItsServiceAndRegion() {
    ConnectionConfig unnamed = Fixtures.cfg("cn_aws2", "aws-mcp", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d/mcp","auth":{"type":"aws",
         "credentials":{"source":"profile","profile":"gw-mcp"}}}""".formatted(port)));
    assertThatThrownBy(() -> runtime.probe(unnamed, new Fixtures.Secrets(), ctx("cn_aws2"), null))
        .hasMessageContaining("name the AWS service and region to sign for");
  }

  /** SigV4 checked as AWS checks it, from what the server received: "valid", or why not. */
  private static String verify(Cached r) {
    String auth = r.getHeader("Authorization");
    Matcher m = auth == null ? null : SIGV4.matcher(auth);
    if (m == null || !m.matches()) {
      return "no signature";
    }
    try {
      String payload = hex(MessageDigest.getInstance("SHA-256").digest(r.body));
      if (!payload.equals(r.getHeader("x-amz-content-sha256"))) {
        return "body changed";
      }
      StringBuilder headers = new StringBuilder();
      for (String h : m.group(5).split(";")) {
        headers.append(h).append(':').append(String.valueOf(r.getHeader(h)).strip()).append('\n');
      }
      String canonical = r.getMethod() + "\n" + r.getRequestURI() + "\n" + (r.getQueryString() == null ? ""
          : r.getQueryString()) + "\n" + headers + "\n" + m.group(5) + "\n" + payload;
      String scope = m.group(2) + "/" + m.group(3) + "/" + m.group(4) + "/aws4_request";
      String toSign = "AWS4-HMAC-SHA256\n" + r.getHeader("x-amz-date") + "\n" + scope + "\n"
          + hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
      byte[] key = hmac(("AWS4" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), m.group(2));
      for (String part : List.of(m.group(3), m.group(4), "aws4_request")) {
        key = hmac(key, part);
      }
      return hex(hmac(key, toSign)).equals(m.group(6)) ? "valid" : "wrong signature";
    } catch (Exception e) {
      return e.toString();
    }
  }

  private static byte[] hmac(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  /** A request whose body was read once, for the check, and is read again by the MCP servlet. */
  private static final class Cached extends HttpServletRequestWrapper {

    final byte[] body;

    Cached(HttpServletRequest r) throws IOException {
      super(r);
      this.body = r.getInputStream().readAllBytes();
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
