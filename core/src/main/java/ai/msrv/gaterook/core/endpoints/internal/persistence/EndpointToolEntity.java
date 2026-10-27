package ai.msrv.gaterook.core.endpoints.internal.persistence;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * The mode of one tool × connection pair on an endpoint (Off · Read only · Approval; there is no "allow writes"
 * mode). {@code connectionId} is {@code null} for the endpoint-wide core tools. {@code toolKey}: {@code sql_query},
 * {@code tpl:<id>:<opKey>}, {@code mcp:<connection>:<original>} or {@code plugin:<type>:<tool>}.
 */
@Entity
@Table(name = "endpoint_tool")
public class EndpointToolEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
  private EndpointEntity endpoint;

  @Column(name = "connection_id", length = 36, updatable = false)
  private String connectionId;

  @Column(name = "tool_key", length = 256, nullable = false, updatable = false)
  private String toolKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", length = 10, nullable = false)
  private ToolDefinition.Mode mode;

  /** Stricter limits for this pair only. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "overrides")
  private Map<String, Object> overrides;

  /** {@code {version, definitionHash, at}}: the person's Read only confirmation of an external or plugin tool. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ro_confirmation")
  private Map<String, Object> roConfirmation;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * HMAC of the row ({@code platform.RowMac}); excluded from optimistic locking, so that writing it after the
   * flush that settled {@code version} doesn't bump the version it signs.
   */
  @OptimisticLock(excluded = true)
  @Column(name = "mac")
  private byte[] mac;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected EndpointToolEntity() {}

  public EndpointToolEntity(EndpointEntity endpoint, String connectionId, String toolKey, ToolDefinition.Mode mode,
      Instant now) {
    this.id = Ids.uuid();
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.connectionId = connectionId;
    this.toolKey = Objects.requireNonNull(toolKey, "toolKey");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public EndpointEntity getEndpoint() {
    return endpoint;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public String getToolKey() {
    return toolKey;
  }

  public ToolDefinition.Mode getMode() {
    return mode;
  }

  public void setMode(ToolDefinition.Mode mode, Instant now) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Map<String, Object> getOverrides() {
    return overrides;
  }

  public void setOverrides(Map<String, Object> overrides, Instant now) {
    this.overrides = overrides;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Map<String, Object> getRoConfirmation() {
    return roConfirmation;
  }

  public void setRoConfirmation(Map<String, Object> roConfirmation, Instant now) {
    this.roConfirmation = roConfirmation;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }

  public long getVersion() {
    return version;
  }
}
