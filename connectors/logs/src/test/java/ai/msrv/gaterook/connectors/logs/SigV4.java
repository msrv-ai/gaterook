package ai.msrv.gaterook.connectors.logs;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mockwebserver3.RecordedRequest;

/**
 * AWS Signature Version 4 of a request the way a server checks it (a service that is not S3): rebuilt from the bytes
 * the server received and the secret, never from what the client meant to send. A signature that matches covers
 * exactly the request line, headers and body on the wire.
 */
final class SigV4 {

  private SigV4() {}

  /** The signature the server computes for {@code request}. */
  static String expected(RecordedRequest request, String secret) throws GeneralSecurityException {
    String authorization = request.getHeaders().get("Authorization");
    String credential = between(authorization, "Credential=");
    String[] scope = credential.substring(credential.indexOf('/') + 1).split("/");
    String signedHeaders = between(authorization, "SignedHeaders=");
    String target = request.getRequestLine().split(" ")[1];
    int mark = target.indexOf('?');
    String path = mark < 0 ? target : target.substring(0, mark);
    String query = mark < 0 ? "" : target.substring(mark + 1);
    StringBuilder headers = new StringBuilder();
    for (String name : signedHeaders.split(";")) {
      headers.append(name).append(':').append(request.getHeaders().get(name).trim().replaceAll("\\s+", " "))
          .append('\n');
    }
    String canonical = request.getMethod() + "\n" + escape(path, true) + "\n" + canonicalQuery(query) + "\n" + headers
        + "\n" + signedHeaders + "\n" + hex(sha256(request.getBody().toByteArray()));
    String date = request.getHeaders().get("X-Amz-Date");
    String stringToSign = "AWS4-HMAC-SHA256\n" + date + "\n" + String.join("/", scope) + "\n"
        + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    byte[] key = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < scope.length; i++) {
      key = hmac(key, scope[i]);
    }
    return hex(hmac(key, stringToSign));
  }

  /** The signature the client sent. */
  static String sent(RecordedRequest request) {
    return between(request.getHeaders().get("Authorization"), "Signature=");
  }

  private static String between(String header, String label) {
    int start = header.indexOf(label) + label.length();
    int end = header.indexOf(',', start);
    return header.substring(start, end < 0 ? header.length() : end);
  }

  /** RFC 3986 escaping of the UTF-8 bytes; the path of a request is escaped once more than it was sent. */
  private static String escape(String text, boolean keepSlash) {
    StringBuilder out = new StringBuilder();
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
          || c == '~' || keepSlash && c == '/') {
        out.append(c);
      } else {
        out.append('%').append(String.format("%02X", b & 0xFF));
      }
    }
    return out.toString();
  }

  private static String canonicalQuery(String query) {
    TreeMap<String, List<String>> parameters = new TreeMap<>();
    if (!query.isEmpty()) {
      for (String pair : query.split("&")) {
        int equals = pair.indexOf('=');
        String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
        String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
        parameters.computeIfAbsent(escape(name, false), k -> new ArrayList<>()).add(escape(value, false));
      }
    }
    List<String> pairs = new ArrayList<>();
    parameters.forEach((name, values) -> {
      Collections.sort(values);
      values.forEach(value -> pairs.add(name + "=" + value));
    });
    return String.join("&", pairs);
  }

  private static byte[] sha256(byte[] bytes) throws GeneralSecurityException {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  private static byte[] hmac(byte[] key, String text) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
