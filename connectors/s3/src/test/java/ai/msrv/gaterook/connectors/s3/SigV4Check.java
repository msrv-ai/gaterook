package ai.msrv.gaterook.connectors.s3;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mockwebserver3.RecordedRequest;

/**
 * Checks a request the way S3 does on its side, written from the SigV4 specification and independent of the SDK
 * signer the connector uses: the canonical request from what arrived on the wire (the raw path as received, the query
 * decoded and encoded again, the signed headers as received), the payload hash against the body, and the signature
 * with the secret key. {@code SigningVectorsTest} checks this verifier against AWS's published examples.
 */
final class SigV4Check {

  private static final Pattern AUTH = Pattern.compile("^AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/"
      + "([^/]+)/aws4_request, ?SignedHeaders=([a-z0-9;-]+), ?Signature=([0-9a-f]{64})$");

  private SigV4Check() {}

  /** What the check found: the credential scope and the signed header names. */
  record Signed(String accessKey, String region, String service, List<String> headers) {}

  static Signed verify(RecordedRequest r, String secretKey) {
    Map<String, List<String>> headers = new TreeMap<>();
    for (String name : r.getHeaders().names()) {
      headers.put(name.toLowerCase(Locale.ROOT), r.getHeaders().values(name));
    }
    byte[] body = r.getBody() == null ? new byte[0] : r.getBody().toByteArray();
    return verify(r.getMethod(), r.getTarget(), headers, body, secretKey);
  }

  /**
   * @param target  the request target exactly as sent: the encoded path and query
   * @param headers by lower-case name
   */
  static Signed verify(String method, String target, Map<String, List<String>> headers, byte[] body,
      String secretKey) {
    String auth = first(headers, "authorization");
    if (auth == null) {
      throw new AssertionError("No Authorization header: " + method + " " + target);
    }
    Matcher m = AUTH.matcher(auth);
    if (!m.matches()) {
      throw new AssertionError("Not a SigV4 Authorization header: " + auth);
    }
    String date = m.group(2);
    String region = m.group(3);
    String service = m.group(4);
    List<String> signed = List.of(m.group(5).split(";"));
    String amzDate = first(headers, "x-amz-date");
    if (amzDate == null || !amzDate.startsWith(date)) {
      throw new AssertionError("x-amz-date " + amzDate + " doesn't match the scope's date " + date);
    }
    if (!signed.contains("host")) {
      throw new AssertionError("host isn't signed");
    }
    for (String name : headers.keySet()) {
      if (name.startsWith("x-amz-") && !signed.contains(name)) {
        throw new AssertionError(name + " is sent without being signed");
      }
    }
    String payloadHash = first(headers, "x-amz-content-sha256");
    if (payloadHash == null || !payloadHash.equals(hex(sha256(body)))) {
      throw new AssertionError("x-amz-content-sha256 " + payloadHash + " isn't the body's SHA-256");
    }
    int q = target.indexOf('?');
    String path = q < 0 ? target : target.substring(0, q);
    String query = q < 0 ? "" : target.substring(q + 1);
    StringBuilder canonical = new StringBuilder();
    canonical.append(method).append('\n').append(path).append('\n').append(canonicalQuery(query)).append('\n');
    for (String name : signed) {
      List<String> values = headers.get(name);
      if (values == null) {
        throw new AssertionError(name + " is signed but wasn't sent");
      }
      canonical.append(name).append(':').append(String.join(",", values).strip().replaceAll(" +", " "))
          .append('\n');
    }
    canonical.append('\n').append(String.join(";", signed)).append('\n').append(payloadHash);
    String scope = date + "/" + region + "/" + service + "/aws4_request";
    String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
        + hex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
    for (String part : List.of(region, service, "aws4_request")) {
      key = hmac(key, part);
    }
    String expected = hex(hmac(key, toSign));
    if (!expected.equals(m.group(6))) {
      throw new AssertionError("The signature doesn't verify. Canonical request as received:\n" + canonical);
    }
    return new Signed(m.group(1), region, service, signed);
  }

  /** The query as S3 canonicalizes it: each name and value decoded, then encoded per RFC 3986, sorted. */
  static String canonicalQuery(String raw) {
    if (raw.isEmpty()) {
      return "";
    }
    List<String> pairs = new ArrayList<>();
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String name = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      pairs.add(encode(decode(name)) + "=" + encode(decode(value)));
    }
    pairs.sort(null);
    return String.join("&", pairs);
  }

  private static String decode(String s) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%' && i + 2 < s.length()) {
        out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
        i += 2;
      } else {
        byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
      }
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  static String encode(String s) {
    StringBuilder out = new StringBuilder();
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
          || c == '~') {
        out.append(c);
      } else {
        out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
      }
    }
    return out.toString();
  }

  private static String first(Map<String, List<String>> headers, String name) {
    List<String> v = headers.get(name);
    return v == null || v.isEmpty() ? null : v.getFirst();
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
