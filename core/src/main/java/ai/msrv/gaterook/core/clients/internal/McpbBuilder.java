package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.connector.api.Env;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Claude Desktop's extension for one endpoint (report 03 §7.4, ARCHITECTURE.md §7.2): {@code mcp-gateway-<slug>.mcpb},
 * a ZIP with {@code manifest.json} (MCPB manifest 0.3, {@code server.type: node}) and {@code server/gw-bridge.mjs}, the
 * zero-dependency bridge that Claude's own Node runs. The manifest names the endpoint and the grant, never a token:
 * the bridge asks the broker for a 15-minute one. One extension per endpoint; a prod endpoint says so in its name.
 */
public final class McpbBuilder {

  /** The bridge inside every extension, copied from {@code bridge/gw-bridge.mjs} at build time. */
  public static final String BRIDGE_RESOURCE = "ai/msrv/gaterook/core/clients/bridge/gw-bridge.mjs";
  public static final String MANIFEST_VERSION = "0.3";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** Fixed entry times: the same endpoint and grant give the same bytes. */
  private static final FileTime EPOCH = FileTime.fromMillis(315_532_800_000L);

  /** One published tool of the endpoint, as the manifest lists it. */
  public record Tool(String name, String description) {}

  /**
   * @param version     the gateway's version, a semver the manifest requires
   * @param brokerInfo  {@code ~/.mcp-gateway/run/broker.json}, where the bridge finds the broker
   * @param platform    {@code darwin}, {@code win32} or {@code linux}
   */
  public record Spec(String slug, Env env, String grantId, String version, List<Tool> tools, Path brokerInfo,
      String platform) {}

  private McpbBuilder() {}

  /** {@code mcp-gateway-<slug>.mcpb}. */
  public static String fileName(String slug) {
    return "mcp-gateway-" + slug + ".mcpb";
  }

  /** {@code MCP Gateway · dev}, {@code MCP Gateway · incident-prod (prod)}. */
  public static String displayName(String slug, Env env) {
    return "MCP Gateway · " + slug + (env == Env.PROD ? " (prod)" : "");
  }

  /** The manifest of the extension: what the preview shows and the ZIP holds. */
  public static ObjectNode manifest(Spec s) {
    ObjectNode m = JSON.createObjectNode();
    m.put("manifest_version", MANIFEST_VERSION);
    m.put("name", "mcp-gateway-" + s.slug());
    m.put("display_name", displayName(s.slug(), s.env()));
    m.put("version", semver(s.version()));
    m.put("description", "The tools of the MCP Gateway endpoint " + s.slug() + (s.env() == Env.PROD ? " (prod)" : "")
        + ". Every change waits for your approval in MCP Gateway; no secret or token is stored in this extension.");
    m.putObject("author").put("name", "MCP Gateway");
    ObjectNode server = m.putObject("server");
    server.put("type", "node");
    server.put("entry_point", "server/gw-bridge.mjs");
    ObjectNode config = server.putObject("mcp_config");
    config.put("command", "node");
    ArrayNode args = config.putArray("args");
    for (String a : List.of("${__dirname}/server/gw-bridge.mjs", "stdio", "--endpoint", s.slug(), "--grant",
        s.grantId(), "--client", "claude-desktop")) {
      args.add(a);
    }
    ObjectNode env = config.putObject("env");
    env.put("GW_BROKER", s.brokerInfo().toString());
    env.put("GW_CLIENT", "claude-desktop");
    ArrayNode tools = m.putArray("tools");
    for (Tool t : s.tools()) {
      ObjectNode o = tools.addObject();
      o.put("name", t.name());
      o.put("description", t.description() == null || t.description().isBlank() ? t.name() : t.description());
    }
    m.put("tools_generated", true);
    ObjectNode compatibility = m.putObject("compatibility");
    compatibility.putArray("platforms").add(s.platform());
    compatibility.putObject("runtimes").put("node", ">=18.0.0");
    return m;
  }

  /** The manifest as the preview shows it. */
  public static String manifestText(Spec s) {
    return JsonEntryEditor.pretty("manifest", manifest(s)).substring("\"manifest\": ".length()) + "\n";
  }

  /** Writes the extension to {@code target} atomically (0600: it names a grant). */
  public static void write(Path target, Spec s) throws IOException {
    Files.createDirectories(target.getParent());
    Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
    try {
      try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(out)) {
        put(zip, "manifest.json", manifestText(s).getBytes(StandardCharsets.UTF_8));
        put(zip, "server/gw-bridge.mjs", bridge());
      }
      ConfigFileWriter.ownerOnly(tmp);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** The bridge script of the extensions. */
  public static byte[] bridge() throws IOException {
    try (InputStream in = McpbBuilder.class.getClassLoader().getResourceAsStream(BRIDGE_RESOURCE)) {
      if (in == null) {
        throw new IOException(BRIDGE_RESOURCE + " is missing from this build");
      }
      return in.readAllBytes();
    }
  }

  private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
    ZipEntry e = new ZipEntry(name);
    e.setLastModifiedTime(EPOCH);
    zip.putNextEntry(e);
    zip.write(data);
    zip.closeEntry();
  }

  /** {@code 0.4.0-SNAPSHOT} → {@code 0.4.0-snapshot}: the manifest's version is a semver. */
  static String semver(String version) {
    if (version == null || version.isBlank()) {
      return "0.0.0";
    }
    return version.matches("\\d+\\.\\d+\\.\\d+.*") ? version.replace("SNAPSHOT", "snapshot") : "0.0.0";
  }
}
