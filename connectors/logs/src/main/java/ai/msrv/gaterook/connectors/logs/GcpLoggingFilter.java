package ai.msrv.gaterook.connectors.logs;

/** Keeps a native Logging expression inside the scope and timestamp clauses the gateway adds around it. */
final class GcpLoggingFilter {

  private GcpLoggingFilter() {}

  /** Removes line comments, preserving quoted values and escapes, and checks only expression boundaries. */
  static String expression(String input) {
    StringBuilder out = new StringBuilder(input.length());
    boolean quoted = false;
    boolean escaped = false;
    int depth = 0;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (quoted) {
        out.append(c);
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
        continue;
      }
      if (c == '-' && i + 1 < input.length() && input.charAt(i + 1) == '-') {
        while (i < input.length() && input.charAt(i) != '\n' && input.charAt(i) != '\r') i++;
        if (i < input.length()) out.append(input.charAt(i));
        continue;
      }
      if (c == '"') quoted = true;
      else if (c == '(') depth++;
      else if (c == ')' && --depth < 0) throw invalid("parentheses");
      out.append(c);
    }
    if (quoted) throw invalid("quoted string");
    if (depth != 0) throw invalid("parentheses");
    return out.toString().strip();
  }

  private static IllegalArgumentException invalid(String what) {
    return new IllegalArgumentException("The Cloud Logging filter has an unclosed or unmatched " + what + ".");
  }
}
