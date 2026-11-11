package ai.msrv.gaterook.connectors.support.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The profiles the build ships ({@code verified-profiles.json}), held to what GateRook checked on their servers on
 * 2026-11-11: the addresses they recognize, the switches their setup sets, and the tools they start in Approval or
 * Off. The index also passes every check {@link VerifiedProfiles#parse} makes, or this class wouldn't load.
 */
class BundledVerifiedProfilesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode http(String url) {
    ObjectNode c = JSON.createObjectNode();
    c.put("transport", "http");
    c.put("url", url);
    return c;
  }

  private static JsonNode stdio(String... argv) {
    ObjectNode c = JSON.createObjectNode();
    c.put("transport", "stdio");
    ArrayNode command = c.putArray("command");
    for (String a : argv) {
      command.add(a);
    }
    return c;
  }

  private static Optional<String> id(JsonNode config) {
    return VerifiedProfiles.of(config).map(VerifiedProfiles.Profile::id);
  }

  private static VerifiedProfiles.Profile profile(String id) {
    return VerifiedProfiles.byId(id).orElseThrow();
  }

  @Test
  void theBuildShipsTheCheckedServersInCardOrder() {
    assertThat(VerifiedProfiles.all()).extracting(VerifiedProfiles.Profile::id).containsExactly("aws-knowledge",
        "cloudflare-api", "digitalocean", "vercel", "firebase", "supabase", "netlify", "azure-devops-remote",
        "azure-devops");
    for (VerifiedProfiles.Profile p : VerifiedProfiles.all()) {
      assertThat(VerifiedProfiles.of(VerifiedProfiles.sampleConfig(p))).as(p.id()).contains(p);
      assertThat(p.documentation()).as(p.id()).startsWith("https://");
      assertThat(p.tools()).as(p.id()).allSatisfy(t -> assertThat(t.reason()).endsWith("."));
      assertThat(p.tools()).as(p.id()).filteredOn(t -> t.kind() == VerifiedProfiles.Kind.DELEGATING)
          .allSatisfy(t -> assertThat(t.start()).isEqualTo(VerifiedProfiles.Start.OFF));
    }
  }

  @Test
  void eachVendorsServerIsRecognizedByItsAddressOrCommand() {
    assertThat(id(http("https://knowledge-mcp.global.api.aws"))).contains("aws-knowledge");
    assertThat(id(http("https://knowledge-mcp.global.api.aws/"))).contains("aws-knowledge");
    assertThat(id(http("https://mcp.cloudflare.com/mcp"))).contains("cloudflare-api");
    assertThat(id(http("https://mcp.cloudflare.com/mcp?codemode=false"))).contains("cloudflare-api");
    assertThat(id(http("https://apps.mcp.digitalocean.com/mcp"))).contains("digitalocean");
    assertThat(id(http("https://mcp.digitalocean.com/mcp"))).as("the router the registry names")
        .contains("digitalocean");
    assertThat(id(http("https://mcp.vercel.com"))).contains("vercel");
    assertThat(id(http("https://mcp.vercel.com/my-team/my-project"))).contains("vercel");
    assertThat(id(http("https://mcp.supabase.com/mcp?project_ref=abc&read_only=true"))).contains("supabase");
    assertThat(id(http("https://mcp.netlify.com/mcp"))).contains("netlify");
    assertThat(id(http("https://netlify-mcp.netlify.app/mcp"))).as("its older address").contains("netlify");
    assertThat(id(stdio("npx", "-y", "firebase-tools@latest", "mcp", "--dir", "/work/app"))).contains("firebase");
    assertThat(id(stdio("firebase", "mcp"))).contains("firebase");
    assertThat(id(stdio("npx", "-y", "@azure-devops/mcp@2.11.0", "contoso", "--authentication", "azcli")))
        .contains("azure-devops");
    assertThat(id(stdio("mcp-server-azuredevops", "contoso", "-d", "core"))).contains("azure-devops");
    assertThat(id(http("https://mcp.dev.azure.com/contoso"))).contains("azure-devops-remote");
    assertThat(id(http("https://mcp.dev.azure.com/contoso/"))).contains("azure-devops-remote");
    assertThat(id(http("https://mcp.dev.azure.com"))).as("the root, which takes the organization in each call")
        .contains("azure-devops-remote");
  }

  @Test
  void otherServersOnTheVendorsHostsAndLookalikesGetNoProfile() {
    assertThat(id(http("https://mcp.vercel.com/docs/mcp"))).as("Vercel's documentation server").isEmpty();
    assertThat(id(http("https://mcp.vercel.com/cli/mcp"))).as("Vercel's CLI server").isEmpty();
    assertThat(id(http("https://observability.mcp.cloudflare.com/mcp"))).as("an older Cloudflare server")
        .isEmpty();
    assertThat(id(http("https://mcp.supabase.com.attacker.example/mcp"))).isEmpty();
    assertThat(id(http("https://a.b.mcp.digitalocean.com/mcp"))).isEmpty();
    assertThat(id(http("http://mcp.supabase.com/mcp"))).isEmpty();
    assertThat(id(stdio("npx", "-y", "firebase-tools", "deploy"))).isEmpty();
    assertThat(id(stdio("npx", "-y", "@supabase/mcp-server-supabase@0.16.1", "--read-only"))).isEmpty();
    assertThat(id(http("https://mcp.dev.azure.com/contoso/mcp"))).as("a path Azure DevOps answers with 404")
        .isEmpty();
    assertThat(id(http("https://mcp.dev.azure.com.attacker.example/contoso"))).isEmpty();
    assertThat(id(http("http://mcp.dev.azure.com/contoso"))).isEmpty();
    assertThat(id(http("https://dev.azure.com/contoso"))).isEmpty();
  }

  @Test
  void azureDevOpssRemoteSetupPinsItsReadOnlyHeaderAndStartsEveryWriteInApproval() {
    VerifiedProfiles.Profile remote = profile("azure-devops-remote");

    assertThat(remote.url()).isEqualTo("https://mcp.dev.azure.com/{organization}");
    assertThat(remote.placeholders()).extracting(VerifiedProfiles.Placeholder::name).containsExactly("organization");
    assertThat(remote.headers()).containsExactly(new FixedHeaders.Header("X-MCP-Readonly", "true"));
    assertThat(VerifiedProfiles.setupConfig(remote).path("fixedHeaders")).hasSize(1);
    assertThat(remote.pins()).extracting(VerifiedProfiles.Pin::in).containsExactly(VerifiedProfiles.In.HEADER);

    ObjectNode pinned = (ObjectNode) http("https://mcp.dev.azure.com/contoso");
    pinned.putArray("fixedHeaders").addObject().put("name", "x-mcp-readonly").put("text", "true");
    assertThat(VerifiedProfiles.pins(remote, pinned)).extracting(VerifiedProfiles.PinState::set).containsExactly(true);
    ObjectNode other = (ObjectNode) http("https://mcp.dev.azure.com/contoso");
    other.putArray("fixedHeaders").addObject().put("name", "X-MCP-Readonly").put("text", "TRUE");
    assertThat(VerifiedProfiles.pins(remote, other)).as("Microsoft documents the string true")
        .extracting(VerifiedProfiles.PinState::set).containsExactly(false);
    List<VerifiedProfiles.PinState> bare = VerifiedProfiles.pins(remote, http("https://mcp.dev.azure.com/contoso"));
    assertThat(bare).extracting(VerifiedProfiles.PinState::set).containsExactly(false);
    assertThat(bare.getFirst().pin().missing()).contains("Without X-MCP-Readonly: true");

    // Microsoft publishes no annotations for its remote tools: every write its documentation lists is named.
    assertThat(remote.tools()).hasSize(18).allSatisfy(t -> assertThat(t.start())
        .isEqualTo(VerifiedProfiles.Start.APPROVAL));
    assertThat(remote.rule("pipelines_write")).get().extracting(VerifiedProfiles.ToolRule::kind)
        .isEqualTo(VerifiedProfiles.Kind.CODE);
    assertThat(remote.rule("enterprise_live_migration_write")).get().extracting(VerifiedProfiles.ToolRule::kind)
        .isEqualTo(VerifiedProfiles.Kind.WRITE);
    assertThat(remote.rule("wit_work_item")).as("a read").isEmpty();
    assertThat(remote.rule("repo_file_write")).as("only the local server has it").isEmpty();
  }

  @Test
  void supabasesSetupPinsReadOnlyAndAsksForTheProject() {
    VerifiedProfiles.Profile supabase = profile("supabase");

    assertThat(supabase.url()).isEqualTo("https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true");
    assertThat(supabase.placeholders()).extracting(VerifiedProfiles.Placeholder::name)
        .containsExactly("project_ref");
    String pinned = "https://mcp.supabase.com/mcp?project_ref=abcdefghijklmnopqrst&read_only=true";
    assertThat(VerifiedProfiles.pins(supabase, http(pinned))).extracting(VerifiedProfiles.PinState::set)
        .containsExactly(true, true);
    List<VerifiedProfiles.PinState> bare = VerifiedProfiles.pins(supabase, http("https://mcp.supabase.com/mcp"));
    assertThat(bare).extracting(VerifiedProfiles.PinState::set).containsExactly(false, false);
    assertThat(bare.getFirst().pin().missing()).contains("Without read_only=true");
    // In read-only mode Supabase itself runs SQL read-only, so execute_sql follows its declaration.
    assertThat(supabase.rule("execute_sql")).isEmpty();
    assertThat(supabase.rule("apply_migration")).get().extracting(VerifiedProfiles.ToolRule::kind)
        .isEqualTo(VerifiedProfiles.Kind.CODE);
  }

  @Test
  void passthroughDelegatingAndCredentialToolsStartStricter() {
    assertThat(profile("cloudflare-api").rule("execute")).get().extracting(VerifiedProfiles.ToolRule::start)
        .isEqualTo(VerifiedProfiles.Start.APPROVAL);
    assertThat(profile("cloudflare-api").rule("whoami")).isEmpty();
    assertThat(profile("digitalocean").rule("harness-runtime-send-input")).get()
        .extracting(VerifiedProfiles.ToolRule::start).isEqualTo(VerifiedProfiles.Start.OFF);
    assertThat(profile("digitalocean").rule("doks-get-kubeconfig")).get()
        .extracting(VerifiedProfiles.ToolRule::kind).isEqualTo(VerifiedProfiles.Kind.CREDENTIALS);
    assertThat(profile("vercel").rule("buy_domain")).get().extracting(VerifiedProfiles.ToolRule::start)
        .isEqualTo(VerifiedProfiles.Start.OFF);
    assertThat(profile("vercel").rule("get_project_env")).get().extracting(VerifiedProfiles.ToolRule::kind)
        .isEqualTo(VerifiedProfiles.Kind.CREDENTIALS);
    assertThat(profile("firebase").rule("apptesting_run_test")).get().extracting(VerifiedProfiles.ToolRule::start)
        .isEqualTo(VerifiedProfiles.Start.OFF);
    assertThat(profile("netlify").rule("netlify-deploy-site")).get().extracting(VerifiedProfiles.ToolRule::kind)
        .isEqualTo(VerifiedProfiles.Kind.CREDENTIALS);
    assertThat(profile("aws-knowledge").tools()).as("every tool of it is declared read only").isEmpty();
    assertThat(profile("azure-devops").rule("wit_work_item_attachment")).get()
        .extracting(VerifiedProfiles.ToolRule::kind).isEqualTo(VerifiedProfiles.Kind.WRITE);
    assertThat(profile("azure-devops").rule("pipelines_write")).get().extracting(VerifiedProfiles.ToolRule::start)
        .isEqualTo(VerifiedProfiles.Start.APPROVAL);
  }

  @Test
  void netlifysSetupListsOneToolPerOperation() {
    VerifiedProfiles.Profile netlify = profile("netlify");

    assertThat(netlify.url()).isEqualTo("https://mcp.netlify.com/mcp?verbose=true");
    assertThat(VerifiedProfiles.pins(netlify, http("https://mcp.netlify.com/mcp")))
        .extracting(VerifiedProfiles.PinState::set).containsExactly(false);
  }

  @Test
  void firebasesSetupPinsTheCheckedRelease() {
    assertThat(profile("firebase").command()).containsExactly("npx", "-y", "firebase-tools@15.35.0", "mcp");
    assertThat(profile("firebase").transport()).isEqualTo("stdio");
  }
}
