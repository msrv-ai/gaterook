package ai.msrv.gaterook.connectors.s3;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One request to S3 as GateRook sends it: the operation (for messages), the HTTP method, the bucket and key it is
 * about, the query parameters (raw values, sorted) and the headers it signs (lower-case names, sorted), and the body.
 * {@link #uri} places the bucket in the host (virtual-hosted style) or the path (path style) of the connection's
 * endpoint and percent-encodes the key and the query once, as SigV4 for S3 signs them; nothing is normalized.
 *
 * @param bucket {@code null} for {@code ListBuckets}
 * @param key    {@code null} for bucket-level requests
 * @param body   {@code null} for none
 */
record S3Request(String operation, String method, String bucket, String key, SortedMap<String, String> query,
    SortedMap<String, String> headers, byte[] body) {

  /** SHA-256 of an empty body, the {@code x-amz-content-sha256} of requests without one. */
  static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  S3Request {
    query = new TreeMap<>(query);
    headers = new TreeMap<>(headers);
  }

  static S3Request of(String operation, String method, String bucket, String key) {
    return new S3Request(operation, method, bucket, key, new TreeMap<>(), new TreeMap<>(), null);
  }

  S3Request param(String name, String value) {
    SortedMap<String, String> q = new TreeMap<>(query);
    q.put(name, value);
    return new S3Request(operation, method, bucket, key, q, headers, body);
  }

  S3Request header(String name, String value) {
    SortedMap<String, String> h = new TreeMap<>(headers);
    h.put(name.toLowerCase(java.util.Locale.ROOT), value);
    return new S3Request(operation, method, bucket, key, query, h, body);
  }

  S3Request withBody(byte[] value) {
    return new S3Request(operation, method, bucket, key, query, headers, value);
  }

  /**
   * The URL on {@code base} ({@code scheme://host[:port][/base-path]}): {@code bucket.host/key} when
   * {@code virtualHosted}, else {@code host/bucket/key}.
   */
  URI uri(URI base, boolean virtualHosted) {
    String host = base.getRawAuthority();
    String basePath = base.getRawPath() == null ? "" : base.getRawPath();
    StringBuilder url = new StringBuilder(base.getScheme()).append("://");
    if (bucket != null && virtualHosted) {
      url.append(bucket).append('.');
    }
    url.append(host).append(basePath);
    if (bucket == null) {
      url.append('/');
    } else if (virtualHosted) {
      url.append('/').append(key == null ? "" : Names.path(key));
    } else {
      url.append('/').append(bucket);
      if (key != null) {
        url.append('/').append(Names.path(key));
      }
    }
    if (!query.isEmpty()) {
      char sep = '?';
      for (Map.Entry<String, String> p : query.entrySet()) {
        url.append(sep).append(Names.query(p.getKey())).append('=').append(Names.query(p.getValue()));
        sep = '&';
      }
    }
    return URI.create(url.toString());
  }

  /** Whether this request may lead with the bucket in the host on {@code base}. */
  boolean virtualHosted(URI base, boolean pathStyle) {
    return bucket != null && !pathStyle && Names.virtualHostable(bucket, base.getHost());
  }

  /** The hex SHA-256 of the body: {@code x-amz-content-sha256}. */
  String bodySha256() {
    return body == null || body.length == 0 ? EMPTY_SHA256 : sha256(body);
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
