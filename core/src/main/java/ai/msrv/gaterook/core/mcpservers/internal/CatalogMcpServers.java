package ai.msrv.gaterook.core.mcpservers.internal;

import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import ai.msrv.gaterook.core.connectors.SignedCatalog;
import ai.msrv.gaterook.core.mcpservers.McpServerCatalog;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The MCP servers of the signed catalog (step K6, {@link McpServerCatalog}): an entry ({@code github-mcp@0.6.2}) pins
 * an npm package with its {@code integrity} and the lockfile {@code npm ci --ignore-scripts} installs it from; the
 * lockfile is downloaded from the catalog's release and must be the file the index signed (SHA-256 and size). The C10
 * runtime then installs it into the server's folder, checks the integrity against the entry before every start and
 * runs {@code node <bin>}, never {@code npx}.
 *
 * <p>A lockfile is fetched once per SHA-256 (a review and the add that follows need the same bytes).
 */
@Component
class CatalogMcpServers implements McpServerCatalog {

  private final SignedCatalog catalog;
  private final Map<String, byte[]> lockfiles = new ConcurrentHashMap<>();

  CatalogMcpServers(SignedCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public Optional<Entry> entry(String ref) {
    return catalog.entry("mcp-server", ref).map(this::entry);
  }

  private Entry entry(SignedCatalog.Entry e) {
    JsonNode json = e.json();
    JsonNode npm = json.path("npm");
    String pkg = npm.path("package").asString(null);
    String version = npm.path("version").asString(null);
    String integrity = npm.path("integrity").asString(null);
    String bin = npm.path("bin").asString(null);
    if (pkg == null || version == null || integrity == null || bin == null || !"stdio".equals(json.path("transport")
        .asString("stdio"))) {
      throw ProblemException.unprocessable("Bad catalog entry", e.ref() + " doesn't name an npm package, its "
          + "integrity and its executable.", "/catalog");
    }
    JsonNode lockRef = npm.path("lockfile");
    String sha = lockRef.path("sha256").asString("");
    byte[] lockfile = lockfiles.computeIfAbsent(sha, k -> catalog.file(lockRef, "the lockfile of " + e.ref()));
    List<String> variables = new ArrayList<>();
    for (JsonNode v : json.path("env")) {
      if (v.path("name").isString()) {
        variables.add(v.get("name").asString());
      }
    }
    List<String> args = new ArrayList<>();
    json.path("args").forEach(a -> args.add(a.asString()));
    McpServerRuntime.NpmPackage npmPackage = new McpServerRuntime.NpmPackage(pkg, version, integrity, lockfile, bin,
        npm.path("node").asString(null), npm.path("ignoreScripts").asBoolean(true));
    return new Entry(e.id(), e.version(), json.path("serverName").asString(e.id()),
        json.path("description").asString(""), npmPackage, variables, args, e.signed());
  }
}
