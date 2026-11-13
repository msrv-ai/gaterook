package com.gaterook.core.mcpservers.internal.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code Bearer} challenge of a {@code WWW-Authenticate} header (RFC 7235 §4.1, RFC 6750 §3, RFC 9728 §5.1): what
 * an MCP server's 401 says about signing in ({@code resource_metadata}, {@code scope}, {@code error}). A header may
 * hold several challenges ({@code Basic realm="a", Bearer resource_metadata="…"}) and a response several headers; the
 * first Bearer challenge counts. Parameter names don't depend on case, values are unquoted ({@code \"} → {@code "}).
 *
 * @param params the challenge's parameters by lower-case name; empty when there is no Bearer challenge
 */
public record WwwAuthenticate(Map<String, String> params) {

  /** Longest header looked at: a server can't make the gateway parse megabytes. */
  private static final int MAX = 8 * 1024;

  public WwwAuthenticate {
    params = Map.copyOf(params);
  }

  /** The Bearer challenge among {@code headers}, or one without parameters when none is there. */
  public static WwwAuthenticate parse(List<String> headers) {
    if (headers != null) {
      for (String h : headers) {
        Map<String, String> bearer = bearer(h);
        if (bearer != null) {
          return new WwwAuthenticate(bearer);
        }
      }
    }
    return new WwwAuthenticate(Map.of());
  }

  public static WwwAuthenticate parse(String header) {
    return parse(header == null ? List.of() : List.of(header));
  }

  /** The URL of the protected resource's metadata the server names, or {@code null}. */
  public String resourceMetadata() {
    return params.get("resource_metadata");
  }

  /** The scopes the server asks for, space separated, or {@code null}. */
  public String scope() {
    return params.get("scope");
  }

  public String error() {
    return params.get("error");
  }

  /** Whether the header had a Bearer challenge at all ({@code "Bearer"} alone counts: its parameters are empty). */
  public boolean bearer() {
    return !params.isEmpty();
  }

  /** The parameters of the first Bearer challenge in one header value, or {@code null} when it has none. */
  private static Map<String, String> bearer(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String h = header.length() > MAX ? header.substring(0, MAX) : header;
    Cursor c = new Cursor(h);
    Map<String, String> current = null;
    boolean inBearer = false;
    while (true) {
      c.skipSpacesAndCommas();
      if (c.end()) {
        break;
      }
      String token = c.token();
      if (token.isEmpty()) {
        // Not a token: skip one character so a malformed header can't loop.
        c.next();
        continue;
      }
      c.skipSpaces();
      if (c.peek() == '=' && !c.token68Ahead()) {
        c.next();
        c.skipSpaces();
        String value = c.peek() == '"' ? c.quoted() : c.token();
        if (inBearer) {
          current.putIfAbsent(token.toLowerCase(Locale.ROOT), value);
        }
        continue;
      }
      // A new challenge: its scheme.
      if (inBearer) {
        return current;
      }
      inBearer = token.equalsIgnoreCase("bearer");
      if (inBearer) {
        current = new LinkedHashMap<>();
      }
      // A token68 after the scheme (Negotiate abc==) belongs to that challenge.
      c.skipSpaces();
      if (!c.end() && c.peek() != ',' && c.token68Ahead()) {
        c.token68();
      }
    }
    if (inBearer && current.isEmpty()) {
      // "Bearer" alone: a Bearer challenge without parameters.
      current.put("", "");
    }
    return inBearer ? current : null;
  }

  /** A position in a header value. */
  private static final class Cursor {
    private final String s;
    private int i;

    Cursor(String s) {
      this.s = s;
    }

    boolean end() {
      return i >= s.length();
    }

    char peek() {
      return end() ? '\0' : s.charAt(i);
    }

    void next() {
      i++;
    }

    void skipSpaces() {
      while (!end() && (peek() == ' ' || peek() == '\t')) {
        i++;
      }
    }

    void skipSpacesAndCommas() {
      while (!end() && (peek() == ' ' || peek() == '\t' || peek() == ',')) {
        i++;
      }
    }

    /** An RFC 7230 token (tchar*). */
    String token() {
      int start = i;
      while (!end() && tchar(peek())) {
        i++;
      }
      return s.substring(start, i);
    }

    /**
     * Whether what follows is a token68 ({@code abc==}) rather than {@code name=value}: '=' signs that end the
     * token68 are followed by a comma, the end, or spaces then a comma or the end.
     */
    boolean token68Ahead() {
      int j = i;
      while (j < s.length() && token68char(s.charAt(j))) {
        j++;
      }
      int equals = j;
      while (j < s.length() && s.charAt(j) == '=') {
        j++;
      }
      if (j == equals) {
        return false;
      }
      while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
        j++;
      }
      return j >= s.length() || s.charAt(j) == ',';
    }

    void token68() {
      while (!end() && (token68char(peek()) || peek() == '=')) {
        i++;
      }
    }

    /** A quoted-string without its quotes, with quoted pairs undone. */
    String quoted() {
      StringBuilder out = new StringBuilder();
      i++;
      while (!end()) {
        char ch = s.charAt(i++);
        if (ch == '\\' && !end()) {
          out.append(s.charAt(i++));
        } else if (ch == '"') {
          break;
        } else {
          out.append(ch);
        }
      }
      return out.toString();
    }

    private static boolean tchar(char c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static boolean token68char(char c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~+/".indexOf(c) >= 0;
    }
  }
}
