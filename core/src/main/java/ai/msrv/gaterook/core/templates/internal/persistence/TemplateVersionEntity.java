package ai.msrv.gaterook.core.templates.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
 * One version of a template, append-only on three levels (report 15 §9 rule 21): {@code @Immutable}, a repository
 * without update or delete, and the {@code template_version_no_update} trigger.
 */
@Entity
@Immutable
@Table(name = "template_version")
public class TemplateVersionEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "template_id", nullable = false, updatable = false)
  private TemplateEntity template;

  @Column(name = "template_version", nullable = false, updatable = false)
  private int templateVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "definition", nullable = false, updatable = false)
  private Map<String, Object> definition;

  /** The source document, a {@link TemplateBlobEntity} key. */
  @Column(name = "source_sha256", length = 64, updatable = false)
  private String sourceSha256;

  /** {@code CompileResult.digest()}: what the approval digest pins. */
  @Column(name = "digest", length = 64, nullable = false, updatable = false)
  private String digest;

  /** {@code {"read": 9, "mutation": 5}}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "natures", nullable = false, updatable = false)
  private Map<String, Object> natures;

  @Column(name = "note", length = 500, updatable = false)
  private String note;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "mac", updatable = false)
  private byte[] mac;

  protected TemplateVersionEntity() {}

  public TemplateVersionEntity(TemplateEntity template, int templateVersion, Map<String, Object> definition,
      String sourceSha256, String digest, Map<String, Object> natures, String note, byte[] mac, Instant now) {
    this(template, templateVersion, definition, sourceSha256, digest, natures, note, id -> mac, now);
  }

  /**
   * A version whose MAC covers its own id: the row is immutable, so {@code signer} gets the id assigned here and
   * returns the MAC stored with the insert.
   */
  public TemplateVersionEntity(TemplateEntity template, int templateVersion, Map<String, Object> definition,
      String sourceSha256, String digest, Map<String, Object> natures, String note,
      java.util.function.Function<String, byte[]> signer, Instant now) {
    this.id = Ids.uuid();
    this.template = Objects.requireNonNull(template, "template");
    this.templateVersion = templateVersion;
    this.definition = Objects.requireNonNull(definition, "definition");
    this.sourceSha256 = sourceSha256;
    this.digest = Objects.requireNonNull(digest, "digest");
    this.natures = Objects.requireNonNull(natures, "natures");
    this.note = note;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.mac = signer.apply(this.id);
  }

  public String getId() {
    return id;
  }

  public TemplateEntity getTemplate() {
    return template;
  }

  public int getTemplateVersion() {
    return templateVersion;
  }

  public Map<String, Object> getDefinition() {
    return definition;
  }

  public String getSourceSha256() {
    return sourceSha256;
  }

  public String getDigest() {
    return digest;
  }

  public Map<String, Object> getNatures() {
    return natures;
  }

  public String getNote() {
    return note;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public byte[] getMac() {
    return mac;
  }
}
