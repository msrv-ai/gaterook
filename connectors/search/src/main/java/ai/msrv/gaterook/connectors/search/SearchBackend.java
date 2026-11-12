package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One search engine behind the search_* tools (docs/search-engines.md). Hits come back in one shape —
 * {@code {"id"?, "score"?, "document"}} — and facets as {@code {field: {value: count}}}, whatever the engine calls them.
 */
abstract class SearchBackend extends StoreBackend {

  /** A sort key. */
  record Sort(String field, boolean descending) {}

  /**
   * search_query.
   *
   * @param text   the words, {@code null} or empty for every match of the filter
   * @param vector a vector to search near, with {@code vectorField}; alone or with {@code text}
   */
  record Query(String index, String text, Filter.Node filter, List<String> facets, List<Sort> sort,
      List<String> fields, List<String> searchFields, int limit, int offset, List<Double> vector,
      String vectorField) {}

  /** A page of hits: the total when the engine says it, exact or estimated. */
  record Hits(List<ObjectNode> hits, Long total, boolean exact, ObjectNode facets) {}

  /** Documents fetched by id; {@code missing}: ids that don't exist. */
  record Documents(List<ObjectNode> documents, List<JsonNode> missing) {}

  @Override
  final String listField() {
    return "indexes";
  }

  /** What a field name in a filter, a facet, a sort or a field list may be here. */
  abstract Pattern fieldPattern();

  /** The words of {@link #fieldPattern} for an agent. */
  abstract String fieldRule();

  /** Checks the filter's fields and operators for this engine, without I/O. */
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    Filter.checkFields(filter, fieldPattern(), fieldRule());
  }

  /** Why {@code q} can't run here (sorting on Algolia, a vector on Algolia), or {@code null}: no I/O. */
  abstract String queryProblem(Query q);

  /** Why {@code id} can't be a document id here, or {@code null}. */
  String idProblem(JsonNode id) {
    return null;
  }

  /** The field each document of a write must carry its id in, when known without I/O; {@code null}: looked up. */
  abstract String fixedIdField();

  /** The indexes the credentials see: {@code {name, documents?, …}}. */
  abstract List<ObjectNode> indexes(Call c) throws ConnectorException;

  /** One index: its fields or settings and its size. */
  abstract ObjectNode describe(Call c, String index) throws ConnectorException;

  abstract Hits search(Call c, Query q) throws ConnectorException;

  abstract Documents documents(Call c, String index, List<JsonNode> ids, List<String> fields)
      throws ConnectorException;

  /** What a write needs looked up first, with I/O: the id field ({@code idField}) when it isn't fixed. */
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    return F.objectNode().put("idField", fixedIdField());
  }

  /** The same without I/O, for describeAction. */
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    String field = fixedIdField();
    return F.objectNode().put("idField", field == null ? "id" : field);
  }

  /** The exact request of a change, without credentials and without I/O. */
  abstract ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException;

  /** Where a change's request goes: the connection's endpoint, or Algolia's write host. */
  ServiceHttp.Endpoint changeEndpoint(ConnectionConfig cfg, Change change) throws ConnectorException {
    return endpoint(cfg);
  }

  /**
   * What the engine answered to a sent change, after waiting a few seconds for an engine that applies it later
   * (Algolia's and Meilisearch's tasks): {@code {"indexed"|"deleted": n, "applied": true|false, …}}.
   */
  abstract ObjectNode changed(Call c, Change change, ServiceHttp.Response r) throws ConnectorException;

  /**
   * The connection's own names when the key may not list indexes: a search key of Meilisearch, Algolia or Azure AI
   * Search. Names with * can't stand for an index, so without exact names the refusal stands.
   */
  final List<ObjectNode> listed(ConnectionConfig cfg, ServiceHttp.Response response, ServiceHttp.Request r)
      throws ConnectorException {
    return listed(cfg, response, r, "the key", "use a key that may list them");
  }

  /**
   * {@link #listed}, worded for the credentials: {@code who} may not list indexes ({@code the key}), {@code fix} says
   * how it could ({@code use a key that may list them}).
   */
  final List<ObjectNode> listed(ConnectionConfig cfg, ServiceHttp.Response response, ServiceHttp.Request r,
      String who, String fix) throws ConnectorException {
    List<ObjectNode> out = new ArrayList<>();
    for (String name : cfg.strings("/indexes")) {
      if (!name.contains("*") && nameProblem(name) == null) {
        out.add(F.objectNode().put("name", name).put("listedBy", "the connection: " + who + " may not list indexes"));
      }
    }
    if (out.isEmpty()) {
      throw ConnectorException.denied(name() + " refused " + r.what() + " (" + response.status() + "): " + who
          + " may not list indexes. Name the indexes agents may use under Indexes, or " + fix + ".");
    }
    return out;
  }

  /**
   * When the key may not list indexes and the connection's names stood in for the list ({@link #listed}): one search
   * of the first, so that a key the engine refuses fails the test rather than passing it on the names alone.
   */
  final void verifyListed(Call c, List<ObjectNode> indexes) throws ConnectorException {
    if (indexes.isEmpty() || !indexes.stream().allMatch(i -> i.has("listedBy"))) {
      return;
    }
    String first = indexes.getFirst().path("name").asString("");
    try {
      search(c, new Query(first, null, null, List.of(), List.of(), List.of(), List.of(), 1, 0, null, null));
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.DENIED) {
        throw ConnectorException.denied(name() + " refused a search of " + first + " with the API key of "
            + c.cfg().name() + ": check the key under Credentials, and that it may search " + first + ".");
      }
      throw e;
    }
  }

  /** Which of a change's ids exist, for the approver; at most 100 are looked up. */
  List<JsonNode> existing(Call c, Change change) throws ConnectorException {
    List<JsonNode> ids = ids(change);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<JsonNode> asked = ids.subList(0, Math.min(ids.size(), 100));
    Documents found = documents(c, change.target(), asked, List.of(idFieldOf(change)));
    List<JsonNode> out = new ArrayList<>(asked);
    out.removeIf(id -> found.missing().stream().anyMatch(m -> m.asString().equals(id.asString())));
    return out;
  }

  /** The ids of a change: the delete's, or each document's id field. */
  static List<JsonNode> ids(Change change) {
    List<JsonNode> out = new ArrayList<>();
    if ("delete".equals(change.op())) {
      change.ids().forEach(out::add);
    } else {
      String field = idFieldOf(change);
      change.records().forEach(d -> {
        JsonNode id = d.get(field);
        if (id != null) {
          out.add(id.isString() ? id : F.stringNode(id.asString()));
        }
      });
    }
    return out;
  }

  static String idFieldOf(Change change) {
    return change.resolved().path("idField").asString("id");
  }

  /** A document id as text: ids are text or integers. */
  static String idText(JsonNode id) {
    return id.isString() ? id.asString() : Long.toString(id.asLong());
  }
}
