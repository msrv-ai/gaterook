package ai.msrv.gaterook.core.admin.templates;

import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.admin.approvals.ApprovalDtos;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connectors › Templates (ARCHITECTURE.md §6 connectors, templates): no-code tools of SQL queries, SSH runbooks and
 * OpenAPI operations, stored in append-only versions and compiled by the connector's {@code TemplateKind} against the
 * connection they run on. A save that doesn't compile stores nothing and answers with the compiler's errors.
 */
@RestController
@RegisterReflectionForBinding({TemplatesController.Template.class, TemplatesController.Saved.class,
    TemplatesController.Compile.class, TemplatesController.CompiledTool.class, TemplatesController.Version.class,
    TemplatesController.ParseOpenApi.class, TemplatesController.Parsed.class, TemplatesController.Operation.class,
    TemplatesController.Source.class,
    ApprovalDtos.Preview.class, ApprovalDtos.Action.class, ApprovalDtos.Field.class, ApprovalDtos.Diff.class,
    ApprovalDtos.Hunk.class, ApprovalDtos.DiffLine.class})
class TemplatesController {

  /** {@code POST /api/templates}: {@code catalogRef} imports a catalog template (K6). */
  record Create(String kind, String connection, String name, JsonNode definition, String note, String catalogRef,
      String reason) {}

  record Update(JsonNode definition, Integer baseVersion, String note, String reason) {}

  record PreviewRequest(JsonNode arguments) {}

  record Reason(String reason) {}

  /**
   * One template; {@code verified} false: changed outside MCP Gateway, it serves nothing until reviewed.
   *
   * @param parameters its signature: the current version's {@code params} ({@code [{name, type, required, values,
   *                   default}]}, "orders_by_status(status, since)"); {@code null} for OpenAPI and when not verified
   * @param definition the current version's definition, on {@code GET /api/templates/{id}} only (the editor and
   *                   Policies' runbook cards); {@code null} in lists and when it doesn't verify
   */
  record Template(String id, String kind, String name, String connection, int version, String status,
      String catalogRef, JsonNode natures, Instant updatedAt, boolean verified, JsonNode parameters,
      JsonNode definition) {}

  /** What compilation said; {@code ok} false means nothing was saved (the template stays at its version). */
  record Compile(boolean ok, List<CompiledTool> tools, List<String> errors, List<String> warnings, String digest) {}

  record CompiledTool(String opKey, String name, String title, String nature) {}

  record Saved(Template template, Compile compile) {}

  record Version(int version, String digest, JsonNode natures, String note, Instant createdAt) {}

  /**
   * {@code POST /api/templates/openapi/parse}: the document as {@code url}, {@code blob} (Base64 of the file) or
   * {@code text}, and the person's choices so far ({@code select}: operation keys).
   */
  record ParseOpenApi(String connection, String url, String blob, String text, String name, String prefix,
      String server, List<String> select) {}

  /** One operation of the document; {@code skipped} says why it can't become a tool. */
  record Operation(String opKey, String name, String method, String path, String summary, List<String> tags,
      String nature, boolean selected, String skipped) {}

  /** Where the document came from: {@code file} or {@code url}. */
  record Source(String kind, String url, long bytes, String sha256) {}

  /**
   * The proposal: {@code definition} (for {@code POST /api/templates {kind:"openapi"}}) holds the selected
   * operations; {@code ok} false means the document can't be used ({@code errors}).
   */
  record Parsed(boolean ok, JsonNode definition, List<Operation> operations, JsonNode info, List<String> errors,
      List<String> warnings, Source source) {}

  private final TemplateService templates;
  private final OpenApiImports openApi;

  TemplatesController(TemplateService templates, OpenApiImports openApi) {
    this.templates = templates;
    this.openApi = openApi;
  }

  @GetMapping("/api/templates")
  List<Template> list(@RequestParam(name = "kind", required = false) String kind) {
    return templates.list(kind == null || kind.isBlank() ? null : kind).stream().map(TemplatesController::template)
        .toList();
  }

  @GetMapping("/api/templates/{id}")
  Template get(@PathVariable String id) {
    TemplateService.TemplateInfo t = templates.get(id);
    JsonNode definition = templates.definition(id).orElse(null);
    return new Template(t.id(), t.kind(), t.name(), t.connectionName(), t.version(), t.status(), t.catalogRef(),
        t.natures(), t.updatedAt(), t.verified(), t.parameters(), definition);
  }

  @PostMapping("/api/templates")
  ResponseEntity<Saved> create(@RequestBody Create request) {
    TemplateService.SaveResult r;
    if (request.catalogRef() != null && !request.catalogRef().isBlank()) {
      // A template of the signed catalog (K6): one template per definition, its read-only confirmations dropped.
      if (request.connection() == null || request.connection().isBlank()) {
        throw ApiRequestException.unprocessable("Connection missing", "Say which connection it runs on.",
            "/connection");
      }
      r = templates.createFromCatalog(request.catalogRef().strip(), request.kind(), request.connection(),
          Reasons.check(request.reason()));
    } else {
      r = templates.create(request.kind(), request.connection(), request.name(), request.definition(),
          request.note(), Reasons.check(request.reason()));
    }
    Saved saved = saved(r);
    // A definition that doesn't compile is no error of the request: the answer is what the compiler said.
    return ResponseEntity.status(r.template() == null ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
  }

  @PutMapping("/api/templates/{id}")
  Saved update(@PathVariable String id, @RequestBody Update request) {
    if (request.baseVersion() == null) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/baseVersion");
    }
    TemplateService.SaveResult r = templates.update(id, request.definition(), request.baseVersion(), request.note(),
        Reasons.check(request.reason()));
    return saved(r);
  }

