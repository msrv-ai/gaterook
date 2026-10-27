package ai.msrv.gaterook.core.tunnel;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.core.tunnel.internal.FakeAgents;
import ai.msrv.gaterook.core.tunnel.internal.TunnelProbe;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A whole core with its public listener, the fake tunnel agents pinned by path, and the world around it
 * (ARCHITECTURE.md §10 K3): the provider's edge played by the fake agent, a "claude.ai" that serves its Client ID
 * Metadata Document over HTTPS from a CA only this core trusts, and a probe for the self-test that reaches the fake
 * edge from "this computer" ({@code 198.51.100.7}, outside Anthropic's ranges — only the self-test's nonce lets it
 * past the Callers gate).
 */
public final class TunnelWorld implements AutoCloseable {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  /** This computer's own public address as the edge sees the self-test. */
  public static final String OWN_ADDRESS = "198.51.100.7";

  /** Where a person's browser is; outside Anthropic's ranges (the authorize page isn't gated). */
  public static final String BROWSER = "203.0.113.24";

  /** claude.ai's servers: inside {@code 160.79.104.0/21}. */
  public static final String CLAUDE = "160.79.104.10";

  private static final List<FakeAgents.Agent> AGENTS = new CopyOnWriteArrayList<>();

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("claude.ai stand-in CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("localhost")
      .addSubjectAlternativeName("localhost").signedBy(CA).build();

  private final ConfigurableApplicationContext context;
  private final Path home;
  private final int publicPort;
  private final FakeAgents.Agent cloudflared;
  private final FakeAgents.Agent ngrok;
  private final HttpsServer claude;
  private final String clientId;
  private final AdminMvc admin;
  private volatile String lastHost;
  private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

  private TunnelWorld(ConfigurableApplicationContext context, Path home, int publicPort, FakeAgents.Agent cloudflared,
      FakeAgents.Agent ngrok, HttpsServer claude, String clientId) {
    this.context = context;
    this.home = home;
    this.publicPort = publicPort;
    this.cloudflared = cloudflared;
    this.ngrok = ngrok;
    this.claude = claude;
    this.clientId = clientId;
    this.admin = new AdminMvc(context, context.getBean(LocalListener.class).port());
  }

  /** How far the core's clock runs ahead of the real one: what lets a test see a sign-in expire. */
  private static final java.util.concurrent.atomic.AtomicReference<Duration> OFFSET =
      new java.util.concurrent.atomic.AtomicReference<>(Duration.ZERO);

  /**
   * The self-test's way to the public URL: DNS says a Cloudflare address, HTTPS goes to the fake edge. A source of
   * {@link #start}'s context only: as a {@code @Configuration} under {@code ai.msrv.gaterook.core} the component scan of
   * every other test core picked it up, and its clock, which ignored {@code withZone}, put their local dates in UTC
   * (ClientsIT's backup names failed after midnight). Its {@code @Bean} methods work without the annotation.
   */
  static class Probe {

    /**
     * The core's clock, shifted by {@link #shift}. Admin requests are signed with the real time, so a test makes them
     * only while the shift is zero.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock shiftedClock() {
      return shifted(java.time.ZoneOffset.UTC);
    }

    /** The system clock moved by {@link #OFFSET}, in {@code zone}: {@code withZone} keeps the shift. */
    private static java.time.Clock shifted(java.time.ZoneId zone) {
      return new java.time.Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId other) {
          return shifted(other);
        }

        @Override
        public java.time.Instant instant() {
          return java.time.Instant.now().plus(OFFSET.get());
        }
      };
    }

