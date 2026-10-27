package ai.msrv.gaterook.core.admin.secrets;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.FileGrants;
import ai.msrv.gaterook.core.secrets.MasterKeyException;
import ai.msrv.gaterook.core.secrets.MasterKeyService;
import ai.msrv.gaterook.core.secrets.MasterKeyStatus;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The master key card of Secrets, onboarding step 1 and the locked screen of core-starting (ARCHITECTURE.md §6,
 * §7.1): status, rotation, backup, fix permissions, restore and create; plus Electron's
 * {@code /internal/keyset/protect}. Files come only as one-time tokens of {@link FileGrants}.
 */
@RestController
@RegisterReflectionForBinding({MasterKeyStatus.class, MasterKeyStatus.Key.class, MasterKeyStatus.Rotation.class,
    MasterKeyController.BackedUp.class, MasterKeyController.Restored.class, MasterKeyController.Created.class,
    MasterKeyController.Status.class, MasterKeyController.ProtectedBy.class, MasterKeyController.References.class})
class MasterKeyController {

  record Reason(String reason) {}

  record Backup(String saveToken, char[] passphrase) {}

  record BackedUp(String path, Instant at, List<Long> keyIds, boolean passphrase, List<String> warnings) {}

  record Restore(String openToken, char[] passphrase, String reason) {}

  record Restored(int secretsReadable) {}

  record Create(String confirm, String reason) {}

  record Created(List<String> needValue) {}

  /**
   * Whose secrets the key protects, one kind of owner each ("6 connections, the github-mcp server token and the Vault
   * sign-in").
   *
   * @param kind    {@code connection}, {@code mcp-server}, {@code store} (an external store's sign-in),
   *                {@code notification}, {@code tunnel} or {@code audit-export}
   * @param secrets how many local secrets of that kind of owner have a value
   * @param owners  their names ({@code dev-db}, {@code github-mcp}, {@code vault}), each once
   */
  record ProtectedBy(String kind, int secrets, List<String> owners) {}

  /** The references, which don't need the key ("The 3 secret references to Vault, GCP and AWS keep working"). */
  record References(int count, List<String> sources) {}

  /**
   * {@code GET /api/master-key}: {@link MasterKeyStatus} and, for the locked screen (where this is the only status
   * that answers), what a new key would destroy ({@code protectsBy}) and what keeps working ({@code references}).
   */
  record Status(String path, String mode, String owner, List<MasterKeyStatus.Key> keys, String osProtection,
      int protects, Instant lastBackupAt, List<String> problems, List<String> warnings, String locked,
      MasterKeyStatus.Rotation rotation, Instant createdAt, Instant rotatedAt, List<ProtectedBy> protectsBy,
      References references) {}

  /**
   * {@code fileKey}: 32 bytes from {@code safeStorage} in the bootstrap line's encoding ({@link
   * Bootstrap#decodeFileKey}), or {@code null} to switch protection off. The key decrypts {@code master.keyset}: it is
   * bound as {@code char[]} to be zeroed, and {@code toString()} doesn't print it (Spring MVC logs the bound body at
   * DEBUG).
   */
  record Protect(char[] fileKey) {
    @Override
    public String toString() {
      return "Protect[fileKey=" + (fileKey == null ? "null" : "‹redacted›") + "]";
    }
  }

  private final MasterKeyService masterKey;
  private final SecretService secrets;
  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final FileGrants files;

  MasterKeyController(MasterKeyService masterKey, SecretService secrets, ConnectionConfigs connections,
      ConnectorRegistry registry, FileGrants files) {
    this.masterKey = masterKey;
    this.secrets = secrets;
    this.connections = connections;
    this.registry = registry;
    this.files = files;
  }

  @GetMapping("/api/master-key")
  Status status() {
    return status(masterKey.status());
  }

