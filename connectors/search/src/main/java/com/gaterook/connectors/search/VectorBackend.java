package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Suggestions;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One vector database behind the vector_* tools (docs/vector-databases.md). Records come back in one shape —
 * {@code {"id", "score"?, "metadata", "document"?, "vector"?}} — whatever the store calls them (points, entities,
 * objects, vectors, embeddings).
 */
abstract class VectorBackend extends StoreBackend {

  /** vector_get: records by id, or those a filter matches, a page at a time. */
  record Get(String collection, String namespace, List<JsonNode> ids, Filter.Node filter, int limit, String cursor,
      boolean vectors) {}

  /** A page of records; {@code missing}: ids asked for that don't exist. */
  record Page(List<ObjectNode> records, String nextCursor, List<JsonNode> missing) {}

  /** How a text is searched. */
  enum TextMode {
    SEMANTIC, KEYWORD, HYBRID;

    String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * vector_query: the records nearest to a vector, to a stored record ({@code id}), or to a text the store embeds or
   * matches itself; exactly one of the three.
   */
  record Query(String collection, String namespace, List<Double> vector, JsonNode id, String text, TextMode mode,
      Filter.Node filter, int topK, String vectorName, boolean vectors) {}

  @Override
  final String listField() {
    return "collections";
  }

  /** What vector_*'s {@code namespace} is here (a Pinecone namespace, a Weaviate tenant), or {@code null}: none. */
  abstract String namespaceWord();

  /** Why {@code id} can't be a record id here (a string or an integer), or {@code null}. */
  abstract String idProblem(JsonNode id);

  /** Checks the fields of a filter against this store's names. */
  abstract void checkFilter(Filter.Node filter) throws Filter.Invalid;

  /** Why {@code q} can't run here whatever the collection (a text on Chroma), or {@code null}: no I/O. */
  abstract String queryProblem(Query q);

  /** Whether a record carries a text of its own besides its metadata (Chroma's documents). */
  boolean documents() {
    return false;
  }

  /** The collections the credentials see: {@code {name, dimension?, metric?, records?}}. */
  abstract List<ObjectNode> collections(Call c) throws ConnectorException;

  /** Service suggestions: the collections the credentials see, for the connection's list. */
  @Override
  void service(Call c, Suggestions.Builder out) throws ConnectorException {
    StoreSuggestions.offerList(this, c.cfg(), collections(c), out);
  }

  /** One collection: its vectors, fields and size. */
  abstract ObjectNode describe(Call c, String collection) throws ConnectorException;

  abstract Page get(Call c, Get g) throws ConnectorException;

  abstract List<ObjectNode> query(Call c, Query q) throws ConnectorException;

  /** What a change needs looked up first, with I/O (Pinecone's index host, Chroma's collection id). */
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    return F.objectNode();
  }

  /** The same without I/O, for describeAction: from what the backend remembers, else placeholders. */
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    return F.objectNode();
  }

  /** The exact request of a change, without credentials and without I/O. */
  abstract ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException;

  /** Where a change's request goes: the connection's endpoint, or Pinecone's index host. */
  ServiceHttp.Endpoint changeEndpoint(ConnectionConfig cfg, Change change) throws ConnectorException {
    return endpoint(cfg);
  }

  /**
   * What the store answered to a sent change: {@code {"upserted"|"deleted": n, …}}. A 2xx answer that reports a
   * failure (Milvus's code, Weaviate's errors per object) is thrown.
   */
  abstract ObjectNode changed(Change change, ServiceHttp.Response r) throws ConnectorException;

  /** A filter in MongoDB's form (Pinecone, Chroma), as the exception the core counts when the store can't take it. */
  static JsonNode mongo(Filter.Node node) throws ConnectorException {
    try {
      return Filter.mongo(node, true);
    } catch (Filter.Invalid e) {
      throw ConnectorException.badInput(e.getMessage());
    }
  }

  /** Which of a change's ids exist, for the approver; at most 100 are looked up. */
  List<JsonNode> existing(Call c, Change change) throws ConnectorException {
    List<JsonNode> ids = new ArrayList<>();
    if ("delete".equals(change.op())) {
      change.ids().forEach(ids::add);
    } else {
      change.records().forEach(r -> ids.add(r.get("id")));
    }
    if (ids.isEmpty()) {
      return List.of();
    }
    List<JsonNode> asked = ids.subList(0, Math.min(ids.size(), 100));
    Page page = get(c, new Get(change.target(), change.namespace(), asked, null, asked.size(), null, false));
    List<JsonNode> found = new ArrayList<>();
    for (ObjectNode r : page.records()) {
      found.add(r.get("id"));
    }
    return found;
  }
}
