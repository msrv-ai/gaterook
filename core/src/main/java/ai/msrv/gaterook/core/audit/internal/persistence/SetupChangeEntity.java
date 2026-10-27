package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.EnvConverter;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of Activity › Setup changes ("Time | What | Change | By | Reason"), written in the transaction of the change
 * itself. Append-only like {@link ToolCallEntity}, and part of the same kind of hash chain.
 */
@Entity
@Immutable
@Table(name = "setup_change")
public class SetupChangeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", columnDefinition = "integer", nullable = false, updatable = false)
  private Long id;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Convert(converter = SetupChangeWhatConverter.class)
  @Column(name = "what", length = 24, nullable = false)
  private SetupChangeRecorder.What what;

  @Column(name = "subject", length = 256, nullable = false)
  private String subject;

  @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
  @Column(name = "change_from")
  private String changeFrom;

  @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
  @Column(name = "change_to")
  private String changeTo;

  @Column(name = "reason", length = 1000)
  private String reason;

  /** Always {@code you} on the desktop (decision 1). */
  @Column(name = "by_actor", length = 16, nullable = false)
  private String byActor;

  @Convert(converter = EnvConverter.class)
  @Column(name = "env", length = 8)
  private Env env;

  @Column(name = "endpoint_slug", length = 63)
  private String endpointSlug;

  @Column(name = "connection_name", length = 64)
  private String connectionName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "details")
  private Map<String, Object> details;

  @Column(name = "prev_sha256", length = 64, nullable = false)
  private String prevSha256;

  @Column(name = "row_sha256", length = 64, nullable = false)
  private String rowSha256;

  protected SetupChangeEntity() {}

  /**
   * @param details the change's {@code details} as a map (the recorder converts the JSON tree)
   */
  public SetupChangeEntity(SetupChangeRecorder.SetupChange change, Map<String, Object> details, String byActor,
      Instant createdAt, String prevSha256, String rowSha256) {
    Objects.requireNonNull(change, "change");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.what = change.what();
    this.subject = change.subject();
    this.changeFrom = change.from();
    this.changeTo = change.to();
    this.reason = change.reason();
    this.byActor = Objects.requireNonNull(byActor, "byActor");
    this.env = change.env();
    this.endpointSlug = change.endpointSlug();
    this.connectionName = change.connectionName();
    this.details = details;
    this.prevSha256 = Objects.requireNonNull(prevSha256, "prevSha256");
    this.rowSha256 = Objects.requireNonNull(rowSha256, "rowSha256");
  }

  public Long getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public SetupChangeRecorder.What getWhat() {
    return what;
  }

  public String getSubject() {
    return subject;
  }

  public String getChangeFrom() {
    return changeFrom;
  }

  public String getChangeTo() {
    return changeTo;
  }

  public String getReason() {
    return reason;
  }

  public String getByActor() {
    return byActor;
  }

  public Env getEnv() {
    return env;
  }

  public String getEndpointSlug() {
    return endpointSlug;
  }

  public String getConnectionName() {
    return connectionName;
  }

  public Map<String, Object> getDetails() {
    return details;
  }

  public String getPrevSha256() {
    return prevSha256;
  }

  public String getRowSha256() {
    return rowSha256;
  }
}
