package ai.msrv.gaterook.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@code gw-bridge stdio --endpoint dev --grant gr_… [--client cursor]}: the same bridge as {@code gw-bridge.mjs}, for
 * clients that start a stdio server themselves (Cursor and Antigravity "through the bridge", Claude Desktop's fallback
 * entry). Each stdin line is POSTed to the endpoint URL the broker names with a 15-minute token; one line comes back
 * on stdout per answer, written under a lock. A 401 gets one new token and one retry. Errors are answered as JSON-RPC
 * errors with the request's id and the process keeps running, so the client doesn't mark the server as failed.
 */
final class StdioBridge implements AutoCloseable {

  static final String NOT_RUNNING = "GateRook is not running. Open GateRook, then try again.";
  private static final Duration BROKER_WAIT = Duration.ofSeconds(60);
  private static final Duration REQUEST_WAIT = Duration.ofSeconds(120);
  private static final Pattern ASCII = Pattern.compile("[\\x20-\\x7e]*");
  private static final String INSTANCE_HEADER = "X-Gateway-Bridge-Instance";

  private record Token(String value, URI url, Instant expires, String instance) {}

  private final String endpoint;
  private final String grant;
  private final String client;
  private final BrokerClient broker;
  private final BridgeLog log;
  private final PrintStream out;
  private final HttpClient http;
  private volatile Token token;
  private volatile String negotiated;
  private volatile boolean listChanged;
  private volatile boolean closed;
  private final Object streamLock = new Object();
  private Thread listener;
  private InputStream notificationStream;

  StdioBridge(String endpoint, String grant, String client, BrokerClient broker, PrintStream out) {
    this.endpoint = endpoint;
    this.grant = grant;
    this.client = client;
    this.broker = broker;
    this.log = new BridgeLog(broker.home());
    this.out = out;
    this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
  }

