package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.core.connectors.plugins.PluginArtifacts;
import ai.msrv.gaterook.core.connectors.plugins.PluginCatalog;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The signed catalog's entries for the modules that install from it besides plugins (ARCHITECTURE.md §7.5, step K6):
 * {@code templates} takes a template's definitions ({@code POST /api/templates {catalogRef}}), {@code mcpservers} an MCP
 * server's npm package and the lockfile it pins. The index, its signature and the downloads stay inside this module
 * ({@code connectors.plugins}); what crosses the boundary are the verified entries as JSON and pinned files as bytes.
 */
@Component
public class SignedCatalog {

  /** The largest file the catalog pins besides a plugin JAR (an npm lockfile). */
  public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

  /**
   * One verified entry.
   *
   * @param signed whether the catalog vouches for it ({@code signed:false} entries are listed, not reviewed)
   * @param json   the entry as the signed index has it
   */
  public record Entry(String kind, String id, String version, boolean signed, JsonNode json) {

    /** {@code id@version}. */
    public String ref() {
      return id + "@" + version;
    }
  }

  private final PluginCatalog catalog;
  private final PluginArtifacts artifacts;

  SignedCatalog(PluginCatalog catalog, PluginArtifacts artifacts) {
    this.catalog = catalog;
    this.artifacts = artifacts;
  }

  /**
   * The entry of {@code kind} that {@code ref} ({@code id@version}) names in the current index.
   *
   * @throws ProblemException 409 when nothing may be installed from the catalog now (never loaded, expired)
   */
  public Optional<Entry> entry(String kind, String ref) {
    catalog.installProblem().ifPresent(problem -> {
      throw ProblemException.conflict("Catalog unavailable", problem);
    });
    int at = ref == null ? -1 : ref.lastIndexOf('@');
    if (at <= 0 || at == ref.length() - 1) {
      return Optional.empty();
    }
    String id = ref.substring(0, at);
    String version = ref.substring(at + 1);
    return catalog.entry(kind, id, version).map(e -> new Entry(kind, id, version,
        !e.path("signed").isBoolean() || e.get("signed").asBoolean(true), e));
  }

  /**
   * A file the entry pins ({@code {url, sha256, size}}), downloaded and checked against the signed hash and size.
   *
   * @throws ProblemException 409 when it can't be downloaded or isn't the file the catalog signed
   */
  public byte[] file(JsonNode ref, String what) {
    return artifacts.fetchPinned(ref, MAX_FILE_BYTES, what, "application/json, */*;q=0.5");
  }
}
