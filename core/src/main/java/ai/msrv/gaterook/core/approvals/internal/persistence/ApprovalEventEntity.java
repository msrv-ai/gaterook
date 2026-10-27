package ai.msrv.gaterook.core.approvals.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One entry of a request's timeline ("Notified on the desktop and in Slack"). Inserts only, so notifications never
 * write the approval row itself.
 */
@Entity
@Immutable
@Table(name = "approval_event")
public class ApprovalEventEntity {

  /** SQLite rowid: {@code columnDefinition} keeps {@code validate} from expecting {@code bigint} (report 15 rule 17). */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", columnDefinition = "integer", nullable = false, updatable = false)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "approval_id", nullable = false, updatable = false)
  private ApprovalEntity approval;

  @Column(name = "at", nullable = false, updatable = false)
  private Instant at;

  @Convert(converter = ApprovalEventKind.Converter.class)
  @Column(name = "kind", length = 16, nullable = false, updatable = false)
  private ApprovalEventKind kind;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "detail", updatable = false)
  private Map<String, Object> detail;

  protected ApprovalEventEntity() {}

  public ApprovalEventEntity(ApprovalEntity approval, ApprovalEventKind kind, Map<String, Object> detail,
      Instant at) {
    this.approval = Objects.requireNonNull(approval, "approval");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.detail = detail;
    this.at = Objects.requireNonNull(at, "at");
  }

  public Long getId() {
    return id;
  }

  public ApprovalEntity getApproval() {
    return approval;
  }

  public Instant getAt() {
    return at;
  }

  public ApprovalEventKind getKind() {
    return kind;
  }

  public Map<String, Object> getDetail() {
    return detail;
  }
}
