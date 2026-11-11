package ai.msrv.gaterook.connectors.support.mcp;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for an HTTP MCP server's headers with a fixed value ({@code fixedHeaders} in its settings, ARCHITECTURE.md
 * §7.5): values the person writes in the settings and GateRook sends as they are, such as a server's read-only switch
 * ({@code X-MCP-Readonly: true}). They are not secrets, so a credential never goes there, and the headers the
 * transport sets for each request can't be replaced. The core checks them when a server is added or edited, the
 * runtime again before it connects.
 */
public final class FixedHeaders {

  /** Lower-case names a fixed header may not have; names starting with {@code mcp-} are the protocol's too. */
  static final Set<String> RESERVED = Set.of("authorization", "proxy-authorization", "cookie", "host", "content-type",
      "content-length", "transfer-encoding", "connection", "accept", "last-event-id");

  /** One header as the settings give it. */
  public record Header(String name, String text) {}

  private FixedHeaders() {}

  /**
   * Why {@code fixed} can't be sent beside headers from secrets named {@code secretHeaders}, or {@code null}: the name
   * of a credential, a header the transport sets, a name given twice, or one that also comes from a secret.
   */
  public static String problem(List<Header> fixed, List<String> secretHeaders) {
    Set<String> fromSecrets = new HashSet<>();
    for (String n : secretHeaders) {
      fromSecrets.add(n.toLowerCase(Locale.ROOT));
    }
    Set<String> seen = new HashSet<>();
    for (Header h : fixed) {
      String n = h.name().toLowerCase(Locale.ROOT);
      if (n.equals("authorization") || n.equals("proxy-authorization") || n.equals("cookie")) {
        return h.name() + " can't have a fixed value: it carries a credential, which belongs in a header from a "
            + "secret.";
      }
      if (RESERVED.contains(n) || n.startsWith("mcp-")) {
        return h.name() + " can't have a fixed value: GateRook sets it for each request.";
      }
      if (!seen.add(n)) {
        return h.name() + " is given twice: give each header once.";
      }
      if (fromSecrets.contains(n)) {
        return h.name() + " is both a header from a secret and a fixed header: keep one.";
      }
    }
    return null;
  }
}
