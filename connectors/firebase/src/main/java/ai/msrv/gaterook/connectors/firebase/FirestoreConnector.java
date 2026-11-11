package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.google.auth.oauth2.GoogleCredentials;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cloud Firestore (docs/firestore.md): {@code firestore_list_collections}, {@code firestore_get_document} and
 * {@code firestore_query} read; {@code firestore_write} runs one create, update or delete of one document that a
 * person approved.
 *
 * <p>The route of a call comes from {@link #classify} without I/O: paths and field paths read strictly, the
 * collection on the connection's list, values that are values. {@link #execute} gets no policy and checks the same
 * again; a change runs only with the core's approval and runs the approved write ({@link FirestoreWrite#approved}),
 * never the call's arguments. The token is the connection's Google identity, limited for a key or an impersonated
 * service account to Datastore and Firestore ({@link FirestoreApi#SCOPE}); Google's IAM roles of that identity are the
 * boundary behind the gateway's checks.
 */
public final class FirestoreConnector implements Connector {

  static final String TYPE = "firestore";
  static final int DEFAULT_LIMIT = 25;
  static final int DEFAULT_LIST = 100;
  /** Pages one listing reads at most to fill its limit, as the connection's list may keep few IDs of each. */
  static final int MAX_PAGES = 5;
  /** Collections Test connection lists. */
  static final int PROBE = 20;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final CloudCredentials cloud;
  private final GoogleHttp http;
  private final FirestoreApi api;
  private final ConnectorManifest manifest;

  public FirestoreConnector(CloudCredentials cloud) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.http = new GoogleHttp(cloud);
    this.api = new FirestoreApi(http);
    SchemaResources schemas = SchemaResources.of(FirestoreConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Cloud Firestore")
        .icon("database")
        .group("Databases")
        .description("Documents, collections and queries on Cloud Firestore. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(FirestoreTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("firestore.googleapis.com", "oauth2.googleapis.com",
            "iamcredentials.googleapis.com"), List.of(), List.of(
            "~/.config/gcloud/application_default_credentials.json")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    FirestoreSettings s;
    try {
      s = FirestoreSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    JsonNode args = call.arguments();
    try {
      return switch (call.tool()) {
        case FirestoreTools.LIST_COLLECTIONS -> {
          parent(args);
          yield Classification.read();
        }
        case FirestoreTools.GET_DOCUMENT -> {
          document(args, s);
          yield Classification.read();
        }
        case FirestoreTools.QUERY -> {
          FirestoreQuery.of(args, s);
          yield Classification.read();
        }
        case FirestoreTools.WRITE -> {
          FirestoreWrite w = FirestoreWrite.of(args, s, policy.limits() == null ? 0
              : policy.limits().maxRequestBytes());
          ObjectNode facts = F.objectNode().put("operation", w.operation())
              .put("collection", w.document().parent().text());
          yield Classification.mutation(w.operation() + " of a document in " + w.document().parent()
              + " changes data", facts);
        }
        default -> Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool()
            + ".", null);
      };
    } catch (Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
  }

  /** {@code parent} of a listing: the root, or a document. */
  private static FirestorePath parent(JsonNode args) throws Refusal {
    JsonNode p = args.path("parent");
    if (p.isMissingNode() || p.isNull()) {
      return FirestorePath.ROOT;
    }
    if (!p.isString()) {
      throw Refusal.bad("parent must be a document's path, such as users/alice.", null);
    }
    FirestorePath path = FirestorePath.parse(p.asString());
    if (!path.root() && !path.document()) {
      throw Refusal.bad("parent " + Names.shown(p.asString()) + " isn't a document: collections are listed at the "
          + "root or under a document, such as users/alice.", null);
    }
    return path;
  }

  /** {@code document} of a read, on the connection's list. */
  private static FirestorePath document(JsonNode args, FirestoreSettings s) throws Refusal {
    JsonNode d = args.path("document");
    if (!d.isString()) {
      throw Refusal.bad("document must be a document's path, such as users/alice.", null);
    }
    FirestorePath path = FirestorePath.parse(d.asString());
    if (!path.document()) {
      throw Refusal.bad("document " + Names.shown(d.asString()) + " isn't a document: a document's path has an even "
          + "number of IDs, such as users/alice or users/alice/orders/o-1.", "Query a collection with "
          + "firestore_query.");
    }
    if (!s.allowsCollection(path.parent())) {
      throw new Refusal(Refusal.NOT_ALLOWED_COLLECTION, Names.shown(path.parent().text()) + " isn't one of the "
          + "collections agents may use on this connection.", "Call firestore_list_collections to see the "
          + "collections you can use.");
    }
    return path;
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    if (FirestoreTools.WRITE.equals(call.tool())) {
      try {
        FirestoreSettings s = FirestoreSettings.of(cfg);
        return writePreview(cfg, s, FirestoreWrite.of(call.arguments(), s, 0));
      } catch (ConnectorException e) {
        return notRunnable(cfg, e.getMessage());
      } catch (Refusal r) {
        return notRunnable(cfg, r.getMessage());
      }
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    ObjectNode payload = F.objectNode().put("tool", call.tool());
    payload.set("arguments", args);
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), cfg.name() + " · "
            + cfg.string("/project"), shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(payload)
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Documents, no change")));
  }

  private static ActionPreview notRunnable(ConnectionConfig cfg, String why) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "Not runnable: " + why, cfg.name(),
        shorten(FirestoreTools.WRITE + " on " + cfg.name() + ": not runnable", 120)).withPayload(F.objectNode());
  }

  private static ActionPreview writePreview(ConnectionConfig cfg, FirestoreSettings s, FirestoreWrite w) {
    String path = w.document().text();
    int n = w.fieldNames().size();
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Operation", switch (w.operation()) {
      case "create" -> "Create the document; refused if it exists";
      case "update" -> "Update " + n + (n == 1 ? " field" : " fields") + "; the others stay. Refused if the document "
          + "doesn't exist";
      default -> "Delete the document; refused if it doesn't exist";
    }));
    fields.add(new Field("Document", path));
    if (n > 0) {
      fields.add(new Field("Fields", shorten(String.join(", ", w.fieldNames()), 2_000)));
    }
    List<String> warnings = w.operation().equals("delete")
        ? List.of("Firestore doesn't delete the document's subcollections: their documents stay.") : List.of();
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", w.text(),
            cfg.name() + " · " + s.label() + " · " + path,
            shorten(FirestoreTools.WRITE + " on " + cfg.name() + ": " + w.operation() + " " + path, 120))
        .withFields(fields)
        .withImpact(List.of(new Field("Documents", "One: " + path),
            new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
        .withWarnings(warnings)
        .withFacets(Map.of("operation", w.operation(), "collection", w.document().parent().text()))
        .withPayload(w.commit());
  }

  static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    FirestoreSettings s = FirestoreSettings.of(cfg);
    GoogleHttp.Caller c = new GoogleHttp.Caller(cfg, s.credentials(), List.of(FirestoreApi.SCOPE), secrets, ctx);
    try {
      return switch (call.tool()) {
        case FirestoreTools.LIST_COLLECTIONS -> listCollections(c, s, arguments(call, ctx));
        case FirestoreTools.GET_DOCUMENT -> getDocument(c, s, arguments(call, ctx));
        case FirestoreTools.QUERY -> query(c, s, arguments(call, ctx));
        case FirestoreTools.WRITE -> write(c, s, ctx);
        default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
      };
    } catch (Refusal r) {
      throw r.toException();
    }
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) throws ConnectorException {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      JsonNode payload = ctx.approvedPayload().get();
      if (!call.tool().equals(payload.path("tool").asString(""))) {
        throw ConnectorException.badInput("The approved request is for another tool. Not executed.");
      }
      return payload.get("arguments");
    }
    return call.arguments();
  }

  private static int limit(JsonNode args, int dflt, int max) {
    JsonNode l = args.path("limit");
    int n = l.isIntegralNumber() && l.canConvertToInt() ? l.asInt() : dflt;
    return Math.max(1, Math.min(n, max));
  }

  /** Bytes of the answer's documents, counted twice (structured content and text); room for the rest of it. */
  static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 10_240);
  }

  private ToolResult listCollections(GoogleHttp.Caller c, FirestoreSettings s, JsonNode args)
      throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    FirestorePath parent = parent(args);
    int limit = limit(args, DEFAULT_LIST, 1_000);
    String hash = Cursors.hash(F.objectNode().put("parent", parent.text()));
    String token = null;
    if (args.path("cursor").isString()) {
      token = Cursors.open(ctx, FirestoreTools.LIST_COLLECTIONS, args.get("cursor").asString(), c.cfg().id(), hash)
          .asString(null);
    }
    URI uri = api.uri(s, parent, "listCollectionIds");
    List<String> ids = new ArrayList<>();
    String next = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      ObjectNode req = F.objectNode().put("pageSize", limit - ids.size());
      if (token != null) {
        req.put("pageToken", token);
      }
      JsonNode r = api.post(c, uri, req, false);
      for (JsonNode id : r.path("collectionIds")) {
        String name = id.asString("");
        if (FirestorePath.problem(name) == null && s.allowsCollection(parent.child(name))) {
          ids.add(name);
        }
      }
      String t = r.path("nextPageToken").asString("");
      next = t.isEmpty() ? null : t;
      if (next == null || ids.size() >= limit || ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
      token = next;
    }
    ObjectNode out = F.objectNode().put("parent", parent.text());
    ArrayNode list = out.putArray("collections");
    ids.forEach(list::add);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(ctx, FirestoreTools.LIST_COLLECTIONS, c.cfg().id(), hash,
          F.stringNode(next)));
    }
    String where = parent.root() ? "at the root of " + s.label() : "under " + parent.text();
    String text = ids.isEmpty() ? "No collections agents may use " + where + "."
        : ids.size() + (ids.size() == 1 ? " collection " : " collections ") + where + ": " + String.join(", ", ids)
            + (next == null ? "" : "\nMore collections: pass nextCursor.");
    return ToolResult.json(out, text);
  }

  private ToolResult getDocument(GoogleHttp.Caller c, FirestoreSettings s, JsonNode args)
      throws ConnectorException, Refusal {
    FirestorePath doc = document(args, s);
    String name = doc.name(s.documents());
    ObjectNode req = F.objectNode();
    req.putArray("documents").add(name);
    JsonNode found = null;
    String readTime = null;
    try (FirestoreApi.Elements e = api.stream(c, api.uri(s, "batchGet"), req, GoogleHttp.MAX_BODY)) {
      JsonNode el;
      while ((el = e.next()) != null) {
        if (el.path("found").isObject()) {
          found = el.get("found");
        }
        if (el.path("readTime").isString()) {
          readTime = el.get("readTime").asString();
        }
      }
      if (e.exceeded()) {
        throw ConnectorException.resourceError("Firestore's answer for " + doc + " is larger than "
            + GoogleHttp.size(GoogleHttp.MAX_BODY) + ".", null);
      }
    }
    if (found != null && !name.equals(found.path("name").asString(""))) {
      throw ConnectorException.resourceError("Firestore answered with another document than " + doc + ".", null);
    }
    ObjectNode out = F.objectNode();
    if (found == null) {
      out.put("found", false).put("path", doc.text());
      if (readTime != null) {
        out.put("readTime", readTime);
      }
      return ToolResult.json(out, "No document " + doc + ".");
    }
    ObjectNode d = FirestoreValues.document(found, s.documents(), c.ctx().limits().maxCellChars());
    long budget = budget(c.ctx()) / 2;
    ObjectNode shown = fit(d, budget);
    out.put("found", true);
    out.set("document", shown);
    if (readTime != null) {
      out.put("readTime", readTime);
    }
    ToolResult r = ToolResult.json(out, doc + "\n" + shown);
    return shown == d ? r : ToolResult.json(out, doc + "\n" + shown + "\nCut at the response size: "
        + shown.path("omittedFields").asInt() + " fields left out.").truncated("size_limit");
  }

  /**
   * {@code doc} within {@code budget} bytes: its fields in order while they fit, then how many were left out
   * ({@code omittedFields}); the document itself when it fits whole.
   */
  static ObjectNode fit(ObjectNode doc, long budget) {
    if (Names.utf8Length(doc.toString()) <= budget) {
      return doc;
    }
    ObjectNode out = doc.deepCopy();
    ObjectNode kept = F.objectNode();
    out.set("fields", kept);
    long used = Names.utf8Length(out.toString()) + 32;
    int left = 0;
    for (Map.Entry<String, JsonNode> e : doc.path("fields").properties()) {
      long cost = Names.utf8Length(e.getKey()) + Names.utf8Length(e.getValue().toString()) + 4;
      if (left > 0 || used + cost > budget) {
        left++;
        continue;
      }
      used += cost;
      kept.set(e.getKey(), e.getValue());
    }
    out.put("omittedFields", left);
    return out;
  }

  private ToolResult query(GoogleHttp.Caller c, FirestoreSettings s, JsonNode args)
      throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    FirestoreQuery q = FirestoreQuery.of(args, s);
    int limit = limit(args, DEFAULT_LIMIT, ctx.limits().maxRows());
    ObjectNode request = F.objectNode().put("parent", q.parent());
    request.set("query", q.structuredQuery());
    String hash = Cursors.hash(request);
    ObjectNode sq = q.structuredQuery().deepCopy();
    if (args.path("cursor").isString()) {
      JsonNode position = Cursors.open(ctx, FirestoreTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash);
      if (!position.isArray() || position.size() != q.order().size()) {
        throw ConnectorException.badInput("The cursor doesn't fit this query. Send the request unchanged with its "
            + "cursor, or leave cursor out.");
      }
      ObjectNode start = sq.putObject("startAt");
      start.set("values", position.deepCopy());
      start.put("before", false);
    }
    // One more than asked: whether there is a next page.
    sq.put("limit", limit + 1);
    ObjectNode body = F.objectNode();
    body.set("structuredQuery", sq);

    int cell = ctx.limits().maxCellChars();
    long budget = budget(ctx);
    ArrayNode docs = F.arrayNode();
    List<String> lines = new ArrayList<>();
    long used = 0;
    JsonNode last = null;
    boolean more = false;
    String truncated = null;
    String readTime = null;
    try (FirestoreApi.Elements e = api.stream(c, api.uri(s, q.parentPath(), "runQuery"), body, GoogleHttp.MAX_BODY)) {
      JsonNode el;
      while ((el = e.next()) != null) {
        if (el.path("readTime").isString()) {
          readTime = el.get("readTime").asString();
        }
        JsonNode raw = el.get("document");
        if (raw == null || !raw.isObject()) {
          continue;
        }
        if (!q.contains(raw.path("name").asString(""), s)) {
          throw ConnectorException.resourceError("Firestore answered with a document outside the query: "
              + Names.shown(raw.path("name").asString("")) + ".", null);
        }
        if (docs.size() == limit) {
          more = true;
          break;
        }
        ObjectNode d = FirestoreValues.document(raw, s.documents(), cell);
        String line = d.toString();
        long cost = 2L * Names.utf8Length(line) + 2;
        if (used + cost > budget) {
          truncated = "size_limit";
          if (docs.isEmpty()) {
            // The first document alone is too large: its fields that fit, so the next page goes on after it.
            ObjectNode cut = fit(d, budget / 2);
            docs.add(cut);
            lines.add(cut.toString());
            last = raw;
          }
          break;
        }
        used += cost;
        docs.add(d);
        lines.add(line);
        last = raw;
      }
      if (e.exceeded()) {
        if (docs.isEmpty()) {
          throw ConnectorException.resourceError("Firestore's answer is larger than "
              + GoogleHttp.size(GoogleHttp.MAX_BODY) + " before its first document.", null);
        }
        truncated = "size_limit";
      }
    }
    String nextCursor = null;
    String note = null;
    if ((more || truncated != null) && last != null) {
      ArrayNode position = q.position(last);
      if (position == null) {
        note = "The last document lacks a field of the ordering, so no page can start after it.";
      } else {
        String sealed = Cursors.seal(ctx, FirestoreTools.QUERY, c.cfg().id(), hash, position);
        if (sealed.length() > Cursors.MAX_LENGTH) {
          note = "The last document's values of the ordering are too long for a cursor: narrow the query.";
        } else {
          nextCursor = sealed;
        }
      }
    }
    ObjectNode out = F.objectNode();
    if (q.collection() != null) {
      out.put("collection", q.collection().text());
    } else {
      out.put("collectionGroup", q.group());
    }
    out.set("documents", docs);
    out.put("count", docs.size());
    if (nextCursor != null) {
      out.put("nextCursor", nextCursor);
    }
    if (readTime != null) {
      out.put("readTime", readTime);
    }
    StringBuilder text = new StringBuilder();
    text.append(docs.size()).append(docs.size() == 1 ? " document" : " documents").append(" from ")
        .append(q.target());
    for (String line : lines) {
      text.append('\n').append(line);
    }
    if (nextCursor != null) {
      text.append("\nMore documents: pass nextCursor.");
    }
    if (truncated != null) {
      text.append("\nCut at the response size: pass nextCursor for the rest, or ask for a smaller limit.");
    }
    if (note != null) {
      text.append('\n').append(note);
    }
    ToolResult result = ToolResult.json(out, text.toString());
    return truncated == null ? result : result.truncated(truncated);
  }

  private ToolResult write(GoogleHttp.Caller c, FirestoreSettings s, ExecContext ctx) throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: firestore_write changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no write."));
    FirestoreWrite w;
    try {
      w = FirestoreWrite.approved(payload, s);
    } catch (Refusal r) {
      throw ConnectorException.denied(r.getMessage());
    }
    byte[] body = w.text().getBytes(StandardCharsets.UTF_8);
    long max = ctx.limits().maxRequestBytes();
    if (max > 0 && body.length > max) {
      throw ConnectorException.denied("The write is larger than the " + GoogleHttp.size(max) + " a request may be "
          + "on this endpoint.");
    }
    JsonNode r = api.post(c, api.uri(s, "commit"), body, true);
    String path = w.document().text();
    ObjectNode out = F.objectNode().put("committed", true).put("operation", w.operation()).put("document", path);
    if (r != null) {
      String updated = r.path("writeResults").path(0).path("updateTime").asString(null);
      if (updated != null) {
        out.put("updateTime", updated);
      }
      String committed = r.path("commitTime").asString(null);
      if (committed != null) {
        out.put("commitTime", committed);
      }
    }
    int n = w.fieldNames().size();
    String text = switch (w.operation()) {
      case "create" -> "Created " + path + ".";
      case "update" -> "Updated " + n + (n == 1 ? " field of " : " fields of ") + path + ".";
      default -> "Deleted " + path + ".";
    };
    return ToolResult.json(out, text);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    FirestoreSettings s;
    try {
      s = FirestoreSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("firestore.settings", e.getMessage(), null);
    }
    GoogleHttp.Caller c = new GoogleHttp.Caller(cfg, s.credentials(), List.of(FirestoreApi.SCOPE), secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    URI probe = api.uri(s, "listCollectionIds");
    long started = System.nanoTime();
    GoogleCredentials credentials;
    Map<String, List<String>> headers;
    try {
      credentials = http.credentials(c);
      headers = http.token(c, probe, cloud::sendsGoogleTokensTo, false);
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("identity", false, ms(started), e.getMessage()));
      return TestResult.failure("firestore.credentials", e.getMessage(), null).withChecks(checks);
    }
    String identity = GoogleIdentity.of(http, c, credentials, headers);
    boolean personal = GoogleIdentity.personal(credentials);
    checks.add(new TestResult.Check("identity", true, ms(started), s.credentials().impersonate() != null
        ? "Impersonates " + identity + " for " + s.credentials().lifetimeSeconds() / 60 + " min"
        : (personal ? "Signed in as " : "Service account ") + identity));
    long listed = System.nanoTime();
    JsonNode r;
    try {
      r = api.post(c, probe, F.objectNode().put("pageSize", PROBE), false);
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("collections", false, ms(listed), e.getMessage()));
      String code = e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE
          || e.kind() == ConnectorException.Kind.TIMEOUT ? "firestore.unreachable" : "firestore.refused";
      return TestResult.failure(code, e.getMessage(), null).withChecks(checks);
    }
    long latency = ms(listed);
    List<String> visible = new ArrayList<>();
    for (JsonNode id : r.path("collectionIds")) {
      String name = id.asString("");
      if (FirestorePath.problem(name) == null && s.allowsCollection(FirestorePath.ROOT.child(name))) {
        visible.add(name);
      }
    }
    boolean more = !r.path("nextPageToken").asString("").isEmpty();
    List<String> first = new ArrayList<>(visible.subList(0, Math.min(5, visible.size())));
    if (visible.size() > 5 || more) {
      first.add("…");
    }
    checks.add(new TestResult.Check("collections", true, latency, (visible.isEmpty() ? "No collection"
        : visible.size() + (more ? "+" : "") + (visible.size() == 1 && !more ? " collection" : " collections"))
        + " at the root that agents may use" + (first.isEmpty() ? "" : ": " + String.join(", ", first))));
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "Documents of " + (s.restricted() ? "the connection's collections" : "every collection the credentials may "
            + "read"), "Only writes a person approved", List.of(), null);
    TestResult result = TestResult.success(latency, "Cloud Firestore API v1 · " + s.label(), identity, permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Cloud Firestore · " + s.label())
            .put("project", s.project()).put("database", s.database()));
    for (Hint h : hints(cfg.env(), s, personal, visible.size())) {
      result = result.withHint(h);
    }
    return result;
  }

  static List<Hint> hints(Env env, FirestoreSettings s, boolean personal, int visible) {
    List<Hint> out = new ArrayList<>();
    if (s.credentials().impersonate() == null) {
      if (env == Env.PROD) {
        out.add(GoogleIdentity.longLived("firestore.long-lived-credentials", s.credentials(), personal,
            "roles/datastore.viewer, or roles/datastore.user for approved changes"));
      } else if (env == Env.STAGE && personal) {
        out.add(new Hint(Severity.INFO, "firestore.personal-sign-in", "/credentials/impersonate",
            "Agents read with your own Google sign-in. Impersonate a service account to give them less."));
      }
    }
    if (visible == 0) {
      out.add(s.restricted()
          ? new Hint(Severity.INFO, "firestore.no-collections", "/collections", "No collection at the root of "
              + s.label() + " is on the connection's list; collections further down may be.")
          : new Hint(Severity.WARNING, "firestore.no-collections", "/collections", "The credentials see no "
              + "collection at the root of " + s.label() + "."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      FirestoreSettings s = FirestoreSettings.of(cfg);
      GoogleHttp.Caller c = new GoogleHttp.Caller(cfg, s.credentials(), List.of(FirestoreApi.SCOPE), secrets, ctx);
      long started = System.nanoTime();
      api.post(c, api.uri(s, "listCollectionIds"), F.objectNode().put("pageSize", 1), false);
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    out.put("project", cfg.string("/project"));
    String database = cfg.string("/database");
    out.put("database", database == null || database.isBlank() ? FirestoreSettings.DEFAULT_DATABASE : database);
    List<String> collections = cfg.strings("/collections");
    if (collections.isEmpty()) {
      out.put("collections", "Every collection the credentials may read");
    } else {
      ArrayNode a = out.putArray("collections");
      collections.forEach(a::add);
      out.put("collectionRules", "An ID such as orders allows every collection with that ID and collection-group "
          + "queries on it; a path such as users/*/orders allows the collections at that path");
    }
    out.put("reads", "firestore_list_collections, firestore_get_document and firestore_query; documents come back "
        + "as plain JSON");
    out.put("values", "Plain JSON, or one Firestore typed value such as {\"timestampValue\": \"2026-01-02T03:04:05Z\"}"
        + " or {\"referenceValue\": \"users/alice\"}");
    out.put("changes", "One create, update or delete of one document per firestore_write, after approval");
    if (object != null && !object.isBlank()) {
      out.put("collection", "Call firestore_query with collection " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    cloud.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    cloud.evict(connectionId);
  }

  @Override
  public void close() {
    http.close();
  }

  static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
