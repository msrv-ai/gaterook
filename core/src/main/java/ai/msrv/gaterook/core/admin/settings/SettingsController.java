package ai.msrv.gaterook.core.admin.settings;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.FileGrants;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.core.settings.DatabaseBackupJob;
import ai.msrv.gaterook.core.settings.SettingsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settings (ARCHITECTURE.md §6 settings, kit {@code settings.html}): the sections General, Appearance, Authorization,
 * Approval rules, Local API, Audit & retention and Backup, plus the database backup itself. Two values live elsewhere
 * and are shown here: "Require a comment on prod" is the prod policy's {@code approvals.comment} (one source of truth,
 * the approval checks it), and the port of the Local API is Electron's ({@code desktop.json}): the core never stores
 * it, it asks for a restart on the new port with the event {@code core.restart-required}.
 */
@RestController
@RegisterReflectionForBinding({SettingsController.Section.class, DatabaseBackupJob.Status.class,
    DatabaseBackupJob.Copy.class})
class SettingsController {

  /** {@code values} as the form shows them, {@code version} to send back with the change ({@code 0}: never saved). */
  record Section(String name, ObjectNode values, long version, Instant updatedAt) {}

  record BackupRequest(String saveToken) {}

  private final SettingsService settings;
  private final PolicyService policies;
  private final DatabaseBackupJob backups;
  private final FileGrants files;
  private final Bootstrap bootstrap;
  private final LocalListener listener;
  private final EventBus events;

  SettingsController(SettingsService settings, PolicyService policies, DatabaseBackupJob backups, FileGrants files,
      Bootstrap bootstrap, LocalListener listener, EventBus events) {
    this.settings = settings;
    this.policies = policies;
    this.backups = backups;
    this.files = files;
    this.bootstrap = bootstrap;
    this.listener = listener;
    this.events = events;
  }

  @GetMapping("/api/settings")
  Map<String, Section> all() {
    Map<String, Section> out = new LinkedHashMap<>();
    settings.all().forEach((name, s) -> out.put(name, view(s)));
    return out;
  }

  @GetMapping("/api/settings/{section}")
  Section get(@PathVariable String section) {
    return view(settings.get(section));
  }

