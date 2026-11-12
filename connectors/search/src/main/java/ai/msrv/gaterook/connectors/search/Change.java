package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A change as a person approves it and execute runs it: what to change (the records to write or the ids to delete,
 * where) and what GateRook looked up to build the request ({@code resolved}: Pinecone's index host, Chroma's collection
 * id, Milvus's fields). The request is built from it by the same code before the approval (the preview) and after it
 * (the run), so the bytes sent are the bytes shown.
 *
 * @param op       {@code upsert} (records) or {@code delete} (ids); for search engines also {@code merge}
 * @param target   the collection or index
 * @param records  the records or documents to write; empty for a delete
 * @param ids      the ids to delete; empty for a write
 */
record Change(String op, String target, String namespace, ArrayNode records, ArrayNode ids, ObjectNode resolved) {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  Change {
    records = records == null ? F.arrayNode() : records;
    ids = ids == null ? F.arrayNode() : ids;
    resolved = resolved == null ? F.objectNode() : resolved;
  }

  Change withResolved(ObjectNode value) {
    return new Change(op, target, namespace, records, ids, value);
  }

  /** The approved payload. */
  ObjectNode payload() {
    ObjectNode out = F.objectNode().put("op", op).put("target", target);
    if (namespace != null) {
      out.put("namespace", namespace);
    }
    out.set("records", records.deepCopy());
    out.set("ids", ids.deepCopy());
    out.set("resolved", resolved.deepCopy());
    return out;
  }

  /** The change of an approved payload. */
  static Change of(JsonNode payload) throws ConnectorException {
    if (payload == null || !payload.isObject() || !payload.path("op").isString()
        || !payload.path("target").isString()) {
      throw ConnectorException.denied("The approved action carries no change.");
    }
    JsonNode ns = payload.get("namespace");
    return new Change(payload.get("op").asString(), payload.get("target").asString(),
        ns == null || ns.isNull() ? null : ns.asString(),
        payload.path("records").isArray() ? (ArrayNode) payload.get("records").deepCopy() : null,
        payload.path("ids").isArray() ? (ArrayNode) payload.get("ids").deepCopy() : null,
        payload.path("resolved").isObject() ? (ObjectNode) payload.get("resolved").deepCopy() : null);
  }

  int count() {
    return "delete".equals(op) ? ids.size() : records.size();
  }
}
