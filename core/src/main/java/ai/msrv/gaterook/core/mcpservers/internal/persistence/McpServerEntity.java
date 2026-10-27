package ai.msrv.gaterook.core.mcpservers.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * The reviewed state of an external MCP server behind a connection of type {@code mcp-server}: the tool snapshot
 * agents see ({@code tools/list} never reaches the server), the prefix of its tools ({@code github_}) and, for catalog
 * servers, the npm integrity checked before every start (report 11).
 */
@Entity
@Table(name = "mcp_server")
public class McpServerEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  /** The connection ({@code connections} module) this server belongs to. */
  @Column(name = "connection_id", length = 36, nullable = false, unique = true, updatable = false)
  private String connectionId;

  @Column(name = "tool_prefix", length = 16, nullable = false, unique = true)
  private String toolPrefix;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "server_info")
  private Map<String, Object> serverInfo;

  @Column(name = "protocol_version", length = 16)
  private String protocolVersion;

  /** {@code [{exposed, original, definitionHash, declared annotations}]}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "tools_snapshot", nullable = false)
  private List<Map<String, Object>> toolsSnapshot;

  @Column(name = "snapshot_sha256", length = 64)
  private String snapshotSha256;

  @Column(name = "npm_integrity", length = 160)
  private String npmIntegrity;

  @Column(name = "catalog_entry", length = 128)
  private String catalogEntry;

  @Column(name = "reviewed_at", nullable = false)
  private Instant reviewedAt;

  @Column(name = "last_started_at")
  private Instant lastStartedAt;

  @Column(name = "state", length = 12, nullable = false)
  private String state;

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

  protected McpServerEntity() {}

  public McpServerEntity(String connectionId, String toolPrefix, List<Map<String, Object>> toolsSnapshot,
      String state, Instant reviewedAt) {
    this.id = Ids.uuid();
    this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
    this.toolPrefix = Objects.requireNonNull(toolPrefix, "toolPrefix");
    this.toolsSnapshot = Objects.requireNonNull(toolsSnapshot, "toolsSnapshot");
    this.state = Objects.requireNonNull(state, "state");
    this.reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
  }

  public String getId() {
    return id;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public String getToolPrefix() {
    return toolPrefix;
  }

  public Map<String, Object> getServerInfo() {
    return serverInfo;
  }

  public String getProtocolVersion() {
    return protocolVersion;
  }

  /** What the server said about itself at the last handshake. */
  public void handshake(Map<String, Object> serverInfo, String protocolVersion, Instant startedAt) {
    this.serverInfo = serverInfo;
    this.protocolVersion = protocolVersion;
    this.lastStartedAt = startedAt;
  }

  public List<Map<String, Object>> getToolsSnapshot() {
    return toolsSnapshot;
  }

  public String getSnapshotSha256() {
    return snapshotSha256;
  }

  /** A reviewed snapshot replaces the previous one. */
  public void snapshot(List<Map<String, Object>> toolsSnapshot, String sha256, Instant reviewedAt) {
    this.toolsSnapshot = Objects.requireNonNull(toolsSnapshot, "toolsSnapshot");
    this.snapshotSha256 = sha256;
    this.reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
  }

  public String getNpmIntegrity() {
    return npmIntegrity;
  }

  public void setNpmIntegrity(String npmIntegrity) {
    this.npmIntegrity = npmIntegrity;
  }

  public String getCatalogEntry() {
    return catalogEntry;
  }

  public void setCatalogEntry(String catalogEntry) {
    this.catalogEntry = catalogEntry;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }

  public Instant getLastStartedAt() {
    return lastStartedAt;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = Objects.requireNonNull(state, "state");
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
