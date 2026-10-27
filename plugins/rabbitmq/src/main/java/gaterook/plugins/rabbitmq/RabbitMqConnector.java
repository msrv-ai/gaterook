package gaterook.plugins.rabbitmq;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * RabbitMQ for agents (catalog plugin {@code rabbitmq} 0.3.0, listed unsigned, step K6): queue depth, consumers and
 * dead letters of one virtual host over the management API, and publishing a message, which waits for a person's
 * approval in the gateway and runs here only with {@code ctx.approved()}. Every tool stays in the connection's virtual
 * host; nothing here takes a message off a queue.
 *
 * <p>Answers of RabbitMQ about the request (not found, no permission) are results the agent reads ({@code isError});
 * trouble reaching RabbitMQ is an exception the gateway counts against the connection.
 */
public final class RabbitMqConnector implements Connector {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  /** The most queues {@code rabbitmq_dead_letters} looks at (four pages of the management API). */
  static final int MAX_QUEUES = 2000;
  private static final int PAGE = 500;
  private static final Pattern NAME = Pattern.compile("^[^\\x00-\\x1f]{0,255}$");

  private final ConnectorManifest manifest;

  /** The plugin host's way in: the manifest of this JAR. */
  public RabbitMqConnector() {
    this(Manifests.own());
  }

