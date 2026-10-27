package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an HTTP answer becomes for the agent (report 10 §3.3): any status is data ({@code isError} false), the body as
 * parsed JSON, sanitized text or a digest of binary bytes, the allowlisted headers, and a first text line
 * "POST https://…/v1/refunds → 201 Created · application/json · 43 B · 84 ms". The core masks and cuts it afterwards;
 * the text repeats the body only as far as the size limit leaves room next to the structured content.
 */
final class ResponseReader {

  /** One answer as it came in. */
  record Answer(int status, String reason, Map<String, List<String>> headers, byte[] body, boolean truncated,
      long elapsedMs, int redirects, String redirectNote, HttpUrl url) {}

  private static final Set<String> TEXT_SUBTYPES = Set.of("xml", "yaml", "x-yaml", "csv", "x-ndjson", "ndjson",
      "javascript", "x-javascript", "x-www-form-urlencoded", "graphql", "html", "x-sh", "sql");

  private ResponseReader() {}

  static ToolResult read(String method, String shownUrl, Answer a, long maxBytes) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("status", a.status());
    s.put("ok", a.status() >= 200 && a.status() < 300);
    s.put("method", method);
    s.put("url", shownUrl);
    ObjectNode headers = s.putObject("headers");
    int removed = 0;
    String contentType = null;
    for (Map.Entry<String, List<String>> e : a.headers().entrySet()) {
      String name = e.getKey().toLowerCase(Locale.ROOT);
      if (name.equals("set-cookie") || name.equals("set-cookie2")) {
        removed += e.getValue().size();
      } else if (Headers.shownToAgent(name)) {
        headers.put(name, OutputSanitizer.sanitize(String.join(", ", e.getValue())));
      }
      if (name.equals("content-type") && !e.getValue().isEmpty()) {
        contentType = e.getValue().getFirst();
      }
    }
    s.put("contentType", contentType == null ? null : OutputSanitizer.sanitize(contentType));
    MediaType media = contentType == null ? null : MediaType.parse(contentType);
    byte[] body = a.body() == null ? new byte[0] : a.body();

    String bodyText = null;
    String note = null;
    JsonNode json = null;
    ObjectNode binary = null;
    if (body.length > 0) {
      Kind kind = kind(media, body);
      if (kind == Kind.JSON) {
        if (a.truncated()) {
          note = "Cut at the size limit, so the JSON isn't parsed: the text shows its start.";
          bodyText = decode(body, media);
        } else {
          try {
            json = JsonText.JSON.readTree(body);
          } catch (JacksonException e) {
            note = "The body isn't valid JSON.";
          }
          bodyText = decode(body, media);
        }
      } else if (kind == Kind.TEXT) {
        bodyText = decode(body, media);
      } else {
        binary = JsonNodeFactory.instance.objectNode();
        binary.put("bytes", body.length);
        binary.put("sha256", sha256(body));
      }
    }
    if (json != null) {
      s.set("json", json);
      s.putNull("text");
    } else {
      s.putNull("json");
      if (bodyText != null) {
        s.put("text", bodyText);
      } else {
        s.putNull("text");
      }
    }
    if (binary != null) {
      s.set("binary", binary);
    } else {
      s.putNull("binary");
    }
    s.put("bytes", body.length);
    s.put("truncated", a.truncated());
    if (a.truncated()) {
      s.put("truncatedReason", "size_limit");
    } else {
      s.putNull("truncatedReason");
    }
    String location = first(a.headers(), "location");
    if (a.status() >= 300 && a.status() < 400 && location != null) {
      s.put("redirect", OutputSanitizer.sanitize(location));
    } else {
      s.putNull("redirect");
    }
    if (a.redirects() > 0) {
      s.put("redirects", a.redirects());
    }
    if (removed > 0) {
      s.put("headersRemoved", removed);
    }
    s.put("elapsedMs", a.elapsedMs());

