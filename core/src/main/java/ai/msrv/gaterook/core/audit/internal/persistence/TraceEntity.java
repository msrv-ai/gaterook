package ai.msrv.gaterook.core.audit.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One agent task as Activity › Traces shows it, rebuilt by {@code TraceSummarizer} after every completed call. The
 * title comes from the agent and is masked and always labelled "unverified".
 */
@Entity
@Table(name = "trace")
public class TraceEntity {

  @Id
  @Column(name = "trace_id", length = 32, nullable = false, updatable = false)
  private String traceId;

  @Column(name = "title", length = 120)
  private String title;

  @Convert(converter = TraceTitleSource.Converter.class)
  @Column(name = "title_source", length = 8)
  private TraceTitleSource titleSource;

  @Column(name = "first_at", nullable = false, updatable = false)
  private Instant firstAt;

  @Column(name = "last_at", nullable = false)
  private Instant lastAt;

  @Column(name = "client", length = 32)
  private String client;

  @Column(name = "endpoint_slug", length = 63)
  private String endpointSlug;

  @Column(name = "calls", nullable = false)
  private int calls;

  /** Phases, decisions, waitedForPeopleMs. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "summary")
  private Map<String, Object> summary;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected TraceEntity() {}

  public TraceEntity(String traceId, Instant firstAt) {
    this.traceId = Objects.requireNonNull(traceId, "traceId");
    this.firstAt = Objects.requireNonNull(firstAt, "firstAt");
    this.lastAt = firstAt;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getTitle() {
    return title;
  }

  public TraceTitleSource getTitleSource() {
    return titleSource;
  }

  public void setTitle(String title, TraceTitleSource source) {
    this.title = title;
    this.titleSource = source;
  }

  public Instant getFirstAt() {
    return firstAt;
  }

  public Instant getLastAt() {
    return lastAt;
  }

  public String getClient() {
    return client;
  }

  public String getEndpointSlug() {
    return endpointSlug;
  }

  public int getCalls() {
    return calls;
  }

  public Map<String, Object> getSummary() {
    return summary;
  }

  /** The summarizer's latest view of the trace. */
  public void summarize(Instant lastAt, int calls, String client, String endpointSlug, Map<String, Object> summary) {
    this.lastAt = Objects.requireNonNull(lastAt, "lastAt");
    this.calls = calls;
    this.client = client;
    this.endpointSlug = endpointSlug;
    this.summary = summary;
  }

  public long getVersion() {
    return version;
  }
}