  /** The release described by {@code manifest} (tests). */
  RabbitMqConnector(JsonNode manifest) {
    this.manifest = Manifests.read(manifest);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return 4;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Optional<ToolDefinition> t = tool(call.tool());
    if (t.isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of RabbitMQ "
          + manifest.version() + ".", null);
    }
    return t.get().nature() == ToolDefinition.Nature.READ ? Classification.read()
        : Classification.mutation("rabbitmq_publish puts a message on the broker.");
  }

  /** The request exactly as it goes to the management API; no I/O, no secret. */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    RabbitConfig config;
    try {
      config = RabbitConfig.of(cfg);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), cfg.name(),
          call.tool() + " on " + cfg.name());
    }
    String target = cfg.name() + " · " + config.host() + " · vhost " + config.vhost();
    String vhost = config.vhostSegment();
    return switch (call.tool()) {
      case "rabbitmq_publish" -> {
        String exchange = exchange(call);
        ObjectNode body = publishBody(call);
        yield http("POST", config, "/api/exchanges/" + vhost + "/" + RabbitConfig.segment(exchange) + "/publish",
            body, target, "Publish to " + (exchange.equals("amq.default") ? "the default exchange" : exchange)
                + " with routing key " + call.arguments().path("routingKey").asString(""))
            .withFields(List.of(new ActionPreview.Field("Exchange", exchange),
                new ActionPreview.Field("Routing key", call.arguments().path("routingKey").asString("")),
                new ActionPreview.Field("Persistent", String.valueOf(call.arguments().path("persistent")
                    .asBoolean(true)))));
      }
      case "rabbitmq_queues" -> http("GET", config, queuesPath(vhost, call.string("name"), 1,
          Math.clamp(call.integer("limit", 100), 1, 500)), null, target, "List queues");
      case "rabbitmq_consumers" -> http("GET", config, "/api/consumers/" + vhost, null, target, "List consumers");
      case "rabbitmq_dead_letters" -> http("GET", config, List.of(queuesPath(vhost, null, 1, PAGE),
          "/api/bindings/" + vhost, "/api/exchanges/" + vhost), null, target, "Find dead-letter queues");
      default -> ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), target,
          call.tool());
    };
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    RabbitClient rabbit;
    try {
      rabbit = RabbitClient.of(cfg, secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("config", e.getMessage(), null);
    }
    RabbitConfig config = rabbit.config();
    String where = config.host() + ":" + config.port();
    try {
      long t0 = System.nanoTime();
      RabbitClient.Response overview = rabbit.get("/api/overview");
      checks.add(new TestResult.Check("Management API", overview.ok(), ms(t0), overview.ok() ? null
          : "HTTP " + overview.status()));
      if (!overview.ok()) {
        return testFailure(overview, where, "/api/overview", checks);
      }
      String version = text(overview.json(), "rabbitmq_version");
      t0 = System.nanoTime();
      RabbitClient.Response me = rabbit.get("/api/whoami");
      checks.add(new TestResult.Check("Account", me.ok(), ms(t0), me.ok() ? null : "HTTP " + me.status()));
      List<String> tags = tags(me.json());
      t0 = System.nanoTime();
      RabbitClient.Response vhost = rabbit.get("/api/vhosts/" + config.vhostSegment());
      checks.add(new TestResult.Check("Virtual host " + config.vhost(), vhost.ok(), ms(t0), vhost.ok() ? null
          : "HTTP " + vhost.status()));
      if (vhost.status() == 404) {
        return TestResult.failure("no-vhost", "The virtual host " + config.vhost() + " doesn't exist on " + where
            + ", or this user can't see it.", "HTTP 404 from /api/vhosts").withChecks(checks);
      }
      RabbitClient.Response perms = rabbit.get("/api/permissions/" + config.vhostSegment() + "/"
          + RabbitConfig.segment(config.user()));
      String write = perms.ok() ? text(perms.json(), "write") : null;
      ObjectNode details = NODES.objectNode();
      ArrayNode tagNodes = details.putArray("tags");
      tags.forEach(tagNodes::add);
      if (perms.ok()) {
        details.put("configure", text(perms.json(), "configure"));
        details.put("write", write);
        details.put("read", text(perms.json(), "read"));
      }
      boolean admin = tags.contains("administrator");
      TestResult.Level level = admin ? TestResult.Level.ADMIN : write == null ? TestResult.Level.UNKNOWN
          : write.isEmpty() ? TestResult.Level.READ : TestResult.Level.WRITE;
      TestResult.Permissions permissions = new TestResult.Permissions(level, "queues and consumers of "
          + config.vhost(), write == null || write.isEmpty() ? null : "publish to exchanges matching " + write,
          admin ? List.of("administrator") : List.of(), details);
      TestResult result = TestResult.success(ms(started), version, me.ok() ? text(me.json(), "name") : config.user(),
          permissions).withChecks(checks);
      if (admin) {
        result = result.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "administrator", null,
            "This user is an administrator of the broker. Use a user tagged monitoring for prod."));
      }
      return result;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("Reach " + where, false, ms(started), e.getMessage()));
      return TestResult.failure(e.kind() == ConnectorException.Kind.DENIED ? "denied" : "unreachable",
          "RabbitMQ can't be reached at " + where + ": " + e.getMessage(), e.getMessage()).withChecks(checks);
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition t = tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool()
        + " isn't a tool of RabbitMQ " + manifest.version() + "."));
    if (t.nature() != ToolDefinition.Nature.READ && !ctx.approved()) {
      // Defence in depth: the gateway sends a change only after a person approved it.
      throw ConnectorException.denied(call.tool() + " puts a message on the broker: it runs only after a person "
          + "approved it.");
    }
    RabbitClient rabbit = RabbitClient.of(cfg, secrets, ctx);
    return switch (call.tool()) {
      case "rabbitmq_queues" -> queues(rabbit, call);
      case "rabbitmq_consumers" -> consumers(rabbit, call);
      case "rabbitmq_dead_letters" -> deadLetters(rabbit);
      case "rabbitmq_publish" -> publish(rabbit, call);
      default -> throw ConnectorException.badInput(call.tool() + " isn't implemented here.");
    };
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult queues(RabbitClient rabbit, ToolCall call) throws ConnectorException {
    int limit = Math.clamp(call.integer("limit", 100), 1, 500);
    String filter = call.string("name");
    if (filter != null && !NAME.matcher(filter).matches()) {
      throw ConnectorException.badInput("name is the text a queue's name contains.");
    }
    RabbitClient.Response r = rabbit.get(queuesPath(rabbit.config().vhostSegment(), filter, 1, limit));
    if (!r.ok()) {
      return failure(r, "the queues of " + rabbit.config().vhost());
    }
    JsonNode page = json(r);
    JsonNode items = page.isArray() ? page : page.path("items");
    ArrayNode rows = NODES.arrayNode();
    long total = 0;
    for (JsonNode q : items) {
      if (rows.size() >= limit) {
        break;
      }
      ArrayNode row = rows.addArray();
      row.add(q.path("name").asString(""));
      row.add(count(q, "messages"));
      row.add(count(q, "messages_ready"));
      row.add(count(q, "messages_unacknowledged"));
      row.add(count(q, "consumers"));
      row.add(q.path("state").asString(""));
      row.add(q.path("type").asString("classic"));
      String dlx = deadLetterExchange(q);
      if (dlx == null) {
        row.addNull();
      } else {
        row.add(dlx.isEmpty() ? "(default)" : dlx);
      }
      total += count(q, "messages");
    }
    ObjectNode structured = NODES.objectNode();
    structured.put("vhost", rabbit.config().vhost());
    structured.put("queues", rows.size());
    structured.put("messages", total);
    long matching = page.path("filtered_count").asLong(rows.size());
    structured.put("truncated", matching > rows.size());
    ToolResult result = ToolResult.table(new ToolResult.Table(List.of(text("queue"), integer("messages"),
        integer("ready"), integer("unacked"), integer("consumers"), text("state"), text("type"),
        text("dead_letter_exchange")), rows, null), structured);
    return matching > rows.size() ? result.truncated("row_limit") : result;
  }

  private ToolResult consumers(RabbitClient rabbit, ToolCall call) throws ConnectorException {
    int limit = Math.clamp(call.integer("limit", 100), 1, 500);
    String queue = call.string("queue");
    RabbitClient.Response r = rabbit.get("/api/consumers/" + rabbit.config().vhostSegment());
    if (!r.ok()) {
      return failure(r, "the consumers of " + rabbit.config().vhost());
    }
    ArrayNode rows = NODES.arrayNode();
    int matching = 0;
    for (JsonNode c : json(r)) {
      String q = c.path("queue").path("name").asString("");
      if (queue != null && !queue.isBlank() && !queue.equals(q)) {
        continue;
      }
      matching++;
      if (rows.size() >= limit) {
        continue;
      }
      JsonNode ch = c.path("channel_details");
      ArrayNode row = rows.addArray();
      row.add(q);
      row.add(c.path("consumer_tag").asString(""));
      row.add(ch.path("connection_name").asString(""));
      row.add(ch.path("peer_host").asString(""));
      row.add(c.path("prefetch_count").asLong(0));
      row.add(c.path("ack_required").asBoolean(true) ? "manual" : "auto");
      row.add(c.path("active").asBoolean(true));
    }
    ObjectNode structured = NODES.objectNode();
    structured.put("vhost", rabbit.config().vhost());
    structured.put("consumers", matching);
    structured.put("truncated", matching > rows.size());
    ToolResult result = ToolResult.table(new ToolResult.Table(List.of(text("queue"), text("consumer_tag"),
        text("connection"), text("peer_host"), integer("prefetch"), text("acks"),
        new ToolResult.Column("active", "boolean", null, null, null)), rows, null), structured);
    return matching > rows.size() ? result.truncated("row_limit") : result;
  }

  /**
   * Dead-letter queues and where the dead letters of each queue go: a queue's dead-letter exchange (its
   * {@code x-dead-letter-exchange} argument, else its policy's {@code dead-letter-exchange}) and routing key, matched
   * against the bindings of that exchange as its type routes; without a dead-letter routing key a message keeps the
   * keys it was published with, so every binding of the exchange may take it. A source whose exchange routes nowhere
   * (or doesn't exist) loses its dead letters; the answer lists those ({@code unrouted}), and the ones that continue
   * to another exchange ({@code onward}). A source on the default exchange without a routing key goes to the queue
   * each message's own key names, which no count can tell. Only counts are read.
   */
  private ToolResult deadLetters(RabbitClient rabbit) throws ConnectorException {
    String vhost = rabbit.config().vhostSegment();
    List<JsonNode> queues = new ArrayList<>();
    boolean more = true;
    for (int page = 1; more && queues.size() < MAX_QUEUES; page++) {
      RabbitClient.Response r = rabbit.get(queuesPath(vhost, null, page, PAGE));
      if (!r.ok()) {
        return failure(r, "the queues of " + rabbit.config().vhost());
      }
      JsonNode body = json(r);
      JsonNode items = body.isArray() ? body : body.path("items");
      items.forEach(queues::add);
      more = !body.isArray() && page < body.path("page_count").asInt(1);
    }
    RabbitClient.Response b = rabbit.get("/api/bindings/" + vhost);
    if (!b.ok()) {
      return failure(b, "the bindings of " + rabbit.config().vhost());
    }
    RabbitClient.Response x = rabbit.get("/api/exchanges/" + vhost);
    if (!x.ok()) {
      return failure(x, "the exchanges of " + rabbit.config().vhost());
    }
    Map<String, String> exchangeTypes = new LinkedHashMap<>();
    for (JsonNode e : json(x)) {
      exchangeTypes.put(e.path("name").asString(""), e.path("type").asString("direct"));
    }
    Map<String, JsonNode> byName = new LinkedHashMap<>();
    queues.forEach(q -> byName.put(q.path("name").asString(""), q));
    Map<String, List<String>> feeders = new LinkedHashMap<>();
    Map<String, String> exchangeOf = new LinkedHashMap<>();
    ArrayNode unrouted = NODES.arrayNode();
    ArrayNode onward = NODES.arrayNode();
    int sources = 0;
    for (JsonNode q : queues) {
      String dlx = deadLetterExchange(q);
      if (dlx == null) {
        continue;
      }
      sources++;
      String name = q.path("name").asString("");
      // Without a dead-letter routing key a message keeps the keys it was published with: any binding may take it.
      String key = deadLetterRoutingKey(q);
      List<String> targets = new ArrayList<>();
      List<String> exchanges = new ArrayList<>();
      if (dlx.isEmpty()) {
        if (key == null) {
          // The default exchange with the message's own routing key: the queue it names, which only a message knows.
          continue;
        }
        if (byName.containsKey(key)) {
          targets.add(key);
        }
      } else if (exchangeTypes.containsKey(dlx)) {
        String type = exchangeTypes.get(dlx);
        for (JsonNode binding : json(b)) {
          if (!dlx.equals(binding.path("source").asString())
              || key != null && !routes(type, binding.path("routing_key").asString(""), key)) {
            continue;
          }
          String target = binding.path("destination").asString("");
          List<String> into = "queue".equals(binding.path("destination_type").asString()) ? targets : exchanges;
          if (!into.contains(target)) {
            into.add(target);
          }
        }
      }
      if (targets.isEmpty() && exchanges.isEmpty()) {
        ObjectNode lost = unrouted.addObject();
        lost.put("queue", name);
        lost.put("exchange", dlx.isEmpty() ? "(default)" : dlx);
        if (key == null) {
          lost.putNull("routingKey");
        } else {
          lost.put("routingKey", key);
        }
        lost.put("exchangeExists", dlx.isEmpty() || exchangeTypes.containsKey(dlx));
      }
      for (String exchange : exchanges) {
        ObjectNode via = onward.addObject();
        via.put("queue", name);
        via.put("exchange", dlx);
        via.put("continuesTo", exchange);
      }
      for (String target : targets) {
        feeders.computeIfAbsent(target, k -> new ArrayList<>()).add(name);
        exchangeOf.putIfAbsent(target, dlx.isEmpty() ? "(default)" : dlx);
      }
    }
    ArrayNode rows = NODES.arrayNode();
    ArrayNode dlqs = NODES.arrayNode();
    for (Map.Entry<String, List<String>> e : feeders.entrySet()) {
      JsonNode q = byName.getOrDefault(e.getKey(), NODES.objectNode());
      ArrayNode row = rows.addArray();
      row.add(e.getKey());
      row.add(count(q, "messages"));
      row.add(count(q, "messages_ready"));
      row.add(count(q, "consumers"));
      row.add(exchangeOf.get(e.getKey()));
      row.add(String.join(", ", e.getValue()));
      ObjectNode d = dlqs.addObject();
      d.put("queue", e.getKey());
      d.put("messages", count(q, "messages"));
      d.put("ready", count(q, "messages_ready"));
      d.put("consumers", count(q, "consumers"));
      d.put("exchange", exchangeOf.get(e.getKey()));
      ArrayNode from = d.putArray("sources");
      e.getValue().forEach(from::add);
    }
    ObjectNode structured = NODES.objectNode();
    structured.put("vhost", rabbit.config().vhost());
    structured.put("sourceQueues", sources);
    structured.set("deadLetterQueues", dlqs);
    structured.set("unrouted", unrouted);
    structured.set("onward", onward);
    structured.put("truncated", queues.size() >= MAX_QUEUES);
    return ToolResult.table(new ToolResult.Table(List.of(text("dead_letter_queue"), integer("messages"),
        integer("ready"), integer("consumers"), text("exchange"), text("fed_by")), rows, null), structured);
  }

  // ── Change ──────────────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult publish(RabbitClient rabbit, ToolCall call) throws ConnectorException {
    String exchange = exchange(call);
    String key = call.arguments().path("routingKey").asString(null);
    if (key == null || !NAME.matcher(key).matches()) {
      throw ConnectorException.badInput("routingKey is required (up to 255 characters, no control characters).");
    }
    if (!NAME.matcher(exchange).matches()) {
      throw ConnectorException.badInput("exchange is a name of up to 255 characters.");
    }
    RabbitClient.Response r = rabbit.post("/api/exchanges/" + rabbit.config().vhostSegment() + "/"
        + RabbitConfig.segment(exchange) + "/publish", publishBody(call));
    if (!r.ok()) {
      return r.status() == 404 ? ToolResult.error("The exchange " + exchange + " doesn't exist in the virtual host "
          + rabbit.config().vhost() + ".") : failure(r, "the publish to " + exchange);
    }
    boolean routed = r.json() != null && r.json().path("routed").asBoolean(false);
    ObjectNode out = NODES.objectNode();
    out.put("exchange", exchange);
    out.put("routingKey", key);
    out.put("routed", routed);
    return ToolResult.json(out, routed ? "Published to " + exchange + " with routing key " + key + "; a queue took "
        + "it." : "RabbitMQ accepted the message, but no queue is bound for routing key " + key + " on " + exchange
        + ": it was dropped.");
  }

  // ── Requests and answers ────────────────────────────────────────────────────────────────────────────────────

  private static ActionPreview http(String method, RabbitConfig config, String pathAndQuery, JsonNode body,
      String target, String summary) {
    return http(method, config, List.of(pathAndQuery), body, target, summary);
  }

  /** The requests one call makes, one per line, the body of a POST under it. */
  private static ActionPreview http(String method, RabbitConfig config, List<String> paths, JsonNode body,
      String target, String summary) {
    StringBuilder text = new StringBuilder();
    for (String path : paths) {
      text.append(text.isEmpty() ? "" : "\n").append(method).append(' ').append(config.uri(path));
    }
    if (body != null) {
      text.append('\n').append(body);
    }
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), target, summary);
  }

  private static String queuesPath(String vhost, String filter, int page, int size) {
    StringBuilder q = new StringBuilder("/api/queues/").append(vhost).append("?page=").append(page)
        .append("&page_size=").append(size).append("&sort=messages&sort_reverse=true&pagination=true");
    if (filter != null && !filter.isBlank()) {
      q.append("&name=").append(URLEncoder.encode(filter, StandardCharsets.UTF_8)).append("&use_regex=false");
    }
    return q.toString();
  }

  /** The exchange of a publish: {@code amq.default} for the default (empty) one, as the management API names it. */
  private static String exchange(ToolCall call) {
    String exchange = call.arguments().path("exchange").asString("");
    return exchange.isEmpty() ? "amq.default" : exchange;
  }

  private static ObjectNode publishBody(ToolCall call) {
    ObjectNode body = NODES.objectNode();
    ObjectNode props = body.putObject("properties");
    props.put("delivery_mode", call.arguments().path("persistent").asBoolean(true) ? 2 : 1);
    props.put("content_type", call.arguments().path("contentType").asString("application/json"));
    JsonNode headers = call.arguments().path("headers");
    if (headers.isObject() && !headers.isEmpty()) {
      props.set("headers", headers.deepCopy());
    }
    body.put("routing_key", call.arguments().path("routingKey").asString(""));
    body.put("payload", call.arguments().path("payload").asString(""));
    body.put("payload_encoding", "string");
    return body;
  }

  /** The queue's dead-letter exchange ({@code ""} is the default exchange), or {@code null} when it has none. */
  static String deadLetterExchange(JsonNode q) {
    JsonNode arg = q.path("arguments").path("x-dead-letter-exchange");
    if (arg.isString()) {
      return arg.asString();
    }
    JsonNode policy = q.path("effective_policy_definition").path("dead-letter-exchange");
    return policy.isString() ? policy.asString() : null;
  }

  static String deadLetterRoutingKey(JsonNode q) {
    JsonNode arg = q.path("arguments").path("x-dead-letter-routing-key");
    if (arg.isString()) {
      return arg.asString();
    }
    JsonNode policy = q.path("effective_policy_definition").path("dead-letter-routing-key");
    return policy.isString() ? policy.asString() : null;
  }

  /** Whether a binding with {@code bindingKey} on an exchange of {@code type} takes {@code routingKey}. */
  static boolean routes(String type, String bindingKey, String routingKey) {
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "fanout", "headers" -> true;
      case "topic" -> topicMatches(bindingKey, routingKey);
      default -> bindingKey.equals(routingKey);
    };
  }

  /** AMQP topic matching: {@code *} is one word, {@code #} zero or more, words split on dots. */
  static boolean topicMatches(String pattern, String key) {
    return topic(pattern.split("\\.", -1), 0, key.split("\\.", -1), 0);
  }

  private static boolean topic(String[] p, int i, String[] k, int j) {
    if (i == p.length) {
      return j == k.length;
    }
    if (p[i].equals("#")) {
      for (int skip = j; skip <= k.length; skip++) {
        if (topic(p, i + 1, k, skip)) {
          return true;
        }
      }
      return false;
    }
    return j < k.length && (p[i].equals("*") || p[i].equals(k[j])) && topic(p, i + 1, k, j + 1);
  }

  /**
   * What RabbitMQ's refusal means for the agent: credentials, permissions, the request itself. Overload and outages
   * are exceptions instead.
   */
  private static ToolResult failure(RabbitClient.Response r, String what) throws ConnectorException {
    int s = r.status();
    if (s == 401) {
      return ToolResult.error("RabbitMQ rejected the user or the password. Test the connection.");
    }
    if (s == 403) {
      return ToolResult.error("This user may not read " + what + ": " + reason(r));
    }
    if (s == 404) {
      return ToolResult.error("RabbitMQ found nothing for " + what + ": " + reason(r));
    }
    if (s == 429 || s >= 500) {
      throw ConnectorException.unavailable("RabbitMQ answered HTTP " + s + " for " + what + ".", null);
    }
    return ToolResult.error("RabbitMQ refused the request for " + what + " (HTTP " + s + "): " + reason(r));
  }

  private static TestResult testFailure(RabbitClient.Response r, String where, String what,
      List<TestResult.Check> checks) {
    if (r.status() == 401) {
      return TestResult.failure("auth", "RabbitMQ rejected the user or the password.", "HTTP 401 from " + what)
          .withChecks(checks);
    }
    if (r.status() == 404) {
      return TestResult.failure("not-management", where + " doesn't answer like RabbitMQ's management API. Check "
          + "the host and the port, and that the management plugin is on.", "HTTP 404 from " + what)
          .withChecks(checks);
    }
    return TestResult.failure("http-" + r.status(), "RabbitMQ answered HTTP " + r.status() + ".", "HTTP "
        + r.status() + " from " + what).withChecks(checks);
  }

  /** RabbitMQ's own words: {@code reason}, else {@code error}, else the status. */
  private static String reason(RabbitClient.Response r) {
    JsonNode j = r.json();
    if (j != null) {
      String reason = j.path("reason").asString(null);
      if (reason != null && !reason.isBlank()) {
        return cut(reason);
      }
      String error = j.path("error").asString(null);
      if (error != null && !error.isBlank()) {
        return cut(error);
      }
    }
    return "HTTP " + r.status() + ".";
  }

  /** The JSON of a successful answer; one that isn't JSON (a proxy's page) is the resource's error. */
  private static JsonNode json(RabbitClient.Response r) throws ConnectorException {
    if (r.json() == null) {
      throw ConnectorException.resourceError("RabbitMQ answered HTTP " + r.status() + " with something that isn't "
          + "JSON.", null);
    }
    return r.json();
  }

  /** {@code tags} is a list in RabbitMQ 3.9 and later, a comma-separated string before. */
  private static List<String> tags(JsonNode me) {
    List<String> out = new ArrayList<>();
    if (me == null) {
      return out;
    }
    JsonNode tags = me.path("tags");
    if (tags.isArray()) {
      tags.forEach(t -> out.add(t.asString("")));
    } else if (tags.isString()) {
      for (String t : tags.asString().split(",")) {
        if (!t.isBlank()) {
          out.add(t.strip());
        }
      }
    }
    return out;
  }

  private static long count(JsonNode q, String field) {
    return q.path(field).asLong(0);
  }

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  private static ToolResult.Column text(String name) {
    return new ToolResult.Column(name, "text", null, null, null);
  }

  private static ToolResult.Column integer(String name) {
    return new ToolResult.Column(name, "integer", null, null, null);
  }

  private static String text(JsonNode n, String field) {
    if (n == null) {
      return null;
    }
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static String cut(String s) {
    return s.length() <= 300 ? s : s.substring(0, 300) + "…";
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
