package ai.msrv.gaterook.core.mcpservers;

import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.util.List;
import java.util.Optional;

/**
 * SPI: the MCP server entries of the signed catalog (step K6). Each entry pins an npm package with the lockfile its
 * {@code npm ci} installs and the integrity every start checks again (report 11 §6.10), so a catalog server never runs
 * through {@code npx}. Until K6 provides a bean, "Add from catalog" answers that the catalog isn't available.
 */
public interface McpServerCatalog {

  /**
   * One entry ({@code github-mcp@0.6.2}).
   *
   * @param name        the connection name it is added under by default ({@code github-mcp})
   * @param variables   the environment variables it needs from secrets ({@code GITHUB_PERSONAL_ACCESS_TOKEN})
   * @param args        arguments after its executable ({@code stdio} for servers that need a mode)
   * @param signed      whether the entry's signature verified against the catalog key
   */
  record Entry(String id, String version, String name, String description, McpServerRuntime.NpmPackage pkg,
      List<String> variables, List<String> args, boolean signed) {

    public Entry {
      variables = variables == null ? List.of() : List.copyOf(variables);
      args = args == null ? List.of() : List.copyOf(args);
    }

    /** {@code github-mcp@0.6.2}. */
    public String ref() {
      return id + "@" + version;
    }
  }

  /** The entry {@code ref} names ({@code id@version}), or empty. */
  Optional<Entry> entry(String ref);
}
