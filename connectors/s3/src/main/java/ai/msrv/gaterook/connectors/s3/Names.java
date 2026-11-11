package ai.msrv.gaterook.connectors.s3;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

/**
 * The names an agent gives the S3 tools, checked before anything is built: bucket names, object keys, prefixes,
 * version ids, user metadata and content types. Nothing an agent sends becomes a URL, a host or a header name of its
 * own: a bucket is a DNS-safe name (or one path segment), a key is percent-encoded byte by byte into the path, every
 * other value goes into the query string or a header the connector names.
 *
 * <p>Keys whose path segments are {@code .} or {@code ..} (also before a {@code ;}) are refused, never normalized: a
 * server, a proxy or the HTTP client could resolve them to another object, and S3 itself signs the path as sent.
 * Backslashes and control characters are refused too; the core's egress guard sends neither.
 */
final class Names {

  /** Bucket names of S3-compatible services: OCI and Cloud Storage allow upper case and underscores, 222 characters. */
  static final Pattern BUCKET = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,220}[A-Za-z0-9]$");
  /** Amazon S3's rules for bucket names created since 2018: 3–63 lower-case letters, digits, dots and hyphens. */
  static final Pattern AWS_BUCKET = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
  /** A bucket name that is one DNS label, so that it can lead the host name (virtual-hosted style). */
  private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
  /** A pattern of the connection's bucket list: names and {@code *}. */
  static final Pattern BUCKET_GLOB = Pattern.compile("^[A-Za-z0-9._*-]{1,222}$");
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
  /** S3 version ids are opaque and URL-safe; {@code null} names the version of an object written before versioning. */
  private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._+/=-]{1,1024}$");
  private static final Pattern META_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  private static final Pattern CONTENT_TYPE = Pattern.compile("(?i)^[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,127}"
      + "(\\s?;\\s?[a-z0-9!#$&^_.+-]{1,64}=(\"[\\x20-\\x21\\x23-\\x7e]{0,64}\"|[a-z0-9!#$&^_.+-]{1,64}))*$");

  static final int MAX_KEY_BYTES = 1024;
  /** S3's limit for the user metadata of one object, names and values together. */
  static final int MAX_METADATA_BYTES = 2048;

  /**
   * Names that hold keys, credentials, {@code .env} settings or Terraform state more often than not: never read as
   * text (the GCP connector's {@code gcp_storage_read} refuses the same names).
   */
  private static final Pattern SENSITIVE_NAME = Pattern.compile("(?i)((^|/)(\\.env(\\.[^/]*)?|\\.netrc|\\.npmrc"
      + "|\\.pypirc|\\.git-credentials|id_(rsa|dsa|ecdsa|ed25519)|credentials(\\.json)?)"
      + "|\\.(pem|key|p12|pfx|jks|keystore|tfstate|tfstate\\.backup|kubeconfig))$");
  /** Content types of text. */
  private static final Pattern TEXT_TYPE = Pattern.compile("(?i)^(text/[^;]+|image/svg\\+xml|application/(json|xml"
      + "|yaml|x-yaml|javascript|x-javascript|ecmascript|x-ndjson|ndjson|jsonl|x-jsonlines|csv|x-csv|sql|x-sql|toml"
      + "|x-toml|x-sh|x-shellscript|graphql|x-www-form-urlencoded|[a-z0-9.+-]+\\+(json|xml|yaml)))\\s*(;.*)?$");
  /** Content types that say nothing about the bytes: the key's file name decides then. */
  private static final Pattern UNSPECIFIC = Pattern.compile("(?i)^(|application/octet-stream|binary/octet-stream"
      + "|application/x-unknown)\\s*(;.*)?$");
  private static final Pattern TEXT_NAME = Pattern.compile("(?i)\\.(txt|text|log|json|ndjson|jsonl|csv|tsv|yaml|yml"
      + "|xml|md|markdown|rst|adoc|sql|ini|cfg|conf|toml|properties|html?|css|js|mjs|cjs|ts|tsx|jsx|py|java|kt|go|rb"
      + "|rs|c|h|cpp|hpp|cs|php|sh|bash|zsh|ps1|gradle|tf|hcl|graphql|proto|svg|srt|vtt|diff|patch)$");
  private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset=\"?([a-z0-9._:-]+)\"?");

  private Names() {}

  /**
   * Why {@code bucket} can't name a bucket of this connection, or {@code null}. Amazon S3 takes its own rules; other
   * services the wider ones above. Directory buckets of S3 Express One Zone ({@code --x-s3}) need a session
   * protocol this connector doesn't speak.
   */
  static String bucketProblem(String bucket, boolean aws) {
    if (bucket == null || bucket.isEmpty()) {
      return "The call names no bucket";
    }
    if (!(aws ? AWS_BUCKET : BUCKET).matcher(bucket).matches() || bucket.contains("..")) {
      return bucket.length() > 80 ? "That isn't a bucket name" : bucket + " isn't a bucket name";
    }
    if (aws && bucket.endsWith("--x-s3")) {
      return bucket + " is an S3 Express One Zone directory bucket, which this connector doesn't read";
    }
    return null;
  }

  /** Whether {@code bucket} can lead the host name: one DNS label, so no dots (a TLS wildcard covers one label). */
  static boolean virtualHostable(String bucket, String host) {
    return DNS_LABEL.matcher(bucket).matches() && !IPV4.matcher(host).matches() && host.indexOf(':') < 0
        && !host.startsWith("[");
  }

  /**
   * Why {@code key} can't name an object, or {@code null}: 1 to 1,024 bytes of UTF-8 without control characters or
   * backslashes, no {@code .} or {@code ..} path segment.
   */
  static String keyProblem(String key) {
    if (key == null || key.isEmpty()) {
      return "The call names no object key";
    }
    String text = textProblem(key, "The key");
    if (text != null) {
      return text;
    }
    if (key.indexOf('\\') >= 0) {
      return "The key has a backslash, which GateRook doesn't send in a URL";
    }
    for (String segment : key.split("/", -1)) {
      int semicolon = segment.indexOf(';');
      String bare = semicolon < 0 ? segment : segment.substring(0, semicolon);
      if (bare.equals(".") || bare.equals("..")) {
        return "The key has a . or .. segment, which a server could resolve to another object";
      }
    }
    return null;
  }

  /** {@link #keyProblem}, and no invisible or direction-changing characters in a key a change writes. */
  static String newKeyProblem(String key) {
    String problem = keyProblem(key);
    if (problem != null) {
      return problem;
    }
    if (key.codePoints().anyMatch(Names::invisible)) {
      return "The key has invisible or direction-changing characters";
    }
    return null;
  }

  /** Why a prefix, delimiter or start key of a listing isn't usable, or {@code null}; empty is fine. */
  static String listTextProblem(String value, String what) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    return textProblem(value, what);
  }

  private static String textProblem(String value, String what) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c >= 0x7f && c <= 0x9f) {
        return what + " has control characters";
      }
      if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return what + " isn't valid Unicode";
      }
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
      return what + " is longer than " + MAX_KEY_BYTES + " bytes";
    }
    return null;
  }

  private static boolean invisible(int cp) {
    return cp >= 0x200b && cp <= 0x200f || cp >= 0x202a && cp <= 0x202e || cp >= 0x2066 && cp <= 0x2069
        || cp == 0xfeff || cp == 0x00ad || cp == 0x061c;
  }

  /** Why {@code versionId} isn't an S3 version id, or {@code null} when it is one or absent. */
  static String versionProblem(String versionId) {
    if (versionId == null) {
      return null;
    }
    return VERSION.matcher(versionId).matches() ? null : "versionId isn't an S3 version id";
  }

  /** Why a user metadata name can't be sent (as {@code x-amz-meta-<name>}), or {@code null}. */
  static String metadataNameProblem(String name) {
    return META_NAME.matcher(name).matches() ? null : "Metadata names are lower-case letters, digits and hyphens, "
        + "such as source-system; " + (name.length() > 40 ? "this one" : name) + " isn't one";
  }

  /** Why a user metadata value can't be sent as a header value, or {@code null}: printable ASCII, trimmed. */
  static String metadataValueProblem(String name, String value) {
    if (value.length() > 1024) {
      return "The metadata value of " + name + " is longer than 1,024 characters";
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        return "The metadata value of " + name + " has characters a header can't carry: use printable ASCII";
      }
    }
    if (!value.equals(value.strip())) {
      return "The metadata value of " + name + " starts or ends with spaces";
    }
    return null;
  }

  /** Whether {@code type} is a media type with parameters, as a {@code Content-Type} header carries one. */
  static boolean mediaType(String type) {
    return type != null && type.length() <= 255 && CONTENT_TYPE.matcher(type).matches();
  }

  /** Why {@code type} isn't a content type GateRook writes text as, or {@code null}. */
  static String contentTypeProblem(String type) {
    if (!mediaType(type)) {
      return "contentType must be a media type such as text/csv or application/json";
    }
    if (!TEXT_TYPE.matcher(type).matches()) {
      return "s3_put_object writes text: " + type + " isn't a text content type";
    }
    String charset = charset(type);
    if (charset != null && !charset.equals("utf-8") && !charset.equals("us-ascii")) {
      return "The content is sent as UTF-8, so contentType can't name the charset " + charset;
    }
    return null;
  }

  /** Whether an object is text by its content type, or by its key when the type says nothing. */
  static boolean text(String contentType, String key) {
    String type = contentType == null ? "" : contentType;
    return TEXT_TYPE.matcher(type).matches() || UNSPECIFIC.matcher(type).matches() && TEXT_NAME.matcher(key).find();
  }

  /** The {@code charset} parameter of a content type, lower case, or {@code null}. */
  static String charset(String contentType) {
    if (contentType == null) {
      return null;
    }
    var m = CHARSET.matcher(contentType);
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  /** Whether an object of this key holds keys, credentials, {@code .env} settings or Terraform state, by its name. */
  static boolean sensitive(String key) {
    return SENSITIVE_NAME.matcher(key).find();
  }

  /** A key as a URL path: every byte but unreserved characters and {@code /} percent-encoded, once. */
  static String path(String key) {
    return SdkHttpUtils.urlEncodeIgnoreSlashes(key);
  }

  /** A query parameter's name or value as SigV4 encodes it (RFC 3986, {@code %20} for a space). */
  static String query(String value) {
    return SdkHttpUtils.urlEncode(value);
  }

  /** A key or bucket for a message: cut to 200 characters. */
  static String shown(String name) {
    return name == null ? "" : name.length() <= 200 ? name : name.substring(0, 199) + "…";
  }
}