    @Bean
    TunnelProbe testTunnelProbe() {
      HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
          .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
      return new TunnelProbe() {
        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
          if (edgeOf(host) < 0) {
            throw new java.net.UnknownHostException(host);
          }
          return List.of(InetAddress.ofLiteral("104.16.230.132"));
        }

        @Override
        public Answer send(String method, URI uri, Map<String, String> headers, String body) throws IOException,
            InterruptedException {
          int edge = edgeOf(uri.getHost());
          if (edge < 0) {
            throw new IOException("no route to " + uri.getHost());
          }
          HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + edge + uri.getRawPath()))
              .timeout(Duration.ofSeconds(10)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                  : HttpRequest.BodyPublishers.ofString(body));
          headers.forEach(rq::header);
          rq.header("X-Fake-Client-IP", OWN_ADDRESS);
          long start = System.nanoTime();
          HttpResponse<String> rs = client.send(rq.build(), HttpResponse.BodyHandlers.ofString());
          Map<String, List<String>> h = new TreeMap<>();
          rs.headers().map().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
          return new Answer(rs.statusCode(), h, rs.body(), Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
      };
    }
  }

  /** The edge port of the running fake agent that serves {@code host}, or -1. */
  static int edgeOf(String host) {
    for (FakeAgents.Agent a : AGENTS) {
      if (Files.isRegularFile(a.reportFile())) {
        Properties p = a.report();
        if (host.equalsIgnoreCase(p.getProperty("hostname")) && FakeAgents.alive(Long.parseLong(
            p.getProperty("pid")))) {
          return Integer.parseInt(p.getProperty("edgePort"));
        }
      }
    }
    return -1;
  }

  public static TunnelWorld start(String name, String... extraArgs) {
    try {
      Path home = TestGateway.newHome(name);
      Path bin = home.resolveSibling(home.getFileName() + "-agents");
      FakeAgents.Agent cloudflared = FakeAgents.cloudflared(bin);
      FakeAgents.Agent ngrok = FakeAgents.ngrok(bin);
      AGENTS.add(cloudflared);
      AGENTS.add(ngrok);
      Path pins = FakeAgents.pins(bin, cloudflared, ngrok, null);
      Path ca = bin.resolve("claude-ca.pem");
      Files.writeString(ca, CA.certificatePem());

      HttpsServer claude = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      claude.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(LEAF)
          .build().sslContext()));
      String clientId = "https://localhost:" + claude.getAddress().getPort() + "/oauth/mcp-oauth-client-metadata";
      byte[] document = ("{\"client_id\":\"" + clientId + "\",\"client_name\":\"Claude\",\"client_uri\":"
          + "\"https://claude.ai\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"],\"grant_types\":"
          + "[\"authorization_code\",\"refresh_token\"],\"response_types\":[\"code\"],"
          + "\"token_endpoint_auth_method\":\"none\"}").getBytes(StandardCharsets.UTF_8);
      claude.createContext("/oauth/mcp-oauth-client-metadata", ex -> {
        try (ex) {
          ex.getResponseHeaders().set("Content-Type", "application/json");
          ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
          ex.sendResponseHeaders(200, document.length);
          ex.getResponseBody().write(document);
        }
      });
      claude.start();

      int publicPort = freePort();
      List<String> args = new ArrayList<>(List.of("--gateway.home=" + home, "--gateway.dev=true",
          "--gateway.dev.launch-secret=" + TestGateway.LAUNCH_SECRET, "--server.port=0",
          "--gateway.connections.health-interval=0s", "--gateway.public-listener=true",
          "--gateway.public-port=" + publicPort, "--gateway.test.tunnel-pins=" + pins,
          "--gateway.test.oauth.client-hosts=localhost", "--gateway.test.oauth.cimd-ca=" + ca));
      args.addAll(List.of(extraArgs));
      ConfigurableApplicationContext context = new SpringApplicationBuilder(GatewayApplication.class)
          .sources(FakeConnectors.class, Probe.class).contextFactory(GatewayLauncher.contexts(true))
          .run(args.toArray(String[]::new));
      return new TunnelWorld(context, home, publicPort, cloudflared, ngrok, claude, clientId);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int freePort() {
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── The core ────────────────────────────────────────────────────────────────────────────────────────────────

  /** Runs the core's clock {@code ahead} of real time (zero again before the next admin request). */
  public void shift(Duration ahead) {
    OFFSET.set(ahead);
  }

  public <T> T bean(Class<T> type) {
    return context.getBean(type);
  }

  public ConfigurableApplicationContext context() {
    return context;
  }

  public Path home() {
    return home;
  }

  public AdminMvc admin() {
    return admin;
  }

  public int localPort() {
    return bean(LocalListener.class).port();
  }

  public int publicPort() {
    return publicPort;
  }

  public FakeAgents.Agent cloudflared() {
    return cloudflared;
  }

  public FakeAgents.Agent ngrok() {
    return ngrok;
  }

  /** The Client ID Metadata Document URL of the stand-in claude.ai. */
  public String clientId() {
    return clientId;
  }

  /** A dev endpoint {@code slug} on its own fake Postgres, ready to publish. */
  public void endpoint(String slug, Env env) {
    String connection = slug + "-db";
    bean(ConnectionService.class).create(connection, "pgfake", env, TestCore.config("db." + slug + ".internal"),
        Map.of("/credentials/password", SecretInput.local(("pw-" + connection + "-0123").toCharArray())), "test");
    bean(EndpointService.class).create(slug, null, EndpointService.StartFrom.EMPTY, null);
    bean(EndpointConnections.class).add(slug, connection, null);
  }

  /** {@code POST /api/tunnel/publish} as the UI sends it after the confirmation; waits until it is on. */
  public JsonNode publish(String slug) {
    AdminMvc.Answer a = admin.post("/api/tunnel/publish", JsonNodeFactory.instance.objectNode().put("endpoint", slug));
    if (a.status() != 202) {
      throw new AssertionError("publish " + slug + ": " + a.status() + " " + a.body());
    }
    await(() -> "on".equals(state().path("state").asString()) || "error".equals(state().path("state").asString()),
        "the tunnel to come up");
    JsonNode s = state();
    if (!"on".equals(s.path("state").asString())) {
      throw new AssertionError("The tunnel didn't come up: " + s);
    }
    return s;
  }

  public JsonNode state() {
    JsonNode s = admin.ok("/api/tunnel");
    if (!s.path("host").isNull()) {
      lastHost = s.path("host").asString();
    }
    return s;
  }

  public String host() {
    return state().path("host").asString();
  }

  // ── Through the edge ────────────────────────────────────────────────────────────────────────────────────────

  /** One answer through the edge: status, headers by lower-case name, body. */
  public record Answer(int status, Map<String, List<String>> headers, String body) {

    public String header(String name) {
      List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
      return v == null ? null : v.getFirst();
    }

    public JsonNode json() {
      return body == null || body.isBlank() ? JsonNodeFactory.instance.missingNode() : JSON.readTree(body);
    }
  }

  /** {@code method path} through the running agent's edge, as {@code caller} (an IPv4 address). */
  public Answer edge(String caller, String method, String pathAndQuery, Map<String, String> headers, String body) {
    // The host the tunnel last reported: a request through the edge needs no admin call (the clock may be shifted).
    String host = lastHost != null ? lastHost : host();
    int port = edgeOf(host);
    if (port < 0) {
      throw new AssertionError("No agent serves " + host);
    }
    return send(URI.create("http://127.0.0.1:" + port + pathAndQuery), caller, method, headers, body);
  }

  /** Straight to a port of the core, bypassing the edge: what another program on this computer could do. */
  public Answer direct(int port, String hostHeader, String method, String pathAndQuery, Map<String, String> headers,
      String body) {
    // java.net.http won't send a Host of our choosing; a raw HTTP/1.1 request will.
    try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
      s.setSoTimeout(10_000);
      StringBuilder rq = new StringBuilder(method + " " + pathAndQuery + " HTTP/1.1\r\nHost: " + hostHeader
          + "\r\nConnection: close\r\n");
      headers.forEach((k, v) -> rq.append(k).append(": ").append(v).append("\r\n"));
      byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
      if (body != null) {
        rq.append("Content-Length: ").append(b.length).append("\r\n");
      }
      rq.append("\r\n");
      s.getOutputStream().write(rq.toString().getBytes(StandardCharsets.UTF_8));
      s.getOutputStream().write(b);
      s.getOutputStream().flush();
      String raw = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int end = raw.indexOf("\r\n\r\n");
      String[] lines = raw.substring(0, end).split("\r\n");
      int status = Integer.parseInt(lines[0].split(" ")[1]);
      Map<String, List<String>> h = new LinkedHashMap<>();
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        h.computeIfAbsent(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
            .add(lines[i].substring(colon + 1).strip());
      }
      String rest = raw.substring(end + 4);
      if ("chunked".equalsIgnoreCase(h.getOrDefault("transfer-encoding", List.of("")).getFirst())) {
        rest = dechunk(rest);
      }
      return new Answer(status, h, rest);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String dechunk(String s) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      int eol = s.indexOf("\r\n", i);
      int size = Integer.parseInt(s.substring(i, eol).split(";")[0].strip(), 16);
      if (size == 0) {
        break;
      }
      out.append(s, eol + 2, eol + 2 + size);
      i = eol + 2 + size + 2;
    }
    return out.toString();
  }

  /** Over the local listener, as Cursor or a browser on this computer would. */
  public Answer local(String method, String pathAndQuery, Map<String, String> headers, String body) {
    return send(URI.create("http://127.0.0.1:" + localPort() + pathAndQuery), null, method, headers, body);
  }

  private Answer send(URI uri, String caller, String method, Map<String, String> headers, String body) {
    HttpRequest.Builder rq = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(rq::header);
    if (caller != null) {
      rq.header("X-Fake-Client-IP", caller);
    }
    try {
      HttpResponse<String> rs = http.send(rq.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, List<String>> h = new TreeMap<>();
      rs.headers().map().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
      return new Answer(rs.statusCode(), h, rs.body());
    } catch (IOException e) {
      throw new IllegalStateException(method + " " + uri + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public static void await(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + 45_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public void close() {
    OFFSET.set(Duration.ZERO);
    try {
      context.close();
    } finally {
      claude.stop(0);
      AGENTS.remove(cloudflared);
      AGENTS.remove(ngrok);
    }
  }
}