  static int run(Map<String, String> args, Map<String, String> env, InputStream in, PrintStream out,
      PrintStream err) {
    String endpoint = args.get("endpoint");
    String grant = args.get("grant");
    String client = args.getOrDefault("client", env.getOrDefault("GW_CLIENT", "cursor"));
    if (!Main.validEndpoint(endpoint) || !Main.validGrant(grant) || !Main.validClient(client)) {
      err.println("usage: gw-bridge stdio --endpoint <slug> --grant <gr_…> [--client cursor|antigravity|claude-desktop]");
      return 3;
    }
    StdioBridge bridge = new StdioBridge(endpoint, grant, client, BrokerClient.fromEnvironment(env), out);
    try (bridge; ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String message = line;
        pool.submit(() -> bridge.handle(message));
      }
      bridge.close();
      pool.shutdown();
      pool.awaitTermination(REQUEST_WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (IOException e) {
      err.println("stdin: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return 0;
  }

  /** One message from the client. */
  void handle(String line) {
    if (line.isBlank()) {
      return;
    }
    Object msg;
    try {
      msg = Json.parse(line);
    } catch (IllegalArgumentException e) {
      write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}");
      return;
    }
    Object id = msg instanceof Map<?, ?> m && m.containsKey("id") ? m.get("id") : Absent.ID;
    String method = Json.string(msg, "method");
    try {
      Token held = token;
      Token t = token(false);
      HttpResponse<String> r;
      try {
        r = post(t, line, msg, method);
      } catch (ConnectException e) {
        // Nobody got the request. A token kept from before may name the port of a core that has since moved
        // (Settings › Local API, a restart): the broker knows the current URL, and sending again is safe.
        if (t != held) {
          token = null;
          throw e;
        }
        t = replace(held);
        r = post(t, line, msg, method);
      }
      if (r.statusCode() == 401) {
        t = replace(t);
        r = post(t, line, msg, method);
      }
      String answeredBy = r.headers().firstValue(INSTANCE_HEADER).orElse(null);
      if (t.instance() != null && !t.instance().equals(answeredBy) && !fromCurrentRun(t, answeredBy)) {
        token = null;
        reply(id, NOT_RUNNING);
        return;
      }
      if (r.statusCode() == 202) {
        if ("notifications/initialized".equals(method)) {
          listen();
        }
        return;
      }
      String type = r.headers().firstValue("Content-Type").orElse("");
      if (type.startsWith("text/event-stream")) {
        for (String data : sseData(r.body())) {
          write(data.strip());
        }
        return;
      }
      if (type.startsWith("application/json") && !r.body().isBlank()) {
        if ("initialize".equals(method)) {
          Object result = Json.get(Json.parse(r.body()), "result");
          String v = Json.string(result, "protocolVersion");
          if (v != null) {
            negotiated = v;
          }
          listChanged = Boolean.TRUE.equals(Json.get(Json.get(Json.get(result, "capabilities"), "tools"),
              "listChanged"));
        }
        write(r.body().strip());
        return;
      }
      reply(id, "GateRook answered HTTP " + r.statusCode() + ".");
    } catch (Refused e) {
      log.write("stdio", client + " on " + endpoint + " (" + grant + "): " + e.code + ": " + e.getMessage());
      reply(id, "grant_revoked".equals(e.code) ? "Access to " + endpoint + " was revoked in GateRook. Connect "
          + "again from GateRook." : e.getMessage());
    } catch (BrokerClient.Unavailable e) {
      log.write("stdio", client + " on " + endpoint + ": " + e.getMessage());
      reply(id, NOT_RUNNING);
    } catch (ConnectException e) {
      token = null;
      reply(id, NOT_RUNNING);
    } catch (IOException | RuntimeException e) {
      log.write("stdio", client + " on " + endpoint + ": " + e.getMessage());
      reply(id, "GateRook didn't answer: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** One GET stream per stdio client; POST responses keep their existing independent request lifecycle. */
  private void listen() {
    synchronized (streamLock) {
      if (!closed && listChanged && negotiated != null && listener == null) {
        listener = Thread.ofVirtual().name("mcp-notifications").start(this::receiveNotifications);
      }
    }
  }

  private void receiveNotifications() {
    long reconnectDelay = 1000;
    while (!closed && listChanged) {
      try {
        Token t = token(false);
        for (int attempt = 0; attempt < 2 && !closed; attempt++) {
          HttpRequest request = HttpRequest.newBuilder(t.url()).timeout(REQUEST_WAIT)
              .header("Accept", "text/event-stream").header("Authorization", "Bearer " + t.value())
              .header("MCP-Protocol-Version", negotiated).GET().build();
          HttpResponse<InputStream> response;
          try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
          } catch (ConnectException e) {
            if (token == t) {
              token = null;
            }
            throw e;
          }
          try (InputStream body = response.body()) {
            if (response.statusCode() == 401 && attempt == 0) {
              t = replace(t);
              continue;
            }
            if (response.statusCode() == 405) {
              listChanged = false;
            }
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream")) {
              break;
            }
            String answeredBy = response.headers().firstValue(INSTANCE_HEADER).orElse(null);
            if (t.instance() != null && !t.instance().equals(answeredBy) && !fromCurrentRun(t, answeredBy)) {
              token = null;
              break;
            }
            synchronized (streamLock) {
              if (closed) {
                return;
              }
              notificationStream = body;
            }
            reconnectDelay = 1000;
            readNotifications(body);
            break;
          } finally {
            synchronized (streamLock) {
              notificationStream = null;
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException | BrokerClient.Unavailable | Refused | RuntimeException e) {
        // Reconnect without inventing a JSON-RPC response or interrupting calls in flight.
      }
      try {
        Thread.sleep(reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, 30_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void readNotifications(InputStream body) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    StringBuilder data = new StringBuilder();
    String line;
    while (!closed && (line = reader.readLine()) != null) {
      if (data.length() + line.length() > 1024 * 1024) {
        throw new IOException("The notification is larger than 1 MiB.");
      }
      if (line.isEmpty()) {
        if (!data.isEmpty() && !closed) {
          try {
            Object message = Json.parse(data.toString());
            if (message instanceof Map<?, ?> m && "2.0".equals(m.get("jsonrpc"))
                && m.get("method") instanceof String && !m.containsKey("id")) {
              write(Json.write(message));
            }
          } catch (IllegalArgumentException e) {
            // Invalid events are never written to the client's JSON-RPC stream.
          }
        }
        data.setLength(0);
      } else if (line.startsWith("data:")) {
        if (!data.isEmpty()) {
          data.append('\n');
        }
        data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
      }
    }
  }

  @Override
  public void close() {
    synchronized (streamLock) {
      closed = true;
      if (listener != null) {
        listener.interrupt();
      }
      if (notificationStream != null) {
        try {
          notificationStream.close();
        } catch (IOException ignored) {
          // Closing stdin terminates the subscription even if the peer already disconnected.
        }
      }
    }
  }

  /**
   * Another run of the core has another id, and it may still accept a token issued before its restart. The broker
   * vouches for the run it gets tokens from now: an answer from that run goes through (it already ran there, so it
   * isn't sent again); an answer from any other program on the port goes nowhere, and that program gets no further
   * request with a token.
   */
  private boolean fromCurrentRun(Token refused, String answeredBy) {
    if (answeredBy == null) {
      return false;
    }
    try {
      Token current = replace(refused);
      return answeredBy.equals(current.instance());
    } catch (BrokerClient.Unavailable | Refused e) {
      return false;
    }
  }

  /** The broker refused: {@code code} is the protocol's error. */
  static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;
    final String code;

    Refused(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  private enum Absent { ID }

  /**
   * A token other than {@code stale}, the one just refused: messages that met the same 401 at the same moment take
   * the token the first of them got instead of one each (a grant keeps only four).
   */
  private synchronized Token replace(Token stale) throws BrokerClient.Unavailable, Refused {
    Token t = token;
    if (t != null && t != stale && Duration.between(Instant.now(), t.expires()).toSeconds() > 60) {
      return t;
    }
    return token(true);
  }

  private synchronized Token token(boolean force) throws BrokerClient.Unavailable, Refused {
    Token t = token;
    if (!force && t != null && Duration.between(Instant.now(), t.expires()).toSeconds() > 60) {
      return t;
    }
    token = null;
    Object answer = broker.token(client, endpoint, grant, BROKER_WAIT);
    if (!Boolean.TRUE.equals(Json.get(answer, "ok"))) {
      throw new Refused(String.valueOf(Json.string(answer, "error")), Optional.ofNullable(Json.string(answer,
          "message")).orElse(NOT_RUNNING));
    }
    URI url;
    try {
      url = URI.create(String.valueOf(Json.string(answer, "url")));
    } catch (IllegalArgumentException e) {
      throw new Refused("bad_url", "The broker named an endpoint URL the bridge doesn't accept.");
    }
    // Only the local core, only this endpoint: nothing can make the bridge send its token elsewhere.
    if (!"http".equals(url.getScheme()) || !"127.0.0.1".equals(url.getHost()) || url.getPort() <= 0
        || !("/mcp/" + endpoint).equals(url.getRawPath()) || url.getRawQuery() != null || url.getRawUserInfo() != null) {
      throw new Refused("bad_url", "The broker named an endpoint URL the bridge doesn't accept.");
    }
    Instant expires;
    try {
      expires = Instant.parse(String.valueOf(Json.string(answer, "expiresAt")));
    } catch (RuntimeException e) {
      expires = Instant.now().plusSeconds(60);
    }
    token = new Token(Json.string(answer, "token"), url, expires, Json.string(answer, "instance"));
    return token;
  }

  private HttpResponse<String> post(Token t, String body, Object msg, String method)
      throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(t.url()).timeout(REQUEST_WAIT)
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("Authorization", "Bearer " + t.value()).POST(HttpRequest.BodyPublishers.ofString(body));
    Object params = Json.get(msg, "params");
    String modern = Json.string(Json.get(params, "_meta"), "io.modelcontextprotocol/protocolVersion");
    if (modern != null) {
      b.header("MCP-Protocol-Version", modern);
      if (method != null) {
        b.header("Mcp-Method", method);
      }
      String name = "resources/read".equals(method) ? Json.string(params, "uri")
          : "tools/call".equals(method) || "prompts/get".equals(method) ? Json.string(params, "name") : null;
      if (name != null) {
        b.header("Mcp-Name", headerName(name));
      }
    } else if (negotiated != null && !"initialize".equals(method)) {
      b.header("MCP-Protocol-Version", negotiated);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  static String headerName(String value) {
    return ASCII.matcher(value).matches() && value.strip().equals(value) ? value
        : "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
  }

  static List<String> sseData(String body) {
    List<String> out = new ArrayList<>();
    for (String event : body.split("\\r?\\n\\r?\\n")) {
      List<String> data = new ArrayList<>();
      for (String l : event.split("\\r?\\n")) {
        if (l.startsWith("data:")) {
          data.add(l.substring(5).startsWith(" ") ? l.substring(6) : l.substring(5));
        }
      }
      if (!data.isEmpty()) {
        out.add(String.join("\n", data));
      }
    }
    return out;
  }

  private void reply(Object id, String message) {
    if (id == Absent.ID) {
      return;
    }
    write("{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(id) + ",\"error\":{\"code\":-32603,\"message\":"
        + Json.quote(message) + "}}");
  }

  private void write(String line) {
    synchronized (out) {
      out.print(line);
      out.print('\n');
      out.flush();
    }
  }
}
