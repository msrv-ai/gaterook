package ai.msrv.gaterook.connectors.support;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Turns a document a person brought (an OpenAPI file) into the definition of a no-code template, before anything is
 * saved: {@code POST /api/templates/openapi/parse} (ARCHITECTURE.md §6 connectors). Offered by a built-in
 * connector's {@code TemplateKind} of the same {@link #kind}; the core finds it among the kinds it already knows and
 * never sees the connector's classes.
 *
 * <p>What comes back is a proposal for the person to review: every operation of the document with what it would
 * become, and the definition of the operations selected, which {@code POST /api/templates} then compiles and stores
 * as any other definition. The document itself is not part of the definition.
 */
public interface TemplateImporter {

  /** The template kind the definitions are for ({@code openapi}). */
  String kind();

  /**
   * Reads a document and proposes a definition. Never throws for a document it can't use: the result's errors say
   * why ("Only OpenAPI 3.0 and 3.1", "External $ref …"); no I/O.
   *
   * @param document the bytes as uploaded or downloaded (JSON or YAML), already size-checked by the caller
   * @param runsOn   the connection the template will run on (its base URLs decide the server)
   * @param options  the person's choices so far, or {@code null}: {@code name}, {@code prefix}, {@code server},
   *                 {@code select} (operation keys; absent: every operation of a small document, none of a large one)
   */
  Result importDocument(byte[] document, ConnectionConfig runsOn, JsonNode options);

  /**
   * One operation of the document.
   *
   * @param opKey    {@code POST /refunds}: the key the template and the endpoints' tool modes use
   * @param name     the tool it becomes ({@code billing_create_refund}), or {@code null} if it is skipped
   * @param nature   {@code read} or {@code mutation}
   * @param selected whether the proposed definition includes it
   * @param skipped  why it can't become a tool ({@code multipart/form-data body}), or {@code null}
   */
  record Operation(String opKey, String name, String method, String path, String summary, List<String> tags,
      String nature, boolean selected, String skipped) {

    public Operation {
      Objects.requireNonNull(opKey, "opKey");
      tags = tags == null ? List.of() : List.copyOf(tags);
    }
  }

  /**
   * The proposal.
   *
   * @param definition the definition of the selected operations, or {@code null} when the document can't be used
   * @param info       what the document says about itself (title, version, OpenAPI version, servers, counts)
   */
  record Result(JsonNode definition, List<Operation> operations, JsonNode info, List<String> errors,
      List<String> warnings) {

    public Result {
      operations = operations == null ? List.of() : List.copyOf(operations);
      errors = errors == null ? List.of() : List.copyOf(errors);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean ok() {
      return errors.isEmpty() && definition != null;
    }
  }
}