    StringBuilder line = new StringBuilder();
    line.append(method).append(' ').append(shownUrl).append(" → ").append(a.status());
    if (a.reason() != null && !a.reason().isBlank()) {
      line.append(' ').append(OutputSanitizer.sanitize(a.reason()));
    }
    if (contentType != null) {
      line.append(" · ").append(media == null ? OutputSanitizer.sanitize(contentType) : media.type() + "/"
          + media.subtype());
    }
    line.append(" · ").append(Sizes.text(body.length)).append(a.truncated() ? " (cut at the size limit)" : "")
        .append(" · ").append(a.elapsedMs()).append(" ms");
    if (removed > 0) {
      line.append(" · ").append(removed).append(removed == 1 ? " header" : " headers").append(" removed");
    }
    if (a.redirects() > 0) {
      line.append(" · after ").append(a.redirects()).append(a.redirects() == 1 ? " redirect" : " redirects");
    }
    if (s.path("redirect").isString()) {
      line.append("\nRedirect to ").append(s.path("redirect").asString()).append(a.redirectNote() == null
          ? ": not followed. Call again with that path if it is allowed." : ": " + a.redirectNote());
    }
    if (note != null) {
      line.append('\n').append(note);
    }
    if (binary != null) {
      line.append("\n\nBinary response (").append(media == null ? "unknown type" : media.type() + "/"
          + media.subtype()).append(", ").append(Sizes.text(body.length)).append(") is not shown.");
    } else if (bodyText != null && !bodyText.isEmpty()) {
      // The structured content already holds the body; the text gets what the size limit leaves next to it.
      long structuredBytes = JsonText.compact(s).getBytes(StandardCharsets.UTF_8).length;
      long room = maxBytes - structuredBytes - line.length() * 3L - 64;
      if (room >= bodyText.getBytes(StandardCharsets.UTF_8).length) {
        line.append("\n\n").append(bodyText);
      } else if (room > 256) {
        line.append("\n\n").append(prefix(bodyText, room)).append("\n… the rest is in structuredContent.");
      } else {
        line.append("\n\nThe body is in structuredContent.");
      }
    }
    return ToolResult.json(s, line.toString());
  }

  enum Kind { JSON, TEXT, BINARY }

  static Kind kind(MediaType media, byte[] body) {
    if (media != null) {
      String type = media.type().toLowerCase(Locale.ROOT);
      String sub = media.subtype().toLowerCase(Locale.ROOT);
      if (sub.equals("json") || sub.endsWith("+json")) {
        return Kind.JSON;
      }
      if (type.equals("text") || TEXT_SUBTYPES.contains(sub) || sub.endsWith("+xml") || sub.endsWith("+yaml")) {
        return Kind.TEXT;
      }
      if (!type.equals("application") || !sub.equals("octet-stream")) {
        return Kind.BINARY;
      }
    }
    return looksLikeText(body) ? Kind.TEXT : Kind.BINARY;
  }

  /** No NUL and valid UTF-8: what an API without a content type most likely meant as text. */
  private static boolean looksLikeText(byte[] body) {
    for (byte b : body) {
      if (b == 0) {
        return false;
      }
    }
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  private static String decode(byte[] body, MediaType media) {
    Charset charset = StandardCharsets.UTF_8;
    if (media != null) {
      try {
        Charset declared = media.charset(null);
        if (declared != null) {
          charset = declared;
        }
      } catch (RuntimeException e) {
        // An unknown charset name reads as UTF-8 with replacement.
      }
    }
    if (charset.equals(StandardCharsets.UTF_8)) {
      return OutputSanitizer.decode(body);
    }
    return OutputSanitizer.sanitize(new String(body, charset));
  }

  private static String first(Map<String, List<String>> headers, String lowerName) {
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (e.getKey().equalsIgnoreCase(lowerName) && !e.getValue().isEmpty()) {
        return e.getValue().getFirst();
      }
    }
    return null;
  }

  private static String prefix(String s, long bytes) {
    long n = 0;
    int i = 0;
    while (i < s.length()) {
      int cp = s.codePointAt(i);
      int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (n + len > bytes) {
        break;
      }
      n += len;
      i += Character.charCount(cp);
    }
    return s.substring(0, i);
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The reason phrase of a status, for HTTP/2 answers that carry none. */
  static String reason(int status, String given) {
    if (given != null && !given.isBlank()) {
      return given;
    }
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 202 -> "Accepted";
      case 204 -> "No Content";
      case 301 -> "Moved Permanently";
      case 302 -> "Found";
      case 303 -> "See Other";
      case 304 -> "Not Modified";
      case 307 -> "Temporary Redirect";
      case 308 -> "Permanent Redirect";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 409 -> "Conflict";
      case 410 -> "Gone";
      case 422 -> "Unprocessable Content";
      case 429 -> "Too Many Requests";
      case 500 -> "Internal Server Error";
      case 502 -> "Bad Gateway";
      case 503 -> "Service Unavailable";
      case 504 -> "Gateway Timeout";
      default -> "";
    };
  }
}