  /**
   * {@code PUT /api/settings/{section} {…, version, reason}}: the section's fields as a merge patch. Approval rules
   * also take {@code requireCommentOnProd}; Local API also takes {@code port}.
   */
  @PutMapping("/api/settings/{section}")
  Section put(@PathVariable String section, @RequestBody JsonNode body) {
    if (body == null || !body.isObject()) {
      throw ApiRequestException.unprocessable("Invalid settings", "Send the section's fields as an object.", "");
    }
    ObjectNode patch = (ObjectNode) body.deepCopy();
    JsonNode version = patch.remove("version");
    JsonNode reasonNode = patch.remove("reason");
    String reason = Reasons.check(reasonNode == null || reasonNode.isNull() ? null : reasonNode.asString());
    if (version == null || !version.isIntegralNumber()) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/version");
    }
    JsonNode comment = "approval-rules".equals(section) ? patch.remove("requireCommentOnProd") : null;
    JsonNode port = "local-api".equals(section) ? patch.remove("port") : null;
    Integer newPort = port == null || port.isNull() ? null : checkPort(port);
    for (String derived : new String[] {"address", "publicPort", "perEnvironment"}) {
      patch.remove(derived);
    }
    if (comment != null && !comment.isNull() && !comment.isBoolean()) {
      throw ApiRequestException.unprocessable("Invalid setting", "requireCommentOnProd is true or false.",
          "/requireCommentOnProd");
    }
    SettingsService.Section before = settings.get(section);
    if (before.version() != version.asLong()) {
      throw new org.springframework.dao.OptimisticLockingFailureException("Settings " + section + " are at version "
          + before.version());
    }
    // The policy first: a refused policy change leaves the section as it was too.
    if (comment != null && !comment.isNull()) {
      requireCommentOnProd(comment.asBoolean(), reason);
    }
    SettingsService.Section after = patch.isEmpty() ? before : settings.update(section, patch, version.asLong(),
        reason);
    if ("local-api".equals(section)) {
      boolean originsChanged = !Objects.equals(before.values().path("allowedOrigins"),
          after.values().path("allowedOrigins"));
      if (newPort != null && newPort != listener.port()) {
        restartRequired(newPort, "port");
      } else if (originsChanged) {
        // The guard reads the origins once; Electron restarts the core on the same port to apply them.
        restartRequired(listener.port(), "allowed-origins");
      }
    }
    return view(settings.get(section));
  }

  // ── Backup ──────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/backup")
  DatabaseBackupJob.Status backup() {
    return backups.status();
  }

  /** "Back up now": into the backup folder, or to the file of a save dialog ({@code saveToken}). */
  @PostMapping("/api/backup/db")
  DatabaseBackupJob.Copy backUpNow(@RequestBody(required = false) BackupRequest request) {
    Path chosen = null;
    if (request != null && request.saveToken() != null && !request.saveToken().isBlank()) {
      try {
        chosen = files.take(request.saveToken(), FileGrants.Purpose.DB_BACKUP);
      } catch (IllegalArgumentException e) {
        throw ApiRequestException.unprocessable("Choose the file again", e.getMessage(), "/saveToken");
      }
    }
    try {
      return backups.now(chosen);
    } catch (UncheckedIOException e) {
      throw ApiRequestException.unprocessable("Backup failed", e.getMessage(), chosen == null ? null : "/saveToken");
    }
  }

  // ── Derived values ──────────────────────────────────────────────────────────────────────────────────────────

  private Section view(SettingsService.Section s) {
    ObjectNode values = s.values().deepCopy();
    switch (s.name()) {
      case "approval-rules" -> {
        values.put("requireCommentOnProd", "required".equals(policies.section(Env.PROD, "approvals")
            .path("comment").asString(null)));
        ObjectNode perEnv = values.putObject("perEnvironment");
        for (Env env : Env.values()) {
          JsonNode a = policies.section(env, "approvals");
          ObjectNode row = perEnv.putObject(env.id());
          row.set("timeoutMinutes", a.path("timeoutMinutes").deepCopy());
          row.set("comment", a.path("comment").deepCopy());
          row.set("announceIn", a.path("announceIn").deepCopy());
        }
      }
      case "local-api" -> {
        values.put("address", "127.0.0.1");
        values.put("port", listener.port());
        values.put("publicPort", bootstrap.publicPort());
      }
      default -> { }
    }
    return new Section(s.name(), values, s.version(), s.updatedAt());
  }

  /** The prod policy's comment rule: {@code required}, or {@code optional} when the person turns the rule off. */
  private void requireCommentOnProd(boolean required, String reason) {
    PolicyService.EnvPolicy prod = policies.get(Env.PROD);
    String current = prod.sections().path("approvals").path("comment").asString(null);
    String wanted = required ? "required" : "optional";
    if (wanted.equals(current) || (!required && !"required".equals(current))) {
      return;
    }
    ObjectNode sections = JsonNodeFactory.instance.objectNode();
    sections.putObject("approvals").put("comment", wanted);
    policies.update(Env.PROD, sections, prod.version(), reason, null);
  }

  private int checkPort(JsonNode port) {
    if (!port.isIntegralNumber() || port.asInt() < 1024 || port.asInt() > 65535) {
      throw ApiRequestException.unprocessable("Invalid port", "Use a port from 1024 to 65535.", "/port");
    }
    int value = port.asInt();
    if (value == bootstrap.publicPort()) {
      throw ApiRequestException.unprocessable("Port taken", value + " is the tunnel's port. Choose another one.",
          "/port");
    }
    if (value != listener.port() && !free(value)) {
      throw ApiRequestException.unprocessable("Port in use", "Another program listens on " + value + ". Choose "
          + "another one.", "/port");
    }
    return value;
  }

  private static boolean free(int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private void restartRequired(int port, String why) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("port", port);
    data.put("reason", why);
    events.publish("core.restart-required", data);
  }
}
