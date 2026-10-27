package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.connections.ConnectionUsage;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionRepository;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What Secrets shows for a connection's secret: its name, environment (a comment is required to replace a secret of a
 * prod connection) and endpoints; and the test with a new version before Replace makes it current.
 */
@Component
class ConnectionSecretOwners implements SecretOwners {

  private final ConnectionRepository repository;
  private final ObjectProvider<ConnectionUsage> usage;
  private final ObjectProvider<ConnectionTester> tester;
  private final TransactionTemplate readTx;

  ConnectionSecretOwners(ConnectionRepository repository, ObjectProvider<ConnectionUsage> usage,
      ObjectProvider<ConnectionTester> tester, PlatformTransactionManager transactions) {
    this.repository = repository;
    this.usage = usage;
    this.tester = tester;
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  @Override
  public SecretOwner.Kind kind() {
    return SecretOwner.Kind.CONNECTION;
  }

  @Override
  public Optional<Owner> describe(String ownerId) {
    return readTx.execute(s -> repository.findById(ownerId).map(row -> {
      ConnectionUsage u = usage.getIfAvailable();
      List<String> endpoints = u == null ? List.of() : u.endpoints(ownerId);
      return new Owner(row.getName(), row.getEnv(), endpoints);
    }));
  }

  @Override
  public Optional<String> rotateUnavailable(String ownerId, String field) {
    return tester.getObject().rotateUnavailable(ownerId, field);
  }

  @Override
  public void rotate(String ownerId, String field, char[] next) {
    tester.getObject().rotateAtSource(ownerId, field, next);
  }

  @Override
  public Optional<TestOutcome> testWithVersion(String ownerId, String field, int version) {
    ConnectionTester.Outcome outcome = tester.getObject().testWithVersion(ownerId, field, version);
    var r = outcome.result();
    String message = r.ok() ? "Connected with the new value" : r.error() != null ? r.error().message()
        : r.needsConfirmation() != null ? r.needsConfirmation().text() : "The test failed";
    return Optional.of(new TestOutcome(r.ok(), message));
  }
}
