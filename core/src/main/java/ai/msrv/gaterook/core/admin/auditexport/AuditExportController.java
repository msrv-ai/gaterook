package ai.msrv.gaterook.core.admin.auditexport;

import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.audit.AuditExports;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settings › Audit & retention › Export (ARCHITECTURE.md §6 settings, kit {@code settings.html#settings-audit}):
 * destinations of every kind, each with its schedule and state; Test, Export now and Retry. A secret arrives in a
 * {@code POST} or {@code PUT} under {@code secrets} (keyed by its field, {@code /credentials/secretAccessKey}) and
 * never leaves: answers say what is stored for it, not its value. Every change is a Setup change.
 */
@RestController
@RegisterReflectionForBinding({AuditExports.Destination.class, AuditExports.StoredSecret.class,
    AuditExports.Tested.class, AuditExportController.Body.class, AuditExportController.SecretBody.class,
    AuditExportController.ReasonBody.class})
class AuditExportController {

  /**
   * {@code POST} and {@code PUT}: what the form sends. On {@code PUT} a missing field keeps its value and a
   * {@code config} replaces the whole config; {@code version} is the one the form read (409 when stale).
   */
  record Body(String kind, String name, Boolean enabled, JsonNode config, String schedule,
      Map<String, SecretBody> secrets, Long version, String reason) {}

  /** A secret field: a value to store here ({@code local}), or a reference to a connected store. */
  record SecretBody(String source, char[] value, String ref, String store) {

    @Override
    public String toString() {
      return "SecretBody[source=" + source + (value == null ? "" : ", value=‹redacted›") + "]";
    }
  }

  record ReasonBody(String reason) {}

  private final AuditExports exports;
  private final JsonMapper json;

  AuditExportController(AuditExports exports, JsonMapper json) {
    this.exports = exports;
    this.json = json;
  }

  @GetMapping("/api/audit-export")
  List<AuditExports.Destination> list() {
    return exports.list();
  }

  @GetMapping("/api/audit-export/{id}")
  AuditExports.Destination get(@PathVariable String id) {
    return exports.get(id);
  }

  @PostMapping("/api/audit-export")
  @ResponseStatus(HttpStatus.CREATED)
  AuditExports.Destination create(@RequestBody Body body) {
    try {
      String reason = Reasons.check(body.reason());
      return exports.create(new AuditExports.Draft(body.kind(), body.name(), body.enabled(), body.config(),
          body.schedule(), secrets(body.secrets())), reason);
    } finally {
      clear(body.secrets());
    }
  }

  @PutMapping("/api/audit-export/{id}")
  AuditExports.Destination update(@PathVariable String id, @RequestBody Body body) {
    try {
      String reason = Reasons.check(body.reason());
      return exports.update(id, new AuditExports.Patch(body.name(), body.enabled(), body.config(), body.schedule(),
          secrets(body.secrets()), body.version()), reason);
    } finally {
      clear(body.secrets());
    }
  }

  @DeleteMapping("/api/audit-export/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestBody(required = false) ReasonBody body) {
    exports.delete(id, Reasons.check(body == null ? null : body.reason()));
  }

  /** Test: the destination as it is, and what the test found ({@code test}); nothing is exported. */
  @PostMapping("/api/audit-export/{id}/test")
  ObjectNode test(@PathVariable String id) {
    AuditExports.Tested tested = exports.test(id);
    ObjectNode answer = json.valueToTree(exports.get(id));
    answer.set("test", json.valueToTree(tested));
    return answer;
  }

  /** Export now / Retry: what the destination hasn't received yet, now; its state afterwards. */
  @PostMapping("/api/audit-export/{id}/run")
  AuditExports.Destination run(@PathVariable String id) {
    return exports.run(id);
  }

  private static Map<String, AuditExports.SecretInput> secrets(Map<String, SecretBody> given) {
    if (given == null) {
      return null;
    }
    Map<String, AuditExports.SecretInput> out = new LinkedHashMap<>();
    given.forEach((field, s) -> {
      if (s != null) {
        out.put(field, new AuditExports.SecretInput(s.source(), s.value() == null ? null : s.value().clone(),
            s.ref(), s.store()));
      }
    });
    return out;
  }

  private static void clear(Map<String, SecretBody> given) {
    if (given != null) {
      given.values().forEach(s -> {
        if (s != null && s.value() != null) {
          Arrays.fill(s.value(), '\0');
        }
      });
    }
  }
}
