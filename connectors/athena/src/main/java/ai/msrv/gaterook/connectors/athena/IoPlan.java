package ai.msrv.gaterook.connectors.athena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Athena's answer to {@code EXPLAIN (TYPE IO, FORMAT JSON) <statement>}, written by its Trino engine (version 3): the
 * tables the statement reads ({@code inputTableColumnInfos}) and the table it writes ({@code outputTable}), each as
 * catalog, schema (Athena's database) and table. Views count as the tables read through them. A data definition
 * statement isn't planned: the answer is the statement's own text, which is {@link #planned()} {@code false}. GateRook
 * asks only for the plan of queries (a change's read of what it reads included), never of a write, so a plan that
 * names a table written is refused. Copied from the Trino connector (connectors don't share internals).
 *
 * @param inputs  the tables read, in the plan's order
 * @param output  the table written, or {@code null}
 * @param planned whether the statement was planned (a JSON IO plan) rather than echoed as a data definition statement
 */
record IoPlan(List<Table> inputs, Table output, boolean planned) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A table of the plan, lower case as Trino names it. */
  record Table(String catalog, String schema, String table) {
    String text() {
      return catalog + "." + schema + "." + table;
    }
  }

  IoPlan {
    inputs = List.copyOf(inputs);
  }

  /**
   * Reads the one cell of the {@code EXPLAIN} answer. A JSON object without {@code inputTableColumnInfos} or with a
   * table that lacks its catalog, schema or name is no plan GateRook can read: {@code null}, and the call refuses it.
   */
  static IoPlan parse(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.strip();
    if (!trimmed.startsWith("{")) {
      return new IoPlan(List.of(), null, false);
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(trimmed);
    } catch (JacksonException e) {
      return null;
    }
    JsonNode infos = tree.get("inputTableColumnInfos");
    if (infos == null || !infos.isArray()) {
      return null;
    }
    List<Table> inputs = new ArrayList<>();
    for (JsonNode info : infos) {
      Table t = table(info.path("table"));
      if (t == null) {
        return null;
      }
      inputs.add(t);
    }
    JsonNode out = tree.get("outputTable");
    Table output = null;
    if (out != null && !out.isNull()) {
      output = table(out);
      if (output == null) {
        return null;
      }
    }
    return new IoPlan(inputs, output, true);
  }

  private static Table table(JsonNode n) {
    String catalog = n.path("catalog").asString(null);
    String schema = n.path("schemaTable").path("schema").asString(null);
    String table = n.path("schemaTable").path("table").asString(null);
    if (catalog == null || schema == null || table == null) {
      return null;
    }
    return new Table(catalog.toLowerCase(Locale.ROOT), schema.toLowerCase(Locale.ROOT),
        table.toLowerCase(Locale.ROOT));
  }

  /** The tables read, as {@code catalog.schema.table}, each once. */
  List<String> inputNames() {
    return inputs.stream().map(Table::text).distinct().toList();
  }
}
