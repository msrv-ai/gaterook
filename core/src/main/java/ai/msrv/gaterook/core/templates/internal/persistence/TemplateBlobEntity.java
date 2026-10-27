package ai.msrv.gaterook.core.templates.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/** A source document of a template (an OpenAPI spec), content-addressed by SHA-256 and stored gzip-compressed. */
@Entity
@Immutable
@Table(name = "template_blob")
public class TemplateBlobEntity {

  @Id
  @Column(name = "sha256", length = 64, nullable = false, updatable = false)
  private String sha256;

  @Column(name = "media_type", length = 64, nullable = false, updatable = false)
  private String mediaType;

  /** Size of the uncompressed document. */
  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Column(name = "gz", nullable = false, updatable = false)
  private byte[] gz;

  protected TemplateBlobEntity() {}

  public TemplateBlobEntity(String sha256, String mediaType, long sizeBytes, byte[] gz) {
    this.sha256 = Objects.requireNonNull(sha256, "sha256");
    this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    this.sizeBytes = sizeBytes;
    this.gz = Objects.requireNonNull(gz, "gz");
  }

  public String getSha256() {
    return sha256;
  }

  public String getMediaType() {
    return mediaType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public byte[] getGz() {
    return gz;
  }
}
