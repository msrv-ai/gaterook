package ai.msrv.gaterook.connectors.influxdb;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * InfluxDB's refusals as what the agent can do about them. The server's words come from its error body — InfluxDB 3's
 * {@code {"error", "data": {"error_message", "line_number"}}}, 2.x's {@code {"code", "message"}}, 1.x's
 * {@code {"error"}} or plain text — sanitized and cut to one short line.
 */
final class Errors {

  static final int MAX_SAID = 300;

  private Errors() {}

  /** The server's own words of an error body, sanitized, one line. */
  static String message(byte[] body) {
    String text = new String(body, StandardCharsets.UTF_8).strip();
    String said = text;
    if (text.startsWith("{")) {
      try {
        JsonNode n = InfluxHttp.JSON.readTree(text);
        String message = n.path("message").asString(n.path("error").asString(""));
        JsonNode data = n.path("data");
        if (data.path("error_message").isString()) {
          message = message + ": " + data.get("error_message").asString()
              + (data.path("line_number").isNumber() ? " (line " + data.get("line_number").asInt() + ")" : "");
        }
        if (!message.isBlank()) {
          said = message;
        }
      } catch (JacksonException e) {
        // Not JSON after all: the text as it is.
      }
    }
    said = firstLine(OutputSanitizer.sanitize(said));
    if (said.isBlank()) {
      return "no reason given";
    }
    return said.length() <= MAX_SAID ? said : said.substring(0, MAX_SAID - 1) + "…";
  }

  /** The first words of a body that should have been JSON. */
  static String excerpt(byte[] body) {
    String said = firstLine(OutputSanitizer.decode(body, 0, Math.min(body.length, 600)));
    return said.isBlank() ? "an empty answer" : said.length() <= 200 ? said : said.substring(0, 199) + "…";
  }

  static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    String line = (nl < 0 ? s : s.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }

  /** A refused request of status {@code status}. */
  static ConnectorException of(int status, byte[] body, InfluxHttp.Request r, InfluxHttp.Call c) {
    String said = message(body);
    if (r.change()) {
      return write(status, said, c);
    }
    String what = r.what();
    return switch (status) {
      case 400 -> ConnectorException.badInput(what + " refused the request: " + said + ".");
      case 401 -> ConnectorException.unavailable("InfluxDB refused the credentials of " + c.cfg().name() + ": " + said
          + ". Check them under Credentials, then test the connection.", null);
      case 403 -> ConnectorException.resourceError("The credentials of " + c.cfg().name() + " may not do this: "
          + said + ".", null);
      case 404 -> ConnectorException.badInput(what + ": " + said + ".");
      case 405, 501 -> ConnectorException.resourceError(what + ": the server doesn't offer this API (" + status
          + "). Check which server the connection is set to, then test it.", null);
      case 413 -> ConnectorException.resourceError(what + ": the request is too large for the server.", null);
      case 429 -> ConnectorException.resourceError(what + " is rate limited: try again in a minute.", null);
      default -> status >= 502 && status <= 504
          ? ConnectorException.unavailable(what + " failed (" + status + "): " + said + ".", null)
          : ConnectorException.resourceError(what + " failed (" + status + "): " + said + ".", null);
    };
  }

  /**
   * A refused write. InfluxDB 1.x and 2.x keep the points before or around a refused one ("partial write", or 422);
   * InfluxDB 3 with {@code accept_partial=false} writes all points or none. A server error leaves the outcome unknown.
   */
  static ConnectorException write(int status, String said, InfluxHttp.Call c) {
    if (status == 422 || said.toLowerCase(Locale.ROOT).contains("partial write")) {
      return ConnectorException.outcomeUnknown("InfluxDB wrote part of the points and refused the rest: " + said
          + ". Read them back before writing again.", null);
    }
    if (status == 400 && c.settings().organization() == null && said.contains("orgID or org")) {
      return ConnectorException.resourceError("InfluxDB OSS needs the organization of the bucket, so nothing was "
          + "written: set Organization in the connection's settings.", null);
    }
    return switch (status) {
      case 400 -> ConnectorException.resourceError("InfluxDB refused the write, so nothing was written: " + said
          + ".", null);
      case 401 -> ConnectorException.unavailable("InfluxDB refused the credentials of " + c.cfg().name() + ", so "
          + "nothing was written: " + said + ".", null);
      case 403 -> ConnectorException.resourceError("The credentials of " + c.cfg().name() + " may not write to this "
          + "database, so nothing was written: " + said + ".", null);
      case 404 -> ConnectorException.badInput("The database doesn't exist, so nothing was written: " + said + ".");
      case 413 -> ConnectorException.resourceError("InfluxDB refused a write this large, so nothing was written: "
          + "split it.", null);
      case 429 -> ConnectorException.resourceError("InfluxDB is rate limiting writes, so nothing was written: try "
          + "again in a minute.", null);
      default -> status >= 500
          ? ConnectorException.outcomeUnknown("InfluxDB failed with " + status + " during the write, so whether the "
              + "points were written is not known: " + said + ". Read them back before writing again.", null)
          : ConnectorException.resourceError("InfluxDB refused the write (" + status + "): " + said + ".", null);
    };
  }
}
