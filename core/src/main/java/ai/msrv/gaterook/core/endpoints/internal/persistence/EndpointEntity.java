package ai.msrv.gaterook.core.endpoints.internal.persistence;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.EnvConverter;
import ai.msrv.gaterook.core.platform.Ids;
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
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * An MCP endpoint ({@code /mcp/dev}). {@code env} is derived, the strictest env of its connections (prod never mixes),
 * and is stored so that requests don't recompute it. A disabled endpoint answers 404 after authentication and keeps
 * its pending approval requests.
 */
@Entity
@Table(name = "endpoint")
public class EndpointEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "slug", length = 63, nullable = false, unique = true)
  private String slug;

  /** Shown in lists and ⌘K; agents never see it (they get {@code instructions}). */
  @Column(name = "description", length = 500)
  private String description;

  @Convert(converter = EnvConverter.class)
  @Column(name = "env", length = 8, nullable = false)
  private Env env;

  @Convert(converter = EndpointAccess.Converter.class)
  @Column(name = "access", length = 10, nullable = false)
  private EndpointAccess access;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "disabled_at")
  private Instant disabledAt;

  @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
  @Column(name = "instructions")
  private String instructions;

  /** Protocol revisions, limits and approval wait (report 01 §4). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "settings", nullable = false)
  private Map<String, Object> settings;

  @Column(name = "published", nullable = false)
  private boolean published;

  @Column(name = "oauth_enabled", nullable = false)
  private boolean oauthEnabled;

  /** {@code {at, reason, ingredients}} once the person dismissed the risk warning. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "risk_dismissed")
  private Map<String, Object> riskDismissed;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

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

  protected EndpointEntity() {}

  public EndpointEntity(String slug, Env env, EndpointAccess access, Map<String, Object> settings, Instant now) {
    this.id = Ids.uuid();
    this.slug = Objects.requireNonNull(slug, "slug");
    this.env = Objects.requireNonNull(env, "env");
    this.access = Objects.requireNonNull(access, "access");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.enabled = true;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public String getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Env getEnv() {
    return env;
  }

  public void setEnv(Env env) {
    this.env = Objects.requireNonNull(env, "env");
  }

  public EndpointAccess getAccess() {
    return access;
  }

  public void setAccess(EndpointAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getDisabledAt() {
    return disabledAt;
  }

  public void disable(Instant now) {
    this.enabled = false;
    this.disabledAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public void enable(Instant now) {
    this.enabled = true;
    this.disabledAt = null;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getInstructions() {
    return instructions;
  }

  public void setInstructions(String instructions) {
    this.instructions = instructions;
  }

  public Map<String, Object> getSettings() {
    return settings;
  }

  public void setSettings(Map<String, Object> settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  public boolean isPublished() {
    return published;
  }

  public void setPublished(boolean published) {
    this.published = published;
  }

  public boolean isOauthEnabled() {
    return oauthEnabled;
  }

  public void setOauthEnabled(boolean oauthEnabled) {
    this.oauthEnabled = oauthEnabled;
  }

  public Map<String, Object> getRiskDismissed() {
    return riskDismissed;
  }

  public void setRiskDismissed(Map<String, Object> riskDismissed) {
    this.riskDismissed = riskDismissed;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch(Instant now) {
    this.updatedAt = Objects.requireNonNull(now, "now");
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
