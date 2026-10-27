package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.core.connections.internal.ConnectionRows;
import ai.msrv.gaterook.core.connections.internal.SecretFields;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionEntity;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionRepository;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads connections as the rest of the core uses them: the {@link ConnectionConfig} for connectors, whether the row
 * verified, its health. A row whose MAC doesn't verify is reported ("Changed outside GateRook") and serves nothing
 * until the person reviews it ({@link ConnectionService#confirmIntegrity}).
 */
@Component
public class ConnectionConfigs {

  /**
   * The last health check.
   *
   * @param since when the checks started saying {@code state} ("unreachable since 11:42"), {@code null} before the
   *              first check
   */
  public record Health(HealthResult.State state, Long latencyMs, String message, Instant checkedAt, Instant since) {}

  /**
   * One stored connection.
   *
   * @param secretIds pointer of each stored secret field → its secret id
   */
  public record Stored(ConnectionConfig config, boolean verified, Health health, Instant createdAt,
      Instant updatedAt, Map<String, String> secretIds) {

    public String id() {
      return config.id();
    }

    public String name() {
      return config.name();
    }
  }

  private final ConnectionRepository repository;
  private final ConnectionRows rows;
  private final ConnectorRegistry registry;
  private final IntegrityAlerts alerts;
  private final TransactionTemplate readTx;

  public ConnectionConfigs(ConnectionRepository repository, ConnectionRows rows, ConnectorRegistry registry,
      IntegrityAlerts alerts, PlatformTransactionManager transactions) {
    this.repository = repository;
    this.rows = rows;
    this.registry = registry;
    this.alerts = alerts;
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  public Optional<Stored> byId(String id) {
    return readTx.execute(s -> repository.findById(id).map(this::stored));
  }

  public Optional<Stored> byName(String name) {
    return readTx.execute(s -> repository.findByName(name).map(this::stored));
  }

  /**
   * The config without checking the row's signature or reporting it: for what a screen shows about a connection
   * (whether Rotate is offered), which may be asked while the row is being written and not yet signed — never for
   * using the connection.
   */
  public Optional<ConnectionConfig> peek(String id) {
    return readTx.execute(s -> repository.findById(id).map(row -> rows.toConfig(row,
        registry.manifest(row.getType()).orElse(null))));
  }

  public List<Stored> all() {
    return readTx.execute(s -> repository.findAllByOrderByNameAsc().stream().map(this::stored).toList());
  }

  /**
   * The config of a connection that must exist and verify.
   *
   * @throws ProblemException 404 when it doesn't exist, 409 when it changed outside the core
   */
  public ConnectionConfig require(String id) {
    Stored stored = byId(id).orElseThrow(() -> ProblemException.notFound("Connection not found",
        "There is no connection " + id + "."));
    if (!stored.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", stored.name() + " was changed outside GateRook. Review it before you use it.");
    }
    return stored.config();
  }

  /** Within the caller's transaction: maps a row it holds. */
  Stored stored(ConnectionEntity row) {
    boolean verified = rows.verify(row);
    if (!verified) {
      alerts.report("connection", row.getId(), row.getName(), "connection row");
    }
    ConnectionConfig cfg = rows.toConfig(row, registry.manifest(row.getType()).orElse(null));
    return new Stored(cfg, verified, new Health(row.getHealthState(), row.getHealthLatencyMs(),
        row.getHealthMessage(), row.getHealthCheckedAt(), row.getHealthSince()), row.getCreatedAt(), row.getUpdatedAt(),
        Map.copyOf(SecretFields.placeholders(rows.storedConfig(row))));
  }
}
