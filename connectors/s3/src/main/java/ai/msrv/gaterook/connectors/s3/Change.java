package ai.msrv.gaterook.connectors.s3;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One change on one object, as a person approves it and as it runs: {@code s3_put_object} ({@code PutObject} with
 * the text as its body), {@code s3_copy_object} ({@code CopyObject}: {@code PUT} with {@code x-amz-copy-source}) or
 * {@code s3_delete_object} ({@code DeleteObject}). It is built from the call's arguments and the connection's settings
 * alone, so {@code describeAction} shows the exact request without I/O and {@code execute} builds it again and
 * compares it with the approved {@link #payload} before anything is sent.
 *
 * @param content the text of a put, or {@code null}
 */
record Change(String tool, S3Request request, String bucket, String key, String sourceBucket, String sourceKey,
    String sourceVersionId, String versionId, String content) {

  /** A put's body never exceeds this, whatever the endpoint's request size. */
  static final long MAX_PUT_BYTES = 5L * 1024 * 1024;
  /** The approval shows a body up to this size, and its SHA-256 beyond it. */
  static final int SHOWN_BODY = 64 * 1024;
  static final String DEFAULT_TYPE = "text/plain; charset=utf-8";
  private static final Pattern ETAG = Pattern.compile("^\"?[\\x21\\x23-\\x7e]{1,1022}\"?$");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /**
   * The change a call asks for, checked: buckets on the connection's list, keys and metadata {@link Names} accepts, a
   * text body within {@code maxRequestBytes} (the endpoint's request size; {@code 0}: unknown, not checked here).
   */
  static Change of(String tool, JsonNode a, S3Settings s, long maxRequestBytes) throws Refusal {
    return switch (tool) {
      case S3Tools.PUT_OBJECT -> put(a, s, maxRequestBytes);
      case S3Tools.COPY_OBJECT -> copy(a, s);
      case S3Tools.DELETE_OBJECT -> delete(a, s);
      default -> throw new Refusal("unknown-tool", tool + " doesn't change objects.", null);
    };
  }

  private static Change put(JsonNode a, S3Settings s, long maxRequestBytes) throws Refusal {
    String bucket = Args.bucket(a, "bucket", s);
    String key = Args.key(a, "key", true);
    String content = Args.text(a, "content");
    if (content == null) {
      throw Refusal.badArgument("s3_put_object needs content: the object's text");
    }
    if (!wellFormed(content)) {
      throw Refusal.badArgument("The content isn't valid Unicode");
    }
    byte[] body = content.getBytes(StandardCharsets.UTF_8);
    long max = maxRequestBytes > 0 ? Math.min(maxRequestBytes, MAX_PUT_BYTES) : MAX_PUT_BYTES;
    if (body.length > max) {
      throw new Refusal("too-large", "The content has " + String.format(Locale.US, "%,d", body.length)
          + " bytes; a request may have " + String.format(Locale.US, "%,d", max) + " on this endpoint.",
          "Write a smaller object.");
    }
    String type = Args.text(a, "contentType");
    type = type == null || type.isBlank() ? DEFAULT_TYPE : type;
    String problem = Names.contentTypeProblem(type);
    if (problem != null) {
      throw Refusal.badArgument(problem);
    }
    S3Request r = S3Request.of("PutObject", "PUT", bucket, key).header("content-type", type).withBody(body);
    r = withMetadata(r, Args.metadata(a));
    boolean overwrite = Args.flag(a, "overwrite", true);
    String ifMatch = Args.text(a, "ifMatch");
    if (ifMatch != null && !ifMatch.isEmpty()) {
      if (!overwrite) {
        throw Refusal.badArgument("Give overwrite false or ifMatch, not both: one creates, the other replaces");
      }
      if (!ETAG.matcher(ifMatch).matches()) {
        throw Refusal.badArgument("ifMatch must be the object's ETag, as s3_head_object shows it");
      }
      r = r.header("if-match", ifMatch.startsWith("\"") ? ifMatch : "\"" + ifMatch + "\"");
    }
    if (!overwrite) {
      r = r.header("if-none-match", "*");
    }
    return new Change(S3Tools.PUT_OBJECT, r, bucket, key, null, null, null, null, content);
  }

  private static Change copy(JsonNode a, S3Settings s) throws Refusal {
    String sourceBucket = Args.bucket(a, "sourceBucket", s);
    String sourceKey = Args.key(a, "sourceKey", false);
    String sourceVersion = Args.version(a, "sourceVersionId");
    String bucket = Args.bucket(a, "bucket", s);
    String key = Args.key(a, "key", true);
    String type = Args.text(a, "contentType");
    if (type != null && type.isBlank()) {
      type = null;
    }
    if (type != null && !Names.mediaType(type)) {
      throw Refusal.badArgument("contentType must be a media type such as text/csv or image/png");
    }
    Map<String, String> metadata = Args.metadata(a);
    boolean replace = type != null || metadata != null;
    if (sourceBucket.equals(bucket) && sourceKey.equals(key) && sourceVersion == null && !replace) {
      throw Refusal.badArgument("This copies the object onto itself and changes nothing: give contentType or "
          + "metadata to replace them, or another key");
    }
    String source = sourceBucket + "/" + Names.path(sourceKey)
        + (sourceVersion == null ? "" : "?versionId=" + Names.query(sourceVersion));
    S3Request r = S3Request.of("CopyObject", "PUT", bucket, key).header("x-amz-copy-source", source);
    if (replace) {
      r = r.header("x-amz-metadata-directive", "REPLACE");
      if (type != null) {
        r = r.header("content-type", type);
      }
      r = withMetadata(r, metadata);
    }
    return new Change(S3Tools.COPY_OBJECT, r, bucket, key, sourceBucket, sourceKey, sourceVersion, null, null);
  }

  private static Change delete(JsonNode a, S3Settings s) throws Refusal {
    String bucket = Args.bucket(a, "bucket", s);
    String key = Args.key(a, "key", false);
    String version = Args.version(a, "versionId");
    S3Request r = S3Request.of("DeleteObject", "DELETE", bucket, key);
    if (version != null) {
      r = r.param("versionId", version);
    }
    return new Change(S3Tools.DELETE_OBJECT, r, bucket, key, null, null, null, version, null);
  }

  private static S3Request withMetadata(S3Request r, Map<String, String> metadata) {
    S3Request out = r;
    if (metadata != null) {
      for (Map.Entry<String, String> m : metadata.entrySet()) {
        out = out.header("x-amz-meta-" + m.getKey(), m.getValue());
      }
    }
    return out;
  }

  private static boolean wellFormed(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  /** {@code put}, {@code copy} or {@code delete}. */
  String operation() {
    return switch (tool) {
      case S3Tools.PUT_OBJECT -> "put";
      case S3Tools.COPY_OBJECT -> "copy";
      default -> "delete";
    };
  }

  /** What Activity and the approval list say the change does. */
  String reason() {
    return switch (tool) {
      case S3Tools.PUT_OBJECT -> "Writes " + Names.shown(bucket) + "/" + Names.shown(key);
      case S3Tools.COPY_OBJECT -> "Copies " + Names.shown(sourceBucket) + "/" + Names.shown(sourceKey) + " to "
          + Names.shown(bucket) + "/" + Names.shown(key);
      default -> "Deletes " + Names.shown(bucket) + "/" + Names.shown(key)
          + (versionId == null ? "" : " (version " + Names.shown(versionId) + ")");
    };
  }

  ObjectNode facts() {
    ObjectNode f = F.objectNode().put("operation", operation()).put("bucket", bucket).put("key", Names.shown(key));
    if (sourceBucket != null) {
      f.put("sourceBucket", sourceBucket).put("sourceKey", Names.shown(sourceKey));
    }
    return f;
  }

  /**
   * The canonical request a person approves and {@code execute} compares: the method, the URL, the headers GateRook
   * sets (signing headers and {@code x-amz-expected-bucket-owner} aside: they only narrow what S3 accepts), the
   * body's SHA-256 and size.
   */
  ObjectNode payload(URI uri) {
    ObjectNode p = F.objectNode();
    p.put("tool", tool);
    p.put("method", request.method());
    p.put("url", uri.toString());
    ObjectNode h = p.putObject("headers");
    request.headers().forEach(h::put);
    p.put("bodySha256", request.bodySha256());
    p.put("bodyBytes", request.body() == null ? 0 : request.body().length);
    return p;
  }

  /**
   * The approver's view: the HTTP request as it goes out, the signature masked, the body as text when it is at most
   * {@link #SHOWN_BODY} bytes and its SHA-256 otherwise.
   *
   * @param owner      the expected bucket owner when it is known without I/O, or {@code null}
   * @param impact     what the object holds now, if {@code prepare} looked, else {@code null}
   */
  ActionPreview preview(ConnectionConfig cfg, S3Settings s, URI uri, String owner, Impact impact) {
    StringBuilder text = new StringBuilder();
    text.append(request.method()).append(' ').append(uri).append('\n');
    request.headers().forEach((name, value) -> text.append(name).append(": ").append(value).append('\n'));
    if (owner != null) {
      text.append("x-amz-expected-bucket-owner: ").append(owner).append('\n');
      if (sourceBucket != null) {
        text.append("x-amz-source-expected-bucket-owner: ").append(owner).append('\n');
      }
    }
    int bytes = request.body() == null ? 0 : request.body().length;
    if (request.body() != null) {
      text.append("content-length: ").append(bytes).append('\n');
    }
    text.append("x-amz-content-sha256: ").append(request.bodySha256()).append('\n');
    text.append("authorization: AWS4-HMAC-SHA256 ‹masked› (").append(s.identity()).append(")\n");
    if (content != null) {
      text.append('\n');
      text.append(bytes <= SHOWN_BODY ? content : "‹" + String.format(Locale.US, "%,d", bytes) + " bytes of text, "
          + "SHA-256 " + request.bodySha256() + ": longer than an approval shows›");
    }
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Bucket", bucket));
    fields.add(new Field("Key", key));
    if (sourceBucket != null) {
      fields.add(new Field("Source", sourceBucket + "/" + sourceKey + (sourceVersionId == null ? ""
          : " (version " + sourceVersionId + ")")));
    }
    if (versionId != null) {
      fields.add(new Field("Version", versionId));
    }
    if (content != null) {
      fields.add(new Field("Size", String.format(Locale.US, "%,d bytes", bytes)));
      fields.add(new Field("Content type", request.headers().get("content-type")));
    }
    fields.add(new Field("Identity", s.identity()));
    List<Field> impactFields = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String target = Names.shown(bucket) + "/" + Names.shown(key);
    switch (tool) {
      case S3Tools.PUT_OBJECT -> {
        if (request.headers().containsKey("if-none-match")) {
          impactFields.add(new Field("Object", "Created; nothing changes if " + target + " exists"));
        } else if (impact != null && impact.exists()) {
          impactFields.add(new Field("Object", "Replaces " + impact.describe()));
          warnings.add("Replaces the object at " + target + ".");
        } else {
          impactFields.add(new Field("Object", impact != null && impact.known() ? "Created: there is no object at "
              + target + " now" : "Created, or replaced if " + target + " exists"));
        }
      }
      case S3Tools.COPY_OBJECT -> {
        if (impact != null && impact.source() != null) {
          impactFields.add(new Field("Copied", impact.source()));
        }
        if (impact != null && impact.exists()) {
          impactFields.add(new Field("Object", "Replaces " + impact.describe()));
          warnings.add("Replaces the object at " + target + ".");
        } else {
          impactFields.add(new Field("Object", impact != null && impact.known() ? "Created: there is no object at "
              + target + " now" : "Created, or replaced if " + target + " exists"));
        }
      }
      default -> {
        if (versionId != null) {
          warnings.add("Deletes this version of " + target + " permanently.");
        } else {
          warnings.add("Deletes " + target + ". A versioned bucket keeps its earlier versions behind a delete "
              + "marker.");
        }
        if (impact != null && impact.known()) {
          impactFields.add(new Field("Object", impact.exists() ? "Deletes " + impact.describe()
              : "There is no object at " + target + " now"));
        }
      }
    }
    impactFields.add(new Field("Retried", "No: a request whose answer is lost is reported as unknown"));
    if (s.aws() && !s.generatedPolicy()) {
      warnings.add("AWS doesn't limit this change to " + target + ": the credentials' own permissions apply.");
    } else if (!s.aws()) {
      impactFields.add(new Field("Limited by", "The access key's own permissions on " + s.target()));
    }
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("operation", operation());
    facets.put("bucket", bucket);
    facets.put("key", Names.shown(key));
    String summary = tool + " on " + cfg.name() + ": " + request.method() + " " + target;
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + s.target()
            + " · " + bucket, summary.length() <= 120 ? summary : summary.substring(0, 119) + "…")
        .withFields(fields)
        .withImpact(impactFields)
        .withWarnings(warnings)
        .withFacets(facets)
        .withPayload(payload(uri));
  }

  /**
   * What {@code prepare} found at the target before the approval: whether an object is there, and what.
   *
   * @param known  whether the look succeeded
   * @param source a copy's source as found, or {@code null}
   */
  record Impact(boolean known, boolean exists, String size, String type, String modified, String source) {

    static final Impact UNKNOWN = new Impact(false, false, null, null, null, null);

    String describe() {
      StringBuilder out = new StringBuilder(size == null ? "the object" : size);
      if (type != null && !type.isBlank()) {
        out.append(" of ").append(type);
      }
      if (modified != null) {
        out.append(", modified ").append(modified);
      }
      return out.toString();
    }

    Impact withSource(String value) {
      return new Impact(known, exists, size, type, modified, value);
    }
  }
}
