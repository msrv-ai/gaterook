package ai.msrv.gaterook.connectors.mcpserver;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The names agents see for an MCP server's tools (report 11 §3.2, ARCHITECTURE.md §9 "An external MCP server must not
 * impersonate a built-in connector"): the connection's prefix and the upstream name, in the gateway's tool alphabet
 * ({@code ^[a-z][a-z0-9_]{0,63}$}: lower case, every other character an underscore, no dots — some client APIs refuse
 * them). A name over 64 characters, or one that two upstream names would share after the mapping, ends with four hex
 * characters of the upstream name's hash. Names the core reserves (its own tools, the built-in tools and their
 * families) are refused rather than renamed: a server can't publish {@code sql_execute} under any spelling.
 */
final class ToolNameMapper {

  static final int MAX = 64;
  private static final Pattern EXPOSED = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

  private ToolNameMapper() {}

  /** The exposed name, or why there is none. */
  record Name(String exposed, String problem) {}

  /**
   * @param taken    exposed names already given to earlier tools of the same server
   * @param reserved the core's verdict on a name ({@code ConnectorRegistry.reservedProblem})
   */
  static Name map(String prefix, String original, Set<String> taken, Function<String, Optional<String>> reserved) {
    if (original == null || original.isEmpty()) {
      return new Name(null, "it has no name");
    }
    if (original.length() > 128) {
      return new Name(null, "its name is longer than 128 characters");
    }
    StringBuilder b = new StringBuilder(prefix);
    String lower = original.toLowerCase(Locale.ROOT);
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
    }
    String name = b.toString();
    if (name.length() > MAX) {
      name = suffixed(name, original);
    }
    if (taken.contains(name)) {
      name = suffixed(name, original);
      if (taken.contains(name)) {
        return new Name(null, "another tool of this server already has the name " + name);
      }
    }
    if (!EXPOSED.matcher(name).matches()) {
      return new Name(null, "its name can't be written as a tool name");
    }
    Optional<String> problem = reserved.apply(name);
    if (problem.isPresent()) {
      return new Name(null, problem.get());
    }
    return new Name(name, null);
  }

  /** {@code name} cut to fit {@code _<4 hex>} of the upstream name's hash. */
  private static String suffixed(String name, String original) {
    String hex = Texts.sha256(original).substring(0, 4);
    String head = name.length() > MAX - 5 ? name.substring(0, MAX - 5) : name;
    return head + "_" + hex;
  }
}
