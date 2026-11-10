package ai.msrv.gaterook.core.mcp.internal.web;

import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.mcp.internal.Era;
import ai.msrv.gaterook.core.mcp.internal.EraResolver;
import ai.msrv.gaterook.core.mcp.internal.JsonRpc;
import ai.msrv.gaterook.core.connectors.ConnectionNotified;
import ai.msrv.gaterook.core.mcp.internal.Listening;
import ai.msrv.gaterook.core.mcp.internal.McpToolNotifications;
import ai.msrv.gaterook.core.mcp.internal.PromptsHandler;
import ai.msrv.gaterook.core.mcp.internal.ResourceSubscriptions;
import ai.msrv.gaterook.core.mcp.internal.ResourcesHandler;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CallerAuthenticator;
import ai.msrv.gaterook.core.platform.Ingress;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /mcp/{slug}} (ARCHITECTURE.md §5 steps 1–2, report 01 §3.1): one JSON-RPC object per request, answered
 * with {@code application/json}. Legacy clients can open an authenticated GET SSE stream for tool-list changes.
 * No session IDs or replay IDs are issued. Before any JSON-RPC: a bearer token of this endpoint and ingress (401 with
 * {@code WWW-Authenticate: Bearer realm="gaterook"}; nothing of an anonymous request's body is read or held), only
 * {@code application/json} (415, which also keeps cross-site forms out), a body of at most 1 MiB (413) and within the
 * principal's hourly budget ({@link RequestBudget}, 429), then one object (a batch is {@code -32600}). Other methods
 * are 405 with {@code Allow: GET, POST}. Host and Origin were checked already: by {@code LocalGuardFilter} on the
 * local port, by {@code PublicIngressFilter} on the public one.
 */
@RestController
class McpHttpController {

  static final int MAX_BODY = 1 << 20;
  /** Notifications of one call waiting for the client; more are dropped (progress says the same again later). */
  static final int STREAMED = 256;
  /** Calls that may stream run here, while the request's thread writes what they say. */
  private static final ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
      .name("mcp-call-", 0).factory());

  private final McpGateway gateway;
  private final CallerAuthenticator authenticator;
  private final Bootstrap bootstrap;
  private final JsonMapper json;
  private final EndpointSnapshotService snapshots;
  private final ToolCatalog catalogs;
  private final McpToolNotifications notifications;
  private final ResourcesHandler resources;
  private final PromptsHandler prompts;
  private final ResourceSubscriptions subscriptions;
  private final RequestBudget budget;
  private final Duration heartbeat;

  McpHttpController(McpGateway gateway, CallerAuthenticator authenticator, Bootstrap bootstrap, JsonMapper json,
      EndpointSnapshotService snapshots, ToolCatalog catalogs, McpToolNotifications notifications,
      ResourcesHandler resources, PromptsHandler prompts, ResourceSubscriptions subscriptions, RequestBudget budget,
      @Value("${gateway.mcp.heartbeat:15s}") Duration heartbeat) {
    this.gateway = gateway;
    this.authenticator = authenticator;
    this.bootstrap = bootstrap;
    this.json = json;
    this.snapshots = snapshots;
    this.catalogs = catalogs;
    this.notifications = notifications;
    this.resources = resources;
    this.prompts = prompts;
    this.subscriptions = subscriptions;
    this.budget = budget;
    this.heartbeat = heartbeat;
  }

  @PostMapping("/mcp/{slug}")
  ResponseEntity<byte[]> post(@PathVariable String slug, HttpServletRequest http,
      HttpServletResponse servletResponse) throws IOException {
    Ingress ingress = ingressOf(http);
    // Nothing is answered or read before the token. Through the tunnel claude.ai starts its sign-in on a 401 only,
    // and an anonymous caller learns nothing about the endpoint, not even which bodies it takes (report 02 §3); on
    // either ingress, an anonymous request's body never takes the gateway's memory.
    Optional<Caller> caller = authenticate(ingress, slug, http);
    if (caller.isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    String type = http.getContentType();
    if (type == null || !isJson(type)) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }
    long declared = http.getContentLengthLong();
    if (declared > MAX_BODY) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    if (declared > 0 && !budget.charge(caller.get().principalId(), declared)) {
      return overBudget();
    }
    byte[] body;
    try (InputStream in = http.getInputStream()) {
      body = in.readNBytes(MAX_BODY + 1);
    }
    if (body.length > MAX_BODY) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    // A chunked body declares no length: counted once it is read, and what the declaration undercounted too.
    if (body.length > Math.max(0, declared) && !budget.charge(caller.get().principalId(),
        body.length - Math.max(0, declared))) {
      return overBudget();
    }
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException e) {
      return write(400, McpGateway.error(JsonRpc.PARSE_ERROR, "Parse error"));
    }
    if (root == null || root.isArray()) {
      return write(400, McpGateway.error(JsonRpc.INVALID_REQUEST, "Batch requests are not supported"));
    }
    if (!root.isObject()) {
      return write(400, McpGateway.error(JsonRpc.INVALID_REQUEST, "A request is one JSON object"));
    }
    if ("subscriptions/listen".equals(root.path("method").asString(null))) {
      return listen(slug, caller.get(), (ObjectNode) root, ingress, http, servletResponse);
    }
    if ("tools/call".equals(root.path("method").asString(null)) && acceptsEvents(http.getHeader(HttpHeaders.ACCEPT))) {
      return streaming(slug, caller.get(), (ObjectNode) root, http, servletResponse);
    }
    McpGateway.Response response = gateway.handle(slug, caller.get(), (ObjectNode) root, http::getHeader);
    if (response.body() == null) {
      return ResponseEntity.status(response.status()).build();
    }
    return write(response.status(), response.body());
  }

  /**
   * A call whose client takes an event stream: it runs on a thread of its own, and the answer stays JSON unless the
   * call says something first (its progress, a log message); then the POST is answered with an event stream that
   * carries those and ends with the answer. A client that closes the stream of a 2026-07-28 request cancels it.
   */
  private ResponseEntity<byte[]> streaming(String slug, Caller caller, ObjectNode request, HttpServletRequest http,
      HttpServletResponse response) throws IOException {
    // The worker reads the headers while this thread may write: a copy, not the container's request.
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String name : java.util.Collections.list(http.getHeaderNames())) {
      headers.put(name, http.getHeader(name));
    }
    boolean modern = request.path("params").path("_meta").path(EraResolver.META_VERSION).isString();
    // The call's notifications and, last, its answer: the request's thread wakes for either at once.
    LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    AtomicInteger waiting = new AtomicInteger();
    AtomicBoolean gone = new AtomicBoolean();
    List<Runnable> onClose = new java.util.concurrent.CopyOnWriteArrayList<>();
    McpGateway.Stream stream = new McpGateway.Stream() {
      @Override
      public boolean send(ObjectNode notification) {
        if (gone.get() || waiting.incrementAndGet() > STREAMED) {
          waiting.decrementAndGet();
          return false;
        }
        return queue.offer(notification);
      }

      @Override
      public void onClose(Runnable action) {
        if (modern) {
          onClose.add(action);
        }
      }
    };
    CompletableFuture.supplyAsync(() -> gateway.handle(slug, caller, request, headers::get, stream), WORKERS)
        .whenComplete((done, failure) -> queue.add(new Answered(done, failure)));
    ServletOutputStream out = null;
    try {
      while (true) {
        Object next = queue.take();
        if (next instanceof Answered a) {
          if (a.failure() != null) {
            LoggerFactory.getLogger(McpHttpController.class).error("A call on {} failed", slug, a.failure());
            if (out == null) {
              return write(500, McpGateway.error(JsonRpc.INTERNAL_ERROR, "Internal error in GateRook. See its log."));
            }
            return null;
          }
          McpGateway.Response done = a.response();
          if (out == null) {
            return done.body() == null ? ResponseEntity.status(done.status()).build()
                : write(done.status(), done.body());
          }
          if (!gone.get() && done.body() != null) {
            writeFrame(out, event(done.body()));
          }
          return null;
        }
        waiting.decrementAndGet();
        if (out == null) {
          out = openStream(response);
        }
        if (!gone.get()) {
          try {
            writeFrame(out, event((ObjectNode) next));
          } catch (IOException e) {
            // The client went away: over 2026-07-28 that cancels the call.
            gone.set(true);
            onClose.forEach(Runnable::run);
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // The client went away with the answer.
    }
    return null;
  }

  /** What the call came to: its answer, or what went wrong inside the core. */
  private record Answered(McpGateway.Response response, Throwable failure) {}

  private static ServletOutputStream openStream(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader("X-Accel-Buffering", "no");
    return response.getOutputStream();
  }

  private String event(ObjectNode message) {
    return "event: message\ndata: " + json.writeValueAsString(message) + "\n\n";
  }

  /**
   * An independent Streamable HTTP notification stream, written on Tomcat's virtual request thread. Revalidate
   * access before each notification and heartbeat, so expired credentials close the stream; user pauses retain discovery.
   */
  @GetMapping("/mcp/{slug}")
  ResponseEntity<byte[]> get(@PathVariable String slug, HttpServletRequest http, HttpServletResponse response)
      throws IOException {
    Ingress ingress = ingressOf(http);
    Optional<Caller> caller = authenticate(ingress, slug, http);
    if (caller.isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    String version = Optional.ofNullable(http.getHeader("MCP-Protocol-Version")).orElse("2025-03-26");
    if (Era.MODERN_VERSION.equals(version)) {
      return methodNotAllowed();
    }
    if (!EraResolver.LEGACY.contains(version)) {
      return write(400, McpGateway.error(JsonRpc.INVALID_REQUEST, "Unsupported MCP-Protocol-Version " + version));
    }
    if (!acceptsEvents(http.getHeader(HttpHeaders.ACCEPT))) {
      return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }
    Optional<EndpointSnapshot> initial = snapshots.bySlug(slug);
    if (initial.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (!legacyEnabled(initial.get())) {
      return methodNotAllowed();
    }
    // The client may have cached a list before connecting, or missed changes while disconnected. Invalidate that
    // unknown view once on every new stream, then notify only when the published catalog actually changes.
    String listHash = null;
    McpToolNotifications.Opened opened = notifications.open(initial.get().id(), caller.get().principalId(),
        streamClient(http));
    if (opened.subscription() == null) {
      return write(429, McpGateway.error(JsonRpc.OVER_BUDGET, opened.refusal()));
    }
    try (McpToolNotifications.Subscription subscription = opened.subscription()) {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      response.setHeader("X-Accel-Buffering", "no");
      ServletOutputStream out = response.getOutputStream();
      writeFrame(out, ": connected\n\n");
      String resourceHash = resources.offerHash(initial.get());
      String promptHash = prompts.offerHash(initial.get());
      String owner = ResourceSubscriptions.legacy(caller.get().principalId());
      while (!subscription.isClosed()) {
        McpToolNotifications.Wake wake = subscription.await(heartbeat);
        if (subscription.isClosed() || authenticate(ingress, slug, http).isEmpty()) {
          break;
        }
        Optional<EndpointSnapshot> current = snapshots.get(initial.get().id())
            .filter(s -> s.slug().equals(slug) && legacyEnabled(s));
        if (current.isEmpty()) {
          break;
        }
        if (wake.catalog()) {
          String nextHash = catalogs.forEndpoint(current.get()).listHash();
          if (!nextHash.equals(listHash)) {
            writeFrame(out, event(notice("notifications/tools/list_changed", null)));
            listHash = nextHash;
          }
          String nextResources = resources.offerHash(current.get());
          if (!nextResources.equals(resourceHash)) {
            writeFrame(out, event(notice("notifications/resources/list_changed", null)));
            resourceHash = nextResources;
          }
          String nextPrompts = prompts.offerHash(current.get());
          if (!nextPrompts.equals(promptHash)) {
            writeFrame(out, event(notice("notifications/prompts/list_changed", null)));
            promptHash = nextPrompts;
          }
        }
        for (ObjectNode n : said(wake, current.get(), owner, true, true)) {
          writeFrame(out, event(n));
        }
        if (!wake.any()) {
          writeFrame(out, ": keepalive\n\n");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // A disconnected client ends this subscription without affecting other clients or configuration changes.
    }
    return null;
  }

  /**
   * What the connections of {@code snapshot} said, as notifications: their changed lists (when the client wants them)
   * and the updates of resources {@code owner} subscribed to. Connections of other endpoints are none of its business.
   */
  private List<ObjectNode> said(McpToolNotifications.Wake wake, EndpointSnapshot snapshot, Object owner,
      boolean resourceLists, boolean promptLists) {
    List<ObjectNode> out = new java.util.ArrayList<>();
    for (ConnectionNotified n : wake.notices()) {
      if (snapshot.connections().stream().noneMatch(w -> w.config().id().equals(n.connectionId()))) {
        continue;
      }
      switch (n.kind()) {
        case RESOURCES_CHANGED -> {
          if (resourceLists) {
            out.add(notice("notifications/resources/list_changed", null));
          }
        }
        case PROMPTS_CHANGED -> {
          if (promptLists) {
            out.add(notice("notifications/prompts/list_changed", null));
          }
        }
        case RESOURCE_UPDATED -> {
          if (n.uri() != null && subscriptions.has(owner, n.uri())) {
            out.add(notice("notifications/resources/updated", json.createObjectNode().put("uri", n.uri())));
          }
        }
      }
    }
    return out;
  }

  private ObjectNode notice(String method, ObjectNode params) {
    ObjectNode n = json.createObjectNode();
    n.put("jsonrpc", "2.0");
    n.put("method", method);
    if (params != null) {
      n.set("params", params);
    }
    return n;
  }

  /**
   * {@code subscriptions/listen} of 2026-07-28 (ARCHITECTURE.md §5.5): the POST is answered with an event stream that
   * acknowledges what it honors, then carries what the client opted in to — the tool, resource and prompt lists when
   * they change, the updates of the resources it subscribed to — each with the stream's id, and keepalives. Access is
   * checked again before each, as on the GET stream; when the gateway ends the stream (it stops, the endpoint goes, a
   * newer stream of the principal takes its place), the request's answer closes it.
   */
  private ResponseEntity<byte[]> listen(String slug, Caller caller, ObjectNode request, Ingress ingress,
      HttpServletRequest http, HttpServletResponse response) throws IOException {
    Optional<EndpointSnapshot> initial = snapshots.bySlug(slug);
    Listening.Outcome o = Listening.check(request, http::getHeader, initial, resources);
    if (!o.accepted()) {
      return write(o.status(), o.error());
    }
    if (!acceptsEvents(http.getHeader(HttpHeaders.ACCEPT))) {
      return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }
    EndpointSnapshot first = initial.orElseThrow();
    McpToolNotifications.Opened opened = notifications.open(first.id(), caller.principalId(), streamClient(http));
    if (opened.subscription() == null) {
      return write(429, McpGateway.error(JsonRpc.OVER_BUDGET, opened.refusal()));
    }
    McpToolNotifications.Subscription subscription = opened.subscription();
    try (subscription) {
      for (String uri : o.resources()) {
        subscriptions.add(subscription, first.id(), uri);
      }
      ServletOutputStream out = openStream(response);
      ObjectNode ack = json.createObjectNode();
      ack.set("notifications", o.honored());
      writeFrame(out, event(Listening.notification("notifications/subscriptions/acknowledged", ack, o.id())));
      String listHash = catalogs.forEndpoint(first).listHash();
      String resourceHash = resources.offerHash(first);
      String promptHash = prompts.offerHash(first);
      boolean ended = false;
      while (true) {
        McpToolNotifications.Wake wake = subscription.await(heartbeat);
        if (subscription.isClosed()) {
          ended = true;
          break;
        }
        if (authenticate(ingress, slug, http).isEmpty()) {
          break;
        }
        Optional<EndpointSnapshot> current = snapshots.get(first.id()).filter(x -> x.slug().equals(slug));
        if (current.isEmpty()) {
          ended = true;
          break;
        }
        List<ObjectNode> tell = new java.util.ArrayList<>();
        if (wake.catalog()) {
          String nextHash = catalogs.forEndpoint(current.get()).listHash();
          if (!nextHash.equals(listHash) && o.wants("toolsListChanged")) {
            tell.add(notice("notifications/tools/list_changed", null));
          }
          listHash = nextHash;
          String nextResources = resources.offerHash(current.get());
          if (!nextResources.equals(resourceHash) && o.wants("resourcesListChanged")) {
            tell.add(notice("notifications/resources/list_changed", null));
          }
          resourceHash = nextResources;
          String nextPrompts = prompts.offerHash(current.get());
          if (!nextPrompts.equals(promptHash) && o.wants("promptsListChanged")) {
            tell.add(notice("notifications/prompts/list_changed", null));
          }
          promptHash = nextPrompts;
        }
        tell.addAll(said(wake, current.get(), subscription, o.wants("resourcesListChanged"),
            o.wants("promptsListChanged")));
        for (ObjectNode n : tell) {
          ObjectNode params = n.get("params") instanceof ObjectNode x ? x : json.createObjectNode();
          writeFrame(out, event(Listening.notification(n.get("method").asString(), params, o.id())));
        }
        if (!wake.any()) {
          writeFrame(out, ": keepalive\n\n");
        }
      }
      if (ended) {
        writeFrame(out, event(Listening.ended(o.id())));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // The client closed its stream: that ends the subscription.
    } finally {
      subscriptions.forget(subscription);
    }
    return null;
  }

  private static boolean legacyEnabled(EndpointSnapshot snapshot) {
    JsonNode revisions = snapshot.settings().path("protocol").path("revisions");
    if (revisions.isEmpty()) {
      return true;
    }
    for (JsonNode revision : revisions) {
      if (Era.LEGACY_VERSION.equals(revision.asString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean acceptsEvents(String accept) {
    try {
      return accept != null && MediaType.parseMediaTypes(accept).stream()
          .anyMatch(t -> t.getQualityValue() > 0 && t.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static void writeFrame(ServletOutputStream out, String frame) throws IOException {
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * Sessions and preflights don't exist here. Through the tunnel every method without a valid token is
   * 401 first, so that a client probing with GET or HEAD is sent to sign in as well (report 02 §3).
   */
  @RequestMapping(value = "/mcp/{slug}", method = {RequestMethod.DELETE, RequestMethod.OPTIONS,
      RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.HEAD})
  ResponseEntity<byte[]> notStreaming(@PathVariable String slug, HttpServletRequest http) {
    Ingress ingress = ingressOf(http);
    if (ingress == Ingress.TUNNEL && authenticate(ingress, slug, http).isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    return methodNotAllowed();
  }

  private ResponseEntity<byte[]> overBudget() {
    return write(429, McpGateway.error(JsonRpc.OVER_BUDGET, String.format(java.util.Locale.ROOT, "This token or "
        + "grant has sent %,d MiB of requests this hour, its budget. Try again later.", budget.bytesPerHour() >> 20)));
  }

  private static ResponseEntity<byte[]> methodNotAllowed() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "GET, POST").build();
  }

  private Ingress ingressOf(HttpServletRequest http) {
    return http.getLocalPort() == bootstrap.publicPort() ? Ingress.TUNNEL : Ingress.LOCAL;
  }

  private Optional<Caller> authenticate(Ingress ingress, String slug, HttpServletRequest http) {
    return bearer(http.getHeader(HttpHeaders.AUTHORIZATION)).flatMap(token -> authenticator.authenticate(
        new Authenticator.Request(ingress, slug, token, http.getRemoteAddr())));
  }

  /**
   * 401 with the challenge of the ingress: through the tunnel (and for a local endpoint that signs in with OAuth) it
   * names the protected resource metadata, so the client knows where to sign in.
   */
  private ResponseEntity<byte[]> unauthorized(Ingress ingress, String slug, HttpServletRequest http) {
    boolean presented = bearer(http.getHeader(HttpHeaders.AUTHORIZATION)).isPresent();
    ResponseEntity.BodyBuilder answer = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, authenticator.challenge(ingress, slug, presented))
        .header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (ingress != Ingress.TUNNEL || "HEAD".equals(http.getMethod())) {
      return answer.build();
    }
    ObjectNode error = json.createObjectNode().put("error", "invalid_token")
        .put("error_description", presented ? "The access token is not valid for this endpoint"
            : "Sign in to use this endpoint");
    return answer.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(error));
  }

  private ResponseEntity<byte[]> write(int status, ObjectNode body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(json.writeValueAsBytes(body));
  }

  /**
   * Which of a principal's clients opens a stream, as far as its credential tells: a digest of the presented token.
   * Each bridge process has a token of its own; clients that share a named token or a sign-in look alike. The digest
   * stays in memory with the stream and is never logged.
   */
  static String streamClient(HttpServletRequest http) {
    return bearer(http.getHeader(HttpHeaders.AUTHORIZATION)).map(token -> {
      try {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest, 0, 16);
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }).orElse(null);
  }

  /** {@code Bearer <token>}; {@code ?access_token=} is never read. */
  static Optional<String> bearer(String authorization) {
    if (authorization == null || authorization.length() < 8
        || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = authorization.substring(7).strip();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  private static boolean isJson(String contentType) {
    String t = contentType.toLowerCase(Locale.ROOT).strip();
    return t.equals("application/json") || t.startsWith("application/json;");
  }
}
