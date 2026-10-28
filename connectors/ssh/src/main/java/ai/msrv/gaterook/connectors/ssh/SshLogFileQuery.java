package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Filename filters apply only to the configured root-relative allowlist, without remote directory discovery. */
record SshLogFileQuery(String needle, boolean caseSensitive, Pattern expression) {
  static SshLogFileQuery parse(JsonNode args) throws ConnectorException {
    String query = text(args, "query", 1024);
    String mode = text(args, "matchMode", 16);
    if (mode == null) mode = "literal";
    if (!mode.equals("literal") && !mode.equals("regex")) {
      throw ConnectorException.badInput("matchMode must be literal or regex.");
    }
    JsonNode caseOption = args.get("caseSensitive");
    if (caseOption != null && !caseOption.isBoolean()) {
      throw ConnectorException.badInput("caseSensitive must be true or false.");
    }
    boolean caseSensitive = caseOption == null || caseOption.asBoolean();
    if (query == null || query.isEmpty()) return new SshLogFileQuery(null, caseSensitive, null);
    if (mode.equals("regex")) {
      try {
        return new SshLogFileQuery(query, caseSensitive,
            Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE));
      } catch (PatternSyntaxException e) {
        throw ConnectorException.badInput("Invalid RE2 regular expression. Lookaround and backreferences are not supported.");
      }
    }
    return new SshLogFileQuery(caseSensitive ? query : query.toLowerCase(Locale.ROOT), caseSensitive, null);
  }

  boolean matches(String file) {
    if (needle == null) return true;
    if (expression != null) return expression.matcher(file).find();
    return (caseSensitive ? file : file.toLowerCase(Locale.ROOT)).contains(needle);
  }

  private static String text(JsonNode args, String name, int max) throws ConnectorException {
    JsonNode value = args.get(name);
    if (value == null) return null;
    if (!value.isString() || value.asString().codePointCount(0, value.asString().length()) > max) {
      throw ConnectorException.badInput(name + " must be a string of at most " + max + " characters.");
    }
    return value.asString();
  }
}