  private Status status(MasterKeyStatus k) {
    SecretService.Protection protection = secrets.protection();
    Map<String, List<String>> owners = new LinkedHashMap<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String kind : List.of("connection", "mcp-server", "store", "notification", "tunnel", "audit-export")) {
      owners.put(kind, new ArrayList<>());
      counts.put(kind, 0);
    }
    for (SecretService.Protected p : protection.local()) {
      String kind = kind(p.owner());
      String name = p.name().contains("/") ? p.name().substring(0, p.name().indexOf('/')) : p.name();
      counts.merge(kind, 1, Integer::sum);
      if (!owners.get(kind).contains(name)) {
        owners.get(kind).add(name);
      }
    }
    List<ProtectedBy> by = new ArrayList<>();
    owners.forEach((kind, names) -> {
      if (counts.get(kind) > 0) {
        by.add(new ProtectedBy(kind, counts.get(kind), List.copyOf(names)));
      }
    });
    List<String> sources = protection.referenceSources().stream().distinct()
        .sorted(Comparator.comparingInt(s -> List.of("vault", "gcp-sm", "aws-sm").indexOf(s) < 0 ? 9
            : List.of("vault", "gcp-sm", "aws-sm").indexOf(s))).toList();
    return new Status(k.path(), k.mode(), k.owner(), k.keys(), k.osProtection(), k.protects(), k.lastBackupAt(),
        k.problems(), k.warnings(), k.locked(), k.rotation(), k.createdAt(), k.rotatedAt(), List.copyOf(by),
        new References(protection.referenceSources().size(), sources));
  }

  /** The owner's kind as the locked screen names it; a connection of the external MCP server type is a server. */
  private String kind(SecretOwner owner) {
    return switch (owner.kind()) {
      case CONNECTION -> connections.peek(owner.id()).flatMap(c -> registry.manifest(c.type()))
          .map(m -> m.kind() == ConnectorManifest.Kind.MCP_SERVER ? "mcp-server" : "connection")
          .orElse("connection");
      case STORE -> "store";
      case MCP_SERVER -> "mcp-server";
      case NOTIFICATION -> "notification";
      case TUNNEL -> "tunnel";
      case AUDIT_EXPORT -> "audit-export";
    };
  }

  @PostMapping("/api/master-key/rotate")
  ResponseEntity<MasterKeyStatus.Rotation> rotate(@RequestBody(required = false) Reason request) {
    MasterKeyStatus.Rotation rotation = masterKey.rotate(request == null ? null : request.reason());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(rotation);
  }

  @GetMapping("/api/master-key/rotation")
  MasterKeyStatus.Rotation rotation() {
    return masterKey.rotation();
  }

  @PostMapping("/api/master-key/backup")
  BackedUp backup(@RequestBody Backup request) {
    Path target = take(request.saveToken(), FileGrants.Purpose.MASTER_KEY_BACKUP, "/saveToken");
    try {
      MasterKeyService.BackupResult result = masterKey.backup(target, request.passphrase());
      return new BackedUp(result.path().toString(), result.at(), result.keyIds(), result.passphrase(),
          result.warnings());
    } finally {
      zero(request.passphrase());
    }
  }

  @PostMapping("/api/master-key/fix-permissions")
  Status fixPermissions() {
    masterKey.fixPermissions();
    return status(masterKey.status());
  }

  @PostMapping("/api/master-key/restore")
  Restored restore(@RequestBody Restore request) {
    Path source = take(request.openToken(), FileGrants.Purpose.MASTER_KEY_RESTORE, "/openToken");
    try {
      return new Restored(masterKey.restore(source, request.passphrase(), request.reason()).secretsReadable());
    } finally {
      zero(request.passphrase());
    }
  }

  @PostMapping("/api/master-key/create")
  Created create(@RequestBody Create request) {
    if (!"danger".equals(request.confirm())) {
      throw new MasterKeyException(MasterKeyException.Problem.NOT_CONFIRMED,
          "Creating a new key destroys the secrets encrypted with the lost one: confirm with \"danger\"");
    }
    return new Created(masterKey.create(request.reason()).needValue());
  }

  @PostMapping("/internal/keyset/protect")
  ResponseEntity<Void> protect(@RequestBody Protect request) {
    byte[] key = null;
    if (request.fileKey() != null) {
      try {
        key = decodeFileKey(request.fileKey());
      } finally {
        zero(request.fileKey());
      }
    }
    masterKey.protect(key);
    return ResponseEntity.noContent().build();
  }

  /** The same decoding as the bootstrap line, without a {@code String} copy of the key. */
  private static byte[] decodeFileKey(char[] encoded) {
    try {
      return Bootstrap.decodeFileKey(encoded);
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Bad file key", e.getMessage(), "/fileKey");
    }
  }

  private Path take(String token, FileGrants.Purpose purpose, String field) {
    try {
      return files.take(token, purpose);
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Choose the file again", e.getMessage(), field);
    }
  }

  private static void zero(char[] chars) {
    if (chars != null) {
      Arrays.fill(chars, '\0');
    }
  }
}
