package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportRepository;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The destinations' secrets in the {@code secrets} module (owner kind {@code audit-export}, id = the destination):
 * write-only like every secret of the gateway — stored encrypted here or referenced in a connected store, opened for
 * one export and closed after it, every value opened added to the run's taint so an echo of it in an error is masked.
 * Also tells the Secrets screen what uses them.
 */
@Component
public class ExportSecrets implements SecretOwners {

  /** A secret as a form sends it: a value to store here, or a reference ({@code vault://…}) to a connected store. */
  public record Input(String source, char[] value, String ref, String store) {

    public void clear() {
      if (value != null) {
        Arrays.fill(value, '\0');
      }
    }

    @Override
    public String toString() {
      return "Input[source=" + source + (value == null ? "" : ", value=‹redacted›") + "]";
    }
  }

  private final ObjectProvider<SecretService> secrets;
  private final ObjectProvider<AuditExportRepository> rows;
  private final TransactionTemplate readTx;

  ExportSecrets(ObjectProvider<SecretService> secrets, ObjectProvider<AuditExportRepository> rows,
      PlatformTransactionManager tx) {
    this.secrets = secrets;
    this.rows = rows;
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  public static SecretOwner owner(String destinationId) {
    return new SecretOwner(SecretOwner.Kind.AUDIT_EXPORT, destinationId);
  }

  /**
   * Stores {@code input} as {@code field} of the destination: a new version of the stored value, or a new secret (a
   * reference replaces what was there). Call in the transaction that saves the destination.
   *
   * @param pointer where the field is in the request, for a 422
   * @return the secret's id, for {@code audit_export.secret_id}
   */
  public String store(String destinationId, String destinationName, String field, Input input, String reason,
      String pointer) {
    SecretService service = secrets.getObject();
    SecretOwner owner = owner(destinationId);
    Optional<SecretInfo> existing = service.listForOwner(owner).stream().filter(s -> s.field().equals(field))
        .findFirst();
    String source = input.source() == null ? (input.value() != null ? "local" : "reference") : input.source();
    String name = destinationName + switch (field) {
      case ExportSettings.SIEM_CLIENT_KEY -> "/client-key";
      case ExportSettings.S3_SECRET_KEY -> "/secret-access-key";
      default -> "/service-account-key";
    };
    if (source.equals("local")) {
      char[] value = input.value() == null ? new char[0] : input.value().clone();
      if (existing.isPresent() && "local".equals(existing.get().source())) {
        service.replace(existing.get().id(), value, false, reason);
        return existing.get().id();
      }
      existing.ifPresent(e -> service.deleteForOwnerFields(owner, List.of(field)));
      return service.createLocal(owner, field, name, value).id();
    }
    existing.ifPresent(e -> service.deleteForOwnerFields(owner, List.of(field)));
    return service.createReference(owner, field, name, input.ref() == null ? "" : input.ref(), false, input.store(),
        pointer).id();
  }

  /** What is stored for the destination: field, source, state and version, never a value. */
  public List<SecretInfo> stored(String destinationId) {
    return secrets.getObject().listForOwner(owner(destinationId));
  }

  /** Destroys the destination's secrets of {@code fields}. Call in the save's transaction. */
  public void destroy(String destinationId, List<String> fields) {
    secrets.getObject().deleteForOwnerFields(owner(destinationId), fields);
  }

  /** Destroys every secret of the destination (it was deleted). Call in the delete's transaction. */
  public List<String> destroyAll(String destinationId) {
    return secrets.getObject().deleteForOwner(owner(destinationId));
  }

  /** A resolver for one export of the destination: only {@code field}, every value handed out tainted. */
  public ScopedSecretResolver open(String destinationId, String field, Taint taint) {
    return secrets.getObject().resolver(owner(destinationId), field == null ? List.of() : List.of(field), taint);
  }

  @Override
  public SecretOwner.Kind kind() {
    return SecretOwner.Kind.AUDIT_EXPORT;
  }

  @Override
  public Optional<Owner> describe(String ownerId) {
    return readTx.execute(s -> rows.getObject().findById(ownerId).map(e -> new Owner("Audit export · "
        + e.getName(), null, List.of("Settings › Audit and retention"))));
  }

  @Override
  public Optional<String> rotateUnavailable(String ownerId, String field) {
    return Optional.of("AWS, Google and your SIEM's CA issue these keys: create a new one there and replace it here.");
  }
}
