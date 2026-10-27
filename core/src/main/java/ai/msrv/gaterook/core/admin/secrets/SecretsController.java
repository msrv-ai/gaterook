package ai.msrv.gaterook.core.admin.secrets;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.IssuedCredential;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.SecretStores;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local secrets for the Secrets screen (ARCHITECTURE.md §6, report 12 §3): the list, Replace, Rotate and Secrets ›
 * Short-lived. Write-only by construction: no response type here has a field that could hold a value (the API test
 * scans every response for {@code value}, {@code password}, {@code token}).
 */
@RestController
@RegisterReflectionForBinding({SecretsController.SecretRow.class, SecretsController.Replaced.class,
    SecretsController.ShortLived.class, SecretsController.Reason.class, SecretsController.Rotated.class})
class SecretsController {

  /** One row; {@code usedBy} — endpoints or servers that use the owner. */
  record SecretRow(String id, String name, String owner, String ownerKind, String field, List<String> usedBy,
      String source, Integer version, String remoteVersion, Instant rotatedAt, String state, boolean canRotate,
      String rotateReason, String ref, String store) {}

  /** {@code PUT …/value}: the new value (write-only), whether to test it first, the comment. */
  record Replace(char[] value, Boolean testBefore, String reason) {}

  record Replaced(String id, int version, String state, Boolean tested, String testMessage) {}

  record Reason(String reason) {}

  /**
   * One short-lived credential the gateway gets on a connection's behalf.
   *
   * @param identity   the impersonated service account, the assumed role's ARN, the Vault role or the SSH signer
   * @param principal  the user an SSH certificate is signed for (kit: "Principal deploy"), {@code null} otherwise
   * @param externalId the ExternalId of an AssumeRole (kit: "ExternalId gw-ext-7c41d2"), {@code null} otherwise
   */
  record ShortLived(String connection, String mechanism, String identity, Instant lastIssuedAt, long lifetime,
      String principal, String externalId) {}

  private final SecretService secrets;
  private final CloudCredentials cloud;
  private final SecretStores stores;

  SecretsController(SecretService secrets, CloudCredentials cloud, SecretStores stores) {
    this.secrets = secrets;
    this.cloud = cloud;
    this.stores = stores;
  }

  @GetMapping("/api/secrets")
  List<SecretRow> list() {
    return secrets.list().stream().map(SecretsController::row).toList();
  }

  @PutMapping("/api/secrets/{id}/value")
  Replaced replace(@PathVariable String id, @RequestBody Replace request) {
    if (request.reason() != null && request.reason().length() > 500) {
      throw ApiRequestException.unprocessable("Comment too long", "A comment has at most 500 characters", "/reason");
    }
    SecretService.ReplaceResult result = secrets.replace(id, request.value() == null ? new char[0] : request.value(),
        Boolean.TRUE.equals(request.testBefore()), request.reason());
    return new Replaced(result.id(), result.version(), result.state(), result.tested(), result.testMessage());
  }

  /**
   * Rotate at the source (the connector's {@code CredentialRotator}): 202 once the source took the new value and it
   * is the current version; 409 with the reason when it can't be rotated or the source refused (version N stays).
   */
  @PostMapping("/api/secrets/{id}/rotate")
  ResponseEntity<Rotated> rotate(@PathVariable String id, @RequestBody(required = false) Reason request) {
    String reason = request == null ? null : request.reason();
    if (reason != null && reason.length() > 500) {
      throw ApiRequestException.unprocessable("Comment too long", "A comment has at most 500 characters", "/reason");
    }
    SecretService.ReplaceResult result = secrets.rotate(id, reason);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Rotated(Ids.next("job_"), result.id(),
        result.version(), result.state()));
  }

  /** {@code jobId} for the window's toast; the rotation is done when this is sent. */
  record Rotated(String jobId, String id, int version, String state) {}

  /** Cloud sign-ins (impersonation, AssumeRole) and what the external stores issue (dynamic users, SSH certs). */
  @GetMapping("/api/secrets/short-lived")
  List<ShortLived> shortLived() {
    List<ShortLived> out = new ArrayList<>(cloud.issued().stream().map(SecretsController::shortLived).toList());
    stores.issued().forEach(i -> out.add(new ShortLived(i.connection(), i.mechanism(), i.identity(), i.lastIssuedAt(),
        i.lifetimeSeconds(), i.principal(), null)));
    return out;
  }

  private static SecretRow row(SecretInfo s) {
    return new SecretRow(s.id(), s.name(), s.ownerLabel(), s.owner().kind().name().toLowerCase(java.util.Locale.ROOT)
        .replace('_', '-'), s.field(), s.usedBy(), s.source(), s.version(), s.remoteVersion(), s.rotatedAt(),
        s.state(), s.canRotate(), s.rotateReason(), s.ref(), s.store());
  }

  private static ShortLived shortLived(IssuedCredential c) {
    return new ShortLived(c.owner(), c.mechanism(), c.identity(), c.lastIssuedAt(), c.lifetime().toSeconds(), null,
        c.externalId());
  }
}