  @PostMapping("/api/templates/{id}/preview")
  ApprovalDtos.Preview preview(@PathVariable String id, @RequestBody(required = false) PreviewRequest request) {
    JsonNode args = request == null ? null : request.arguments();
    if (args != null && !args.isNull() && !args.isObject()) {
      throw ApiRequestException.unprocessable("Invalid arguments", "Arguments are a JSON object.", "/arguments");
    }
    ObjectNode arguments = args == null || args.isNull() ? JsonNodeFactory.instance.objectNode()
        : (ObjectNode) args.deepCopy();
    return ApprovalDtos.preview(templates.preview(id, arguments));
  }

  @GetMapping("/api/templates/{id}/versions")
  List<Version> versions(@PathVariable String id) {
    return templates.versions(id).stream().map(v -> new Version(v.version(), v.digest(), v.natures(), v.note(),
        v.createdAt())).toList();
  }

  @PostMapping("/api/templates/{id}/enable")
  Template enable(@PathVariable String id, @RequestBody(required = false) Reason request) {
    templates.setStatus(id, true, request == null ? null : Reasons.check(request.reason()));
    return template(templates.get(id));
  }

  @PostMapping("/api/templates/{id}/disable")
  Template disable(@PathVariable String id, @RequestBody(required = false) Reason request) {
    templates.setStatus(id, false, request == null ? null : Reasons.check(request.reason()));
    return template(templates.get(id));
  }

  @PostMapping("/api/templates/{id}/confirm-integrity")
  Template confirmIntegrity(@PathVariable String id, @RequestBody(required = false) Reason request) {
    return template(templates.confirmIntegrity(id, request == null ? null : Reasons.check(request.reason())));
  }

  @DeleteMapping("/api/templates/{id}")
  ResponseEntity<Void> delete(@PathVariable String id, @RequestBody(required = false) Reason request) {
    templates.delete(id, request == null ? null : Reasons.check(request.reason()));
    return ResponseEntity.noContent().build();
  }

  /**
   * OpenAPI import, step one: the document read into every operation and the definition of the selected ones;
   * nothing is saved. A document that can't be used is no error of the request: {@code ok} false with the errors.
   */
  @PostMapping("/api/templates/openapi/parse")
  Parsed parseOpenApi(@RequestBody ParseOpenApi request) {
    ObjectNode options = JsonNodeFactory.instance.objectNode();
    if (request.name() != null) {
      options.put("name", request.name());
    }
    if (request.prefix() != null) {
      options.put("prefix", request.prefix());
    }
    if (request.server() != null) {
      options.put("server", request.server());
    }
    if (request.select() != null) {
      var select = options.putArray("select");
      request.select().forEach(select::add);
    }
    var result = openApi.parse(request.connection(), request.url(), request.blob(), request.text(), options);
    List<Operation> operations = result.operations().stream().map(o -> new Operation(o.opKey(), o.name(),
        o.method(), o.path(), o.summary(), o.tags(), o.nature(), o.selected(), o.skipped())).toList();
    JsonNode info = result.info();
    Source source = new Source(request.url() != null && !request.url().isBlank() ? "url" : "file",
        request.url() != null && !request.url().isBlank() ? request.url() : null,
        info == null ? 0 : info.path("bytes").asLong(0), info == null ? null : info.path("sha256").asString(null));
    return new Parsed(result.ok(), result.definition(), operations, info, result.errors(), result.warnings(), source);
  }

  private static Saved saved(TemplateService.SaveResult r) {
    return new Saved(r.template() == null ? null : template(r.template()), compile(r.compile()));
  }

  private static Compile compile(TemplateKind.CompileResult c) {
    if (c == null) {
      return null;
    }
    List<CompiledTool> tools = c.tools() == null ? List.of() : c.tools().stream().map(t -> new CompiledTool(
        t.opKey(), t.definition().name(), t.definition().title(), t.definition().nature().name()
            .toLowerCase(Locale.ROOT))).toList();
    return new Compile(c.ok(), tools, c.errors() == null ? List.of() : List.copyOf(c.errors()),
        c.warnings() == null ? List.of() : List.copyOf(c.warnings()), c.digest());
  }

  private static Template template(TemplateService.TemplateInfo t) {
    return new Template(t.id(), t.kind(), t.name(), t.connectionName(), t.version(), t.status(), t.catalogRef(),
        t.natures(), t.updatedAt(), t.verified(), t.parameters(), null);
  }
}
