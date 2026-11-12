package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud.Audience;
import ai.msrv.gaterook.connectors.cloudauth.AzureCredentials;
import ai.msrv.gaterook.connectors.cloudauth.AzureSpec;
import ai.msrv.gaterook.connectors.cloudauth.AzureTrust;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure AI Search's data plane REST API (docs/search-engines.md), {@code api-version} {@value #API_VERSION}: indexes,
 * their definitions and statistics, search with an OData {@code $filter} GateRook writes from the filter tree and
 * vector queries, documents by key, and batches of uploads, merges and deletes. The service's host is fixed by the
 * connection ({@code <service>.search.windows.net} or a sovereign cloud's).
 *
 * <p>A connection signs in one of two ways ({@code auth}):
 * <ul>
 *   <li>an API key in {@code api-key}, the default: a query key may only search and read documents — Test connection
 *       tells it from an admin key — and listing or describing indexes needs an admin key, so with a query key the
 *       connection names its indexes;</li>
 *   <li>Microsoft Entra ID ({@code entraId}): a token for the search audience of the endpoint's cloud
 *       ({@link Audience#SEARCH}) from the Azure CLI, a device sign-in or a service principal ({@link AzureSpec}),
 *       sent as {@code Authorization: Bearer} only to the service's own host, tainted, and dropped when the service
 *       answers 401. The identity's roles decide what it may do: Search Index Data Reader searches and reads
 *       documents, Search Index Data Contributor also writes them, and listing indexes or reading their definitions
 *       needs Reader or Search Service Contributor; without those the connection names its indexes, as for a query
 *       key.</li>
 * </ul>
 */
final class AzureSearchBackend extends SearchBackend {

  static final String TYPE = "azure-ai-search";
  static final String API_KEY = "/apiKey";
  static final String API_VERSION = "2025-09-01";
  /** {@code auth} of a connection that signs in with Microsoft Entra ID; any other value, or none, is the API key. */
  static final String ENTRA_ID = "entraId";
  /** The roles an Entra ID identity needs, by what it does (Azure AI Search's built-in roles). */
  static final String DATA_READER = "Search Index Data Reader";
  static final String DATA_CONTRIBUTOR = "Search Index Data Contributor";
  static final String DEFINITIONS_ROLES = "Reader or Search Service Contributor";
  /** What a 401 to an Entra ID token means: the service doesn't take role-based sign-ins, or not this tenant's. */
  static final String TOKEN_REFUSED = "The service didn't accept the Entra ID token: allow role-based access on it "
      + "(Keys: Role-based access control, or Both), and sign in to the tenant the service belongs to.";
  private static final String ROLE_DELAY = "A new role assignment can take up to 10 minutes to apply.";
  private static final Pattern ENDPOINT = Pattern.compile(
      "https://([a-z0-9][a-z0-9-]{0,58}[a-z0-9]?)\\.search\\.(windows\\.net|azure\\.us|azure\\.cn)/?");
  private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}(/[A-Za-z_][A-Za-z0-9_]{0,127}){0,9}");
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9\\-=][A-Za-z0-9_\\-=]{0,1023}");
  private static final long MAX_READ = 16L << 20;
  private static final long INDEX_TTL_NANOS = 5L * 60 * 1_000_000_000;

  /** An index definition as GateRook uses it: its key field and each field's type. */
  private record Definition(String key, Map<String, String> types, java.util.Set<String> vectors, JsonNode body,
      long atNanos) {

    /** Whether {@code field} is a collection of values (not a vector field): filtered through any/all. */
    boolean collection(String field) {
      String t = types.get(field);
      return t != null && t.startsWith("Collection(") && !vectors.contains(field);
    }
  }

  private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
  private final URI testBase;
  private final Object cloudLock = new Object();
  private CloudCredentials cloud;
  private boolean ownsCloud;
  private volatile CloudLocal local;

  /** Without the core (tests of keys, tools): Entra ID tokens of the real endpoints, made on first use. */
  AzureSearchBackend() {
    this((URI) null);
  }

  /** Tests: {@code testBase} on this computer replaces the service's host. Never a setting. */
  AzureSearchBackend(URI testBase) {
    this(null, testBase);
  }

  /** The built-in type: Entra ID tokens of the core's {@link CloudCredentials}. */
  AzureSearchBackend(CloudCredentials cloud) {
    this(cloud, null);
  }

  /**
   * Tests of Entra ID: tokens of {@code cloud}, whose test sign-in ({@code azure.login}) and test search service
   * ({@code azure.search}) are fakes, and {@code testBase}, that service, in place of the endpoint's host.
   */
  AzureSearchBackend(CloudCredentials cloud, URI testBase) {
    if (testBase != null && !ServiceHttp.loopbackHost(testBase.getHost())) {
      throw new IllegalArgumentException("A test base is on this computer");
    }
    this.testBase = testBase;
    this.cloud = cloud;
  }

  /** The credentials Entra ID tokens come from: the core's, or real endpoints' of this backend's own. */
  private CloudCredentials cloud() {
    synchronized (cloudLock) {
      if (cloud == null) {
        cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        ownsCloud = true;
      }
      return cloud;
    }
  }

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Azure AI Search";
  }

  @Override
  String description() {
    return "Indexes and documents of an Azure AI Search service: full-text, filtered and vector search, documents by "
        + "key. Document writes and deletes wait for approval.";
  }

  /** Whether the connection signs in with Microsoft Entra ID rather than an API key. */
  static boolean entra(ConnectionConfig cfg) {
    return ENTRA_ID.equals(cfg.string("/auth"));
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    URI configured = configured(cfg);
    if (entra(cfg)) {
      spec(cfg, configured);
    }
    return new ServiceHttp.Endpoint(testBase != null ? testBase : configured, List.of());
  }

  /** The service endpoint as the connection names it: lower case, without a trailing slash. */
  private static URI configured(ConnectionConfig cfg) throws ConnectorException {
    String endpoint = cfg.string("/endpoint");
    if (endpoint == null || !ENDPOINT.matcher(endpoint.strip().toLowerCase(Locale.ROOT)).matches()) {
      throw ConnectorException.badInput("Enter the service endpoint: https://<service>.search.windows.net (or "
          + ".search.azure.us, .search.azure.cn).");
    }
    String e = endpoint.strip().toLowerCase(Locale.ROOT);
    if (e.endsWith("/")) {
      e = e.substring(0, e.length() - 1);
    }
    return URI.create(e);
  }

  /**
   * The connection's Entra ID credentials ({@link AzureSpec#fromConfig}: {@code tenantId} and {@code trustedCli} at the
   * top, {@code credentials} below), in the cloud the endpoint's suffix names: {@code search.windows.net} is Azure,
   * {@code search.azure.us} Azure Government, {@code search.azure.cn} Azure China.
   */
  static AzureSpec spec(ConnectionConfig cfg, URI configured) throws ConnectorException {
    AzureCloud cloud = AzureCloud.ofSearchHost(configured.getHost());
    if (cloud == null) {
      throw ConnectorException.badInput("The service endpoint isn't an Azure AI Search service of a known cloud.");
    }
    JsonNode credentials = cfg.config().path("credentials");
    if (!credentials.isObject() || !credentials.path("source").isString()) {
      throw ConnectorException.badInput("Choose how to sign in with Microsoft Entra ID under Credentials.");
    }
    ObjectNode config = ((ObjectNode) cfg.config()).deepCopy();
    config.put("cloud", cloud.id());
    try {
      return AzureSpec.fromConfig(config, "/credentials");
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput(ServiceHttp.sentence(e.getMessage()));
    }
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    if (entra(c.cfg())) {
      return bearer(c, endpoint(c.cfg()).base());
    }
    return ServiceHttp.headers("api-key", secret(c, API_KEY, "API key"));
  }

  @Override
  Map<String, List<String>> auth(Call c, ServiceHttp.Request r) throws ConnectorException {
    return entra(c.cfg()) ? bearer(c, r.uri()) : auth(c);
  }

  /** An Entra ID token for {@code uri}: a search service of the endpoint's cloud (or a test one of a test sign-in). */
  private Map<String, List<String>> bearer(Call c, URI uri) throws ConnectorException {
    AzureSpec spec = spec(c.cfg(), configured(c.cfg()));
    if (!cloud().azure().sendsTokensTo(spec.cloud(), Audience.SEARCH, uri)) {
      throw ConnectorException.denied("GateRook sends Entra ID tokens only to Azure AI Search services of "
          + spec.cloud().id() + ", not to " + uri.getHost() + ".");
    }
    return ServiceHttp.headers("Authorization", "Bearer " + token(c, spec).value());
  }

  /** The call's token for Azure AI Search, tainted. */
  private AzureCredentials.AccessToken token(Call c, AzureSpec spec) throws ConnectorException {
    AzureCredentials.AccessToken t = cloud().azure().token(owner(c.cfg()), spec, Audience.SEARCH, c.secrets(),
        c.ctx().remaining().minus(ServiceHttp.MARGIN));
    c.ctx().taint(t.value());
    return t;
  }

  private static CloudCredentials.Owner owner(ConnectionConfig cfg) {
    return new CloudCredentials.Owner(cfg.id(), cfg.version(), cfg.name());
  }

  @Override
  void answered(Call c, ServiceHttp.Response r) {
    if (r.status() == 401 && entra(c.cfg())) {
      cloud().azure().invalidate(c.cfg().id(), Audience.SEARCH);
    }
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return entra(cfg) ? List.of("Authorization: Bearer ‹secret›") : List.of("api-key: ‹secret›");
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      JsonNode body = ServiceHttp.JSON.readTree(r.body());
      String m = body.path("error").path("message").asString(null);
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  /** With Entra ID, a 401 and a 403 say what to allow on the service and which role the request needed. */
  @Override
  ConnectorException failure(ConnectionConfig cfg, ServiceHttp.Request r, ServiceHttp.Response response) {
    if (!entra(cfg) || (response.status() != 401 && response.status() != 403)) {
      return super.failure(cfg, r, response);
    }
    String words = said(response);
    String refused = name() + " refused " + r.what() + " (" + response.status() + ")" + (words == null ? "."
        : ": " + ServiceHttp.sentence(words));
    if (response.status() == 401) {
      return ConnectorException.denied(refused + " " + TOKEN_REFUSED);
    }
    return ConnectorException.denied(refused + " " + roleNeeded(r) + " " + ROLE_DELAY + (r.change()
        ? " Nothing changed." : ""));
  }

  /**
   * The role a request of an Entra ID identity needs, by what it does: a batch of documents (the one change) writes,
   * {@code /indexes/<name>/docs/…} searches or reads documents, anything else lists indexes or reads definitions.
   */
  static String roleNeeded(ServiceHttp.Request r) {
    if (r.change()) {
      return "Writing documents needs " + DATA_CONTRIBUTOR + " on the search service.";
    }
    List<String> segments = List.of(r.uri().getRawPath().split("/", -1));
    int at = segments.indexOf("indexes");
    if (at >= 0 && segments.size() > at + 2 && segments.get(at + 2).equals("docs")) {
      return "Searching and reading documents needs " + DATA_READER + " (or " + DATA_CONTRIBUTOR + ") on the search "
          + "service.";
    }
    return "Listing indexes and reading their definitions needs " + DEFINITIONS_ROLES + " on the search service.";
  }

  @Override
  String nameProblem(String name) {
    return NAME.matcher(name).matches() && !name.contains("--") ? null : "An Azure AI Search index name has 1 to 128 "
        + "lowercase letters, digits and dashes, and starts and ends with a letter or digit.";
  }

  @Override
  Pattern fieldPattern() {
    return FIELD;
  }

  @Override
  String fieldRule() {
    return "an Azure AI Search field name starts with a letter or _, with / into complex fields.";
  }

  @Override
  String fixedIdField() {
    return null;
  }

  @Override
  String idProblem(JsonNode id) {
    return KEY.matcher(idText(id)).matches() ? null : "An Azure AI Search key has letters, digits, -, _ and = only, "
        + "doesn't start with _, and has at most 1,024 characters.";
  }

  @Override
  String queryProblem(Query q) {
    if (q.vector() != null && q.vectorField() == null) {
      return "Name the vector field of vector with vectorField: search_describe_index lists the index's fields.";
    }
    return q.sort().size() > 32 ? "Azure AI Search sorts by at most 32 fields." : null;
  }

  private static String index(String name) {
    return "/indexes/" + ServiceHttp.seg(name);
  }

  private ServiceHttp.Request get(ConnectionConfig cfg, String path, String what, String... query)
      throws ConnectorException {
    String[] all = new String[query.length + 2];
    all[0] = "api-version";
    all[1] = API_VERSION;
    System.arraycopy(query, 0, all, 2, query.length);
    return ServiceHttp.Request.get(endpoint(cfg).uri(path, all), null, what);
  }

  private ServiceHttp.Request post(ConnectionConfig cfg, String path, JsonNode body, String what, boolean change)
      throws ConnectorException {
    return ServiceHttp.Request.json("POST", endpoint(cfg).uri(path, "api-version", API_VERSION), null, body, what,
        change);
  }

  /**
   * The index's definition, or {@code null} when the credentials may not read it: a query key, or an Entra ID
   * identity without Reader or Search Service Contributor.
   */
  private Definition definition(Call c, String index, boolean required) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + index;
    Definition known = definitions.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < INDEX_TTL_NANOS) {
      return known;
    }
    ServiceHttp.Request r = get(c.cfg(), index(index), "Azure AI Search's definition of " + index);
    ServiceHttp.Response response = send(c, r, MAX_READ);
    boolean entra = entra(c.cfg());
    if (entra && response.status() == 403) {
      if (!required) {
        return null;
      }
      throw ConnectorException.denied(name() + " refused " + r.what() + " (403): a write needs the index's key field "
          + "from its definition, which this identity may not read. Assign it " + DEFINITIONS_ROLES + " on the "
          + "search service, besides " + DATA_CONTRIBUTOR + " to write. " + ROLE_DELAY);
    }
    if (!entra && (response.status() == 401 || response.status() == 403) && !required) {
      return null;
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    JsonNode body = ServiceHttp.json(response, r.what());
    String keyField = null;
    Map<String, String> types = new LinkedHashMap<>();
    java.util.Set<String> vectors = new java.util.HashSet<>();
    for (JsonNode f : body.path("fields")) {
      String name = f.path("name").asString("");
      types.put(name, f.path("type").asString(""));
      if (f.path("dimensions").isNumber()) {
        vectors.add(name);
      }
      if (f.path("key").asBoolean(false)) {
        keyField = name;
      }
    }
    if (keyField == null) {
      throw ConnectorException.resourceError("Azure AI Search described " + index + " without a key field.", null);
    }
    Definition d = new Definition(keyField, Map.copyOf(types), java.util.Set.copyOf(vectors), body, System.nanoTime());
    if (definitions.size() > 1_000) {
      definitions.clear();
    }
    definitions.put(key, d);
    return d;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> indexes(Call c) throws ConnectorException {
    ServiceHttp.Request r = get(c.cfg(), "/indexes", "Azure AI Search's index list", "$select", "name");
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if (entra(c.cfg()) && (response.status() == 401 || response.status() == 403)) {
      if (response.status() == 401) {
        throw failure(c.cfg(), r, response);
      }
      return listed(c.cfg(), response, r, "the identity", "assign it " + DEFINITIONS_ROLES + " on the search service");
    }
    if (response.status() == 401 || response.status() == 403) {
      return listed(c.cfg(), response, r);
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode i : ServiceHttp.json(response, r.what()).path("value")) {
      out.add(F.objectNode().put("name", i.path("name").asString("")));
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String index) throws ConnectorException {
    ObjectNode out = F.objectNode().put("name", index);
    Definition d = definition(c, index, false);
    if (d != null) {
      out.put("key", d.key());
      ArrayNode fields = out.putArray("fields");
      for (JsonNode f : d.body().path("fields")) {
        ObjectNode o = fields.addObject().put("name", f.path("name").asString("")).put("type", f.path("type")
            .asString(""));
        for (String flag : List.of("searchable", "filterable", "sortable", "facetable", "retrievable")) {
          if (f.path(flag).isBoolean()) {
            o.put(flag, f.get(flag).asBoolean());
          }
        }
        if (f.path("dimensions").isNumber()) {
          o.set("dimensions", f.get("dimensions"));
        }
      }
      if (d.body().path("semantic").isObject()) {
        out.put("semanticSearch", true);
      }
      try {
        JsonNode stats = json(c, get(c.cfg(), index(index) + "/stats", "Azure AI Search's statistics of " + index),
            1 << 20);
        out.set("documents", stats.path("documentCount"));
        out.set("storageSize", stats.path("storageSize"));
      } catch (ConnectorException e) {
        // Below: the count a query key may read.
      }
    } else if (entra(c.cfg())) {
      out.put("definition", "Not readable by this identity: reading index definitions needs " + DEFINITIONS_ROLES
          + ".");
    } else {
      out.put("definition", "Not readable with a query key: it may only search and read documents.");
    }
    if (!out.has("documents")) {
      try {
        ServiceHttp.Response count = send(c, get(c.cfg(), index(index) + "/docs/$count", "Azure AI Search's count of "
            + index), 64 * 1024);
        if (count.ok() && count.text().strip().matches("\\d{1,19}")) {
          out.put("documents", Long.parseLong(count.text().strip()));
        }
      } catch (ConnectorException e) {
        // The count is a nicety.
      }
    }
    return out;
  }

  @Override
  Hits search(Call c, Query q) throws ConnectorException {
    Definition d = definition(c, q.index(), false);
    ObjectNode body = F.objectNode().put("search", q.text() == null ? "*" : q.text());
    if (q.filter() != null) {
      body.put("filter", odata(q.filter(), d));
    }
    if (!q.facets().isEmpty()) {
      ArrayNode facets = body.putArray("facets");
      q.facets().forEach(facets::add);
    }
    if (!q.sort().isEmpty()) {
      List<String> order = new ArrayList<>();
      q.sort().forEach(s -> order.add(s.field() + (s.descending() ? " desc" : " asc")));
      body.put("orderby", String.join(", ", order));
    }
    if (!q.fields().isEmpty()) {
      List<String> fields = new ArrayList<>(q.fields());
      if (d != null && !fields.contains(d.key())) {
        fields.add(d.key());
      }
      body.put("select", String.join(",", fields));
    }
    if (!q.searchFields().isEmpty()) {
      body.put("searchFields", String.join(",", q.searchFields()));
    }
    if (q.vector() != null) {
      ObjectNode vq = body.putArray("vectorQueries").addObject().put("kind", "vector");
      ArrayNode v = vq.putArray("vector");
      q.vector().forEach(v::add);
      vq.put("fields", q.vectorField()).put("k", q.offset() + q.limit());
    }
    body.put("top", q.limit()).put("skip", q.offset()).put("count", true);
    JsonNode answer = json(c, post(c.cfg(), index(q.index()) + "/docs/search", body, "Azure AI Search's search of "
        + q.index(), false), MAX_READ);
    List<ObjectNode> hits = new ArrayList<>();
    for (JsonNode h : answer.path("value")) {
      ObjectNode doc = (ObjectNode) h.deepCopy();
      JsonNode score = doc.remove("@search.score");
      JsonNode reranker = doc.remove("@search.rerankerScore");
      doc.remove("@search.highlights");
      doc.remove("@search.captions");
      ObjectNode hit = F.objectNode();
      if (d != null && doc.has(d.key())) {
        hit.set("id", doc.get(d.key()));
      }
      if (reranker != null && !reranker.isNull()) {
        hit.set("score", reranker);
      } else if (score != null) {
        hit.set("score", score);
      }
      hit.set("document", doc);
      hits.add(hit);
    }
    ObjectNode facets = F.objectNode();
    for (var e : answer.path("@search.facets").properties()) {
      ObjectNode counts = facets.putObject(e.getKey());
      for (JsonNode bucket : e.getValue()) {
        counts.set(bucket.path("value").asString(bucket.path("value").toString()), bucket.path("count"));
      }
    }
    return new Hits(hits, answer.has("@odata.count") ? answer.get("@odata.count").asLong() : null, false, facets);
  }

  /** The filter as an OData {@code $filter}: text single-quoted with quotes doubled, collections through any/all. */
  static String odata(Filter.Node node, Definition d) throws ConnectorException {
    return switch (node) {
      case Filter.And a -> join(a.nodes(), " and ", d);
      case Filter.Or o -> join(o.nodes(), " or ", d);
      case Filter.Cmp cmp -> {
        String op = switch (cmp.op()) {
          case EQ -> "eq";
          case NE -> "ne";
          case GT -> "gt";
          case GTE -> "ge";
          case LT -> "lt";
          case LTE -> "le";
        };
        if (d != null && d.collection(cmp.field())) {
          if (cmp.op() == Filter.Op.EQ) {
            yield cmp.field() + "/any(t: t eq " + literal(cmp.value()) + ")";
          }
          if (cmp.op() == Filter.Op.NE) {
            yield cmp.field() + "/all(t: t ne " + literal(cmp.value()) + ")";
          }
          throw ConnectorException.badInput(cmp.field() + " is a collection: compare it with $eq, $ne, $in or $nin.");
        }
        yield cmp.field() + " " + op + " " + literal(cmp.value());
      }
      case Filter.In in -> {
        boolean text = in.values().stream().allMatch(v -> v instanceof Filter.Text);
        boolean collection = d != null && d.collection(in.field());
        if (text) {
          String delimiter = delimiter(in.values());
          StringBuilder joined = new StringBuilder();
          for (Filter.Value v : in.values()) {
            joined.append(joined.isEmpty() ? "" : delimiter).append(((Filter.Text) v).value());
          }
          String call = "search.in(" + (collection ? "t" : in.field()) + ", " + quote(joined.toString()) + ", "
              + quote(delimiter) + ")";
          if (collection) {
            yield in.not() ? in.field() + "/all(t: not " + call + ")" : in.field() + "/any(t: " + call + ")";
          }
          yield in.not() ? "not " + call : call;
        }
        List<String> parts = new ArrayList<>();
        for (Filter.Value v : in.values()) {
          parts.add((collection ? "t" : in.field()) + (in.not() ? " ne " : " eq ") + literal(v));
        }
        String joined = String.join(in.not() ? " and " : " or ", parts);
        if (collection) {
          yield in.not() ? in.field() + "/all(t: " + joined + ")" : in.field() + "/any(t: " + joined + ")";
        }
        yield "(" + joined + ")";
      }
      case Filter.Exists e -> e.field() + (e.exists() ? " ne null" : " eq null");
    };
  }

  private static String join(List<Filter.Node> nodes, String op, Definition d) throws ConnectorException {
    StringBuilder out = new StringBuilder();
    for (Filter.Node n : nodes) {
      out.append(out.isEmpty() ? "" : op).append('(').append(odata(n, d)).append(')');
    }
    return out.toString();
  }

  /** A delimiter of search.in that none of the values contains. */
  private static String delimiter(List<Filter.Value> values) throws ConnectorException {
    for (String candidate : List.of("|", ",", ";", "~", "^", "`", "#")) {
      if (values.stream().noneMatch(v -> ((Filter.Text) v).value().contains(candidate))) {
        return candidate;
      }
    }
    throw ConnectorException.badInput("The values of $in contain every delimiter Azure AI Search's search.in could "
        + "use: split the filter into $or of $eq.");
  }

  private static String literal(Filter.Value v) {
    return switch (v) {
      case Filter.Text t -> quote(t.value());
      case Filter.Bool b -> b.value() ? "true" : "false";
      default -> Filter.number(v);
    };
  }

  /** An OData string literal: single quotes, a quote doubled. */
  static String quote(String s) {
    return "'" + s.replace("'", "''") + "'";
  }

  @Override
  Documents documents(Call c, String index, List<JsonNode> ids, List<String> fields) throws ConnectorException {
    Definition d = definition(c, index, false);
    List<ObjectNode> docs = new ArrayList<>();
    List<JsonNode> missing = new ArrayList<>();
    if (d != null) {
      List<Filter.Value> values = new ArrayList<>();
      ids.forEach(id -> values.add(new Filter.Text(idText(id))));
      String delimiter = delimiter(values);
      StringBuilder joined = new StringBuilder();
      ids.forEach(id -> joined.append(joined.isEmpty() ? "" : delimiter).append(idText(id)));
      ObjectNode body = F.objectNode().put("search", "*").put("filter", "search.in(" + d.key() + ", "
          + quote(joined.toString()) + ", " + quote(delimiter) + ")").put("top", ids.size());
      if (!fields.isEmpty()) {
        List<String> f = new ArrayList<>(fields);
        if (!f.contains(d.key())) {
          f.add(d.key());
        }
        body.put("select", String.join(",", f));
      }
      JsonNode answer = json(c, post(c.cfg(), index(index) + "/docs/search", body, "Azure AI Search's documents of "
          + index, false), MAX_READ);
      List<String> found = new ArrayList<>();
      for (JsonNode h : answer.path("value")) {
        ObjectNode doc = (ObjectNode) h.deepCopy();
        doc.remove("@search.score");
        docs.add(doc);
        found.add(doc.path(d.key()).asString(""));
      }
      for (JsonNode id : ids) {
        if (!found.contains(idText(id))) {
          missing.add(id);
        }
      }
      return new Documents(docs, missing);
    }
    if (ids.size() > 20) {
      throw ConnectorException.badInput(entra(c.cfg()) ? "Without the index's definition (" + DEFINITIONS_ROLES
          + ") GateRook looks documents up one by one: name at most 20 ids."
          : "With a query key GateRook looks documents up one by one: name at most 20 ids.");
    }
    for (JsonNode id : ids) {
      ServiceHttp.Request r = fields.isEmpty() ? get(c.cfg(), index(index) + "/docs/" + ServiceHttp.seg(idText(id)),
          "Azure AI Search's document " + idText(id)) : get(c.cfg(), index(index) + "/docs/"
          + ServiceHttp.seg(idText(id)), "Azure AI Search's document " + idText(id), "$select", String.join(",",
          fields));
      ServiceHttp.Response response = send(c, r, MAX_READ);
      if (response.status() == 404) {
        missing.add(id);
      } else if (!response.ok()) {
        throw failure(c.cfg(), r, response);
      } else {
        docs.add((ObjectNode) ServiceHttp.json(response, r.what()));
      }
    }
    return new Documents(docs, missing);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    Definition d = definition(c, change.target(), true);
    return F.objectNode().put("idField", d.key());
  }

  @Override
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    Definition d = definitions.get(cfg.id() + "|" + cfg.version() + "|" + change.target());
    return F.objectNode().put("idField", d == null ? "id" : d.key());
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    String key = idFieldOf(change);
    if (!FIELD.matcher(key).matches()) {
      throw ConnectorException.badInput("Azure AI Search named no usable key field of " + change.target() + ".");
    }
    ObjectNode body = F.objectNode();
    ArrayNode value = body.putArray("value");
    if ("delete".equals(change.op())) {
      change.ids().forEach(id -> value.addObject().put("@search.action", "delete").put(key, idText(id)));
    } else {
      String action = "merge".equals(change.op()) ? "mergeOrUpload" : "upload";
      for (JsonNode d : change.records()) {
        ObjectNode doc = value.addObject().put("@search.action", action);
        d.properties().forEach(e -> {
          if (!e.getKey().startsWith("@")) {
            doc.set(e.getKey(), e.getValue());
          }
        });
        doc.put(key, idText(d.get(key)));
      }
    }
    return post(cfg, index(change.target()) + "/docs/index", body, "Azure AI Search's batch on " + change.target(),
        true);
  }

  @Override
  ObjectNode changed(Call c, Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode answer = ServiceHttp.json(r, "Azure AI Search's answer");
    int ok = 0;
    ArrayNode failed = F.arrayNode();
    for (JsonNode item : answer.path("value")) {
      if (item.path("status").asBoolean(false)) {
        ok++;
      } else {
        failed.addObject().put("id", item.path("key").asString("")).put("status", item.path("statusCode").asInt())
            .put("error", shorten(item.path("errorMessage").asString(""), 300));
      }
    }
    if (ok == 0 && !failed.isEmpty()) {
      throw ConnectorException.badInput("Azure AI Search refused every document, so nothing changed: "
          + shorten(failed.get(0).path("error").asString(""), 300));
    }
    ObjectNode out = F.objectNode().put("delete".equals(change.op()) ? "deleted" : "indexed", ok);
    out.put("applied", true);
    if (!failed.isEmpty()) {
      out.set("failed", failed);
    }
    return out;
  }

  // ── Setup helpers ───────────────────────────────────────────────────────────────────────────────────────────

  /** Tests: this computer's Azure CLI files elsewhere. */
  AzureSearchBackend local(CloudLocal local) {
    this.local = local;
    return this;
  }

  private CloudLocal cloudLocal() {
    CloudLocal own = local;
    if (own != null) {
      return own;
    }
    CloudCredentials current;
    synchronized (cloudLock) {
      current = cloud;
    }
    return new CloudLocal(current == null ? CloudEndpoints.PRODUCTION : current.endpoints());
  }

  @Override
  String signedIn(ConnectionConfig cfg) {
    try {
      return configured(cfg).getHost();
    } catch (ConnectorException e) {
      return name();
    }
  }

  @Override
  String cannotList(ConnectionConfig cfg) {
    return entra(cfg) ? "The identity may not list indexes (" + DEFINITIONS_ROLES + " may), so none can be offered: "
        + "name the ones agents may use." : "A query key may not list indexes, so none can be offered: name the ones "
        + "agents may use, or test an admin key to see them here.";
  }

  /**
   * Local suggestions of an Entra ID sign-in: the tenants of the Azure CLI's subscriptions on this computer. The only
   * one is filled in for a device sign-in or a service principal, which need one; the Azure CLI's own sign-in uses its
   * default tenant when the field is empty, and a tenant would change the command the person allowed.
   */
  @Override
  void local(ConnectionConfig cfg, Suggestions.Builder out) {
    if (!entra(cfg)) {
      return;
    }
    List<Suggestions.Option> tenants = cloudLocal().azureTenantOptions();
    String source = cfg.string("/credentials/source");
    boolean cli = source == null || source.equals(AzureSpec.Source.CLI.id());
    out.value("/tenantId", "the Azure CLI's sign-in", !cli && tenants.size() == 1 ? tenants.getFirst().value() : null,
        tenants);
  }

  /**
   * Service suggestions: the indexes the credentials see. With Entra ID the token comes as for Test connection, but
   * the Azure CLI runs only once the person allowed its command, and a device sign-in only once it happened: until
   * then a note says so and nothing is listed.
   */
  @Override
  void service(Call c, Suggestions.Builder out) throws ConnectorException {
    if (entra(c.cfg())) {
      AzureSpec spec = spec(c.cfg(), configured(c.cfg()));
      if (spec.source() == AzureSpec.Source.CLI
          && !cloud().azure().cliCommand(spec).fingerprint().equals(spec.trustedCli())) {
        out.note("Indexes appear after Test connection: it asks you to allow the Azure CLI command first.");
        return;
      }
      AzureCredentials.AccessToken token;
      try {
        token = token(c, spec);
      } catch (ConnectorException e) {
        if (spec.source() == AzureSpec.Source.DEVICE_CODE
            && e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE) {
          out.note("Indexes appear after Test connection signs you in with a code.");
          return;
        }
        throw e;
      }
      if (token.account() != null) {
        out.note("Signed in as " + token.account() + (token.tenantId() == null ? "" : " in tenant "
            + token.tenantId()) + ".");
      }
    }
    StoreSuggestions.offerList(this, c.cfg(), indexes(c), out);
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    if (entra(c.cfg())) {
      return testEntra(c);
    }
    long started = System.nanoTime();
    boolean admin;
    List<ObjectNode> all;
    try {
      ServiceHttp.Request r = get(c.cfg(), "/indexes", "Azure AI Search's index list", "$select", "name");
      ServiceHttp.Response response = send(c, r, MAX_READ);
      admin = response.ok();
      all = indexes(c);
      verifyListed(c, all);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    List<TestResult.Check> checks = new ArrayList<>();
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("indexes", true, latency, count(visible, "index", "indexes") + " agents may "
        + "use"));
    TestResult.Permissions permissions = admin
        ? new TestResult.Permissions(TestResult.Level.ADMIN, "An admin key: it may also write and delete documents "
            + "and change indexes.", null, List.of("admin key"), null)
        : new TestResult.Permissions(TestResult.Level.READ, "A query key: it may only search and read documents.",
            null, List.of(), null);
    TestResult result = TestResult.success(latency, "Azure AI Search", admin ? "admin key" : "query key", permissions)
        .withChecks(checks).withMetadata(F.objectNode().put("serverVersion", "Azure AI Search").put("apiVersion",
            API_VERSION));
    if (admin) {
      result = result.withHint(new Hint(c.cfg().env() == ai.msrv.gaterook.connector.api.Env.DEV ? Severity.INFO
          : Severity.WARNING, "azure-ai-search.admin-key", "/apiKey", "An admin key may also write documents and "
          + "change indexes. Give agents a query key, and list their indexes, unless they should write."));
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "azure-ai-search.no-indexes", "/indexes", "No index agents "
          + "may use."));
    }
    return result;
  }

  /**
   * Test connection with Entra ID, each step timed: the person's decision first (the Azure CLI command to allow, or a
   * device sign-in's code), then the token — who signed in, in which tenant — and what the identity's roles allow:
   * the index list, a search of the first index agents may use (no documents read), and its definition. Writes aren't
   * tried: only a write would tell whether the identity may write.
   */
  private TestResult testEntra(Call c) {
    List<TestResult.Check> checks = new ArrayList<>();
    AzureSpec spec;
    try {
      spec = spec(c.cfg(), configured(c.cfg()));
    } catch (ConnectorException e) {
      return TestResult.failure(TYPE + ".settings", e.getMessage(), null);
    }
    boolean cli = spec.source() == AzureSpec.Source.CLI;
    if (cli || spec.source() == AzureSpec.Source.DEVICE_CODE) {
      String step = cli ? "Azure CLI" : "device sign-in";
      long started = System.nanoTime();
      try {
        TestResult.Confirmation decision = AzureTrust.decision(cloud().azure(), spec, c.cfg().name(),
            c.ctx().remaining());
        checks.add(new TestResult.Check(step, true, ms(started), decision == null ? (cli ? "allowed" : "signed in")
            : cli ? "waiting for your decision" : "waiting for the code"));
        if (decision != null) {
          return TestResult.confirm(decision, checks);
        }
      } catch (ConnectorException e) {
        checks.add(new TestResult.Check(step, false, ms(started), e.getMessage()));
        return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null).withChecks(checks);
      }
    }
    AzureCredentials.AccessToken token;
    List<ObjectNode> all;
    boolean listed;
    boolean searched = false;
    Boolean definitionsReadable = null;
    String first = null;
    long latency;
    try {
      token = step(checks, "sign-in", () -> token(c, spec), t -> t.account() + (t.tenantId() == null ? ""
          : " in tenant " + t.tenantId()));
      long data = System.nanoTime();
      all = step(checks, "index list", () -> indexes(c), l -> l.stream().anyMatch(i -> i.has("listedBy"))
          ? "not allowed (" + DEFINITIONS_ROLES + " may list them): the connection names " + shorten(String.join(", ",
              l.stream().map(i -> i.path("name").asString("")).toList()), 200)
          : count(l.size(), "index", "indexes"));
      latency = Math.max(1, ms(data));
      listed = all.stream().noneMatch(i -> i.has("listedBy"));
      first = all.stream().map(o -> o.path("name").asString("")).filter(n -> allows(c.cfg(), n)).findFirst()
          .orElse(null);
      if (first != null) {
        String index = first;
        step(checks, "search " + index, () -> {
          ServiceHttp.Request r = post(c.cfg(), index(index) + "/docs/search", F.objectNode().put("search", "*")
              .put("top", 0).put("count", true), "Azure AI Search's search of " + index, false);
          return json(c, r, 64 * 1024).path("@odata.count");
        }, n -> n.isNumber() ? count(n.asLong(), "document") : "allowed");
        searched = true;
        long at = System.nanoTime();
        ServiceHttp.Request r = get(c.cfg(), index(index), "Azure AI Search's definition of " + index);
        ServiceHttp.Response response = send(c, r, MAX_READ);
        if (response.status() == 403) {
          definitionsReadable = false;
          checks.add(new TestResult.Check("definition of " + index, true, ms(at), "not allowed ("
              + DEFINITIONS_ROLES + " may read it)"));
        } else if (!response.ok()) {
          ConnectorException e = failure(c.cfg(), r, response);
          checks.add(new TestResult.Check("definition of " + index, false, ms(at), e.getMessage()));
          throw e;
        } else {
          definitionsReadable = true;
          checks.add(new TestResult.Check("definition of " + index, true, ms(at), "allowed"));
        }
      }
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null).withChecks(checks);
    }
    List<String> may = new ArrayList<>();
    if (searched) {
      may.add("search and read documents");
    }
    if (listed) {
      may.add("list indexes");
    }
    if (Boolean.TRUE.equals(definitionsReadable)) {
      may.add("read index definitions");
    }
    String read = may.isEmpty() ? "Not checked: no index agents may use." : "May " + and(may) + ".";
    String write = "Not checked: writing documents needs " + DATA_CONTRIBUTOR
        + (Boolean.FALSE.equals(definitionsReadable) ? ", and the index's definition (" + DEFINITIONS_ROLES
            + ") for its key field" : "") + ". GateRook's writes wait for approval.";
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN, read, write, List.of(),
        null);
    ObjectNode details = F.objectNode();
    details.put("tenant", token.tenantId()).put("account", token.account()).put("application", token.application())
        .put("cloud", spec.cloud().id());
    TestResult result = TestResult.success(latency, "Azure AI Search", token.account(), permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Azure AI Search").put("apiVersion", API_VERSION))
        .withDetails(details);
    if (first == null) {
      result = result.withHint(new Hint(Severity.WARNING, "azure-ai-search.no-indexes", "/indexes", "No index agents "
          + "may use."));
    }
    if (Boolean.FALSE.equals(definitionsReadable)) {
      result = result.withHint(new Hint(Severity.INFO, "azure-ai-search.no-definitions", "/credentials", "This "
          + "identity may not read index definitions, so search_describe_index shows no fields and writes can't "
          + "find an index's key field. Assign it " + DEFINITIONS_ROLES + " too if agents should write."));
    }
    switch (spec.source()) {
      case CLI -> result = result.withHint(new Hint(Severity.INFO, "azure-ai-search.cli", "/credentials/source",
          "Agents search with your Azure CLI sign-in: what you may read in this service, they may read through this "
              + "connection. Writes still wait for approval."));
      case DEVICE_CODE -> result = result.withHint(new Hint(Severity.INFO, "azure-ai-search.device-sign-in",
          "/credentials/source", "Device sign-ins last until GateRook restarts; then test the connection again to "
              + "sign in."));
      case CLIENT_SECRET -> {
        if (c.cfg().env() == Env.PROD) {
          result = result.withHint(new Hint(Severity.WARNING, "azure-ai-search.client-secret", "/credentials/source",
              "Use a certificate on prod: a client secret is a password that lasts up to two years."));
        }
      }
      default -> {
        // A certificate needs no hint.
      }
    }
    return result;
  }

  @FunctionalInterface
  private interface Step<T> {
    T run() throws ConnectorException;
  }

  /** One timed step of Test connection, its outcome added to {@code checks}. */
  private static <T> T step(List<TestResult.Check> checks, String name, Step<T> step,
      java.util.function.Function<T, String> detail) throws ConnectorException {
    long started = System.nanoTime();
    try {
      T value = step.run();
      checks.add(new TestResult.Check(name, true, ms(started), detail.apply(value)));
      return value;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check(name, false, ms(started), e.getMessage()));
      throw e;
    }
  }

  /** {@code a, b and c}. */
  private static String and(List<String> parts) {
    if (parts.size() == 1) {
      return parts.getFirst();
    }
    return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
  }

  private static long ms(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    ObjectNode out = F.objectNode().put("ids", "The index's key field: letters, digits, -, _ and =")
        .put("search", "Full-text (simple syntax) with OData filters, facets and sorting; vector queries on vector "
            + "fields")
        .put("apiVersion", API_VERSION);
    if (entra(cfg)) {
      out.put("signIn", "Microsoft Entra ID: the identity's roles decide what it may do (" + DATA_READER
          + " searches and reads documents, " + DATA_CONTRIBUTOR + " also writes them)");
    }
    return out;
  }

  @Override
  void forget(String connectionId) {
    definitions.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
    CloudCredentials current;
    synchronized (cloudLock) {
      current = cloud;
    }
    if (current != null) {
      current.evict(connectionId);
    }
  }

  @Override
  public void close() {
    synchronized (cloudLock) {
      if (ownsCloud && cloud != null) {
        cloud.close();
        cloud = null;
        ownsCloud = false;
      }
    }
  }
}
