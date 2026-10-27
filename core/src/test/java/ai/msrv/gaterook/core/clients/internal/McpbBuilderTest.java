package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Claude Desktop extension of an endpoint (report 03 §7.4): the manifest names the endpoint and the grant, never a
 * token; the ZIP holds the bridge the npm package ships; the same endpoint gives the same bytes. The app's E2E profile
 * runs {@code npx @anthropic-ai/mcpb@2.1.2 validate} on it.
 */
class McpbBuilderTest {

  @TempDir
  Path dir;

  private static McpbBuilder.Spec spec(Env env) {
    return new McpbBuilder.Spec("incident-prod", env, "gr_cx7q2lmaabcd", "0.4.0-SNAPSHOT", List.of(
        new McpbBuilder.Tool("sql_query", "Read from incident-db"), new McpbBuilder.Tool("sql_execute", "")),
        Path.of("/home/u/.gaterook/run/broker.json"), "darwin");
  }

  @Test
  void theManifestNamesTheEndpointAndTheGrantOnly() {
    JsonNode m = McpbBuilder.manifest(spec(Env.PROD));
    assertThat(m.path("manifest_version").asString()).isEqualTo("0.3");
    assertThat(m.path("name").asString()).isEqualTo("gaterook-incident-prod");
    assertThat(m.path("display_name").asString()).isEqualTo("GateRook · incident-prod (prod)");
    assertThat(m.path("version").asString()).isEqualTo("0.4.0-snapshot");
    assertThat(m.path("server").path("type").asString()).isEqualTo("node");
    assertThat(m.path("server").path("entry_point").asString()).isEqualTo("server/gw-bridge.mjs");
    assertThat(m.path("server").path("mcp_config").path("args").valueStream().map(JsonNode::asString).toList())
        .containsExactly("${__dirname}/server/gw-bridge.mjs", "stdio", "--endpoint", "incident-prod", "--grant",
            "gr_cx7q2lmaabcd", "--client", "claude-desktop");
    assertThat(m.path("server").path("mcp_config").path("env").path("GW_BROKER").asString())
        .isEqualTo("/home/u/.gaterook/run/broker.json");
    assertThat(m.path("tools").get(1).path("description").asString()).as("a tool without one gets its name")
        .isEqualTo("sql_execute");
    assertThat(m.path("compatibility").path("platforms").get(0).asString()).isEqualTo("darwin");
    assertThat(m.toString()).doesNotContain("gwb_").doesNotContain("gwt_");
    assertThat(McpbBuilder.displayName("dev", Env.DEV)).isEqualTo("GateRook · dev");
    assertThat(McpbBuilder.semver("0.4.0")).isEqualTo("0.4.0");
    assertThat(McpbBuilder.semver("dev")).isEqualTo("0.0.0");
  }

  @Test
  void theExtensionHoldsTheManifestAndTheBridgeAndIsOwnerOnly() throws IOException {
    Path a = dir.resolve("a/gaterook-incident-prod.mcpb");
    Path b = dir.resolve("b/gaterook-incident-prod.mcpb");
    McpbBuilder.write(a, spec(Env.PROD));
    McpbBuilder.write(b, spec(Env.PROD));
    assertThat(Files.readAllBytes(a)).as("reproducible").isEqualTo(Files.readAllBytes(b));
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(a))).isEqualTo("rw-------");
    try (ZipFile zip = new ZipFile(a.toFile())) {
      assertThat(Collections.list(zip.entries()).stream().map(ZipEntry::getName))
          .containsExactly("manifest.json", "server/gw-bridge.mjs");
      String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
          StandardCharsets.UTF_8);
      assertThat(JsonMapper.builder().build().readTree(manifest)).isEqualTo(McpbBuilder.manifest(spec(Env.PROD)));
      byte[] bridge = zip.getInputStream(zip.getEntry("server/gw-bridge.mjs")).readAllBytes();
      assertThat(bridge).isEqualTo(Files.readAllBytes(Path.of("../bridge/gw-bridge.mjs")));
    }
  }

  /** The broker signs with the same function as the core: bridge/test/broker.test.mjs checks the same vector. */
  @Test
  void theLaunchSecretSignatureMatchesTheBrokers() {
    LaunchSecret secret = LaunchSecret.fromBase64Url(java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
    assertThat(secret.sign("POST", "/internal/bridge/tokens", 1_792_900_000_000L, "AAAAAAAAAAAAAAAAAAAAAA"))
        .isEqualTo("1792900000000.AAAAAAAAAAAAAAAAAAAAAA.pW2mL07tz9Ju6YR3gKetHyKTmOuLxaf-sj4y2G1o828");
  }
}
