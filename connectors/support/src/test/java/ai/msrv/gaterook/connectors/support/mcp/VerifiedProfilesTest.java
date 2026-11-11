package ai.msrv.gaterook.connectors.support.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The matching, pins and checks of verified profiles, over a fixture index; {@link BundledVerifiedProfilesTest} holds
 * the profiles the build ships to the same checks.
 */
class VerifiedProfilesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Two servers at URLs (one exact host, one host under a suffix) and one command. */
  private static final String FIXTURE = """
      {"schema": 1, "profiles": [
        {"id": "acme", "name": "Acme", "publisher": "Acme", "category": "Cloud platform",
         "description": "Acme's own server.", "documentation": "https://docs.acme.example/mcp",
         "registryName": "com.acme/mcp", "checked": "2026-11-11",
         "url": "https://mcp.acme.example/mcp?project={project}&read_only=true",
         "placeholders": [{"name": "project", "description": "Your project's ID."}],
         "pins": [
           {"parameter": "read_only", "setTo": "true", "purpose": "Acme lists only its reads.",
            "missing": "Without read_only=true, Acme lists its writes too."},
           {"parameter": "project", "purpose": "One project only.", "missing": "Without project, every project."}],
         "signIn": "OAuth.",
         "tools": [
           {"tool": "run_code", "start": "approval", "kind": "code", "reason": "It runs code."},
           {"tool": "ask_agent", "start": "off", "kind": "delegating", "reason": "It asks Acme's agent."}],
         "match": {"hosts": ["mcp.acme.example", "mcp.acme-legacy.example"], "paths": ["/mcp", "/sse", "/team/*"],
          "excludePaths": ["/team/docs"]}},
        {"id": "ocean", "name": "Ocean", "publisher": "Ocean", "category": "Cloud platform",
         "description": "One server per service.", "documentation": "https://docs.ocean.example/mcp",
         "checked": "2026-11-11", "url": "https://{service}.mcp.ocean.example/mcp",
         "placeholders": [{"name": "service", "description": "apps, droplets."}],
         "headers": [{"name": "X-Read-Only", "text": "true"}, {"name": "X-Toolsets", "text": "apps,dns"}],
         "pins": [{"header": "X-Read-Only", "setTo": "true", "purpose": "Ocean lists only reads.",
                   "missing": "Without X-Read-Only: true, Ocean lists its writes too."}],
         "signIn": "A token.",
         "match": {"hosts": ["mcp.ocean.example"], "hostSuffix": "mcp.ocean.example", "paths": ["/mcp"]}},
        {"id": "fire", "name": "Fire", "publisher": "Fire", "category": "Cloud platform",
         "description": "A command.", "documentation": "https://docs.fire.example/mcp", "checked": "2026-11-11",
         "command": ["npx", "-y", "fire-tools@1.2.3", "mcp"], "signIn": "The CLI's sign-in.",
         "match": {"npm": "fire-tools", "bin": "fire", "args": ["mcp"]}}
      ]}""";

  private static List<VerifiedProfiles.Profile> fixture() {
    return VerifiedProfiles.parse(JSON.readTree(FIXTURE));
  }

  private static VerifiedProfiles.Profile profile(String id) {
    return fixture().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
  }

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

  @Test
  void anExactHostMatchesItsPathsWhateverTheQuery() {
    VerifiedProfiles.Profile acme = profile("acme");

    assertThat(acme.matches(http("https://mcp.acme.example/mcp"))).isTrue();
    assertThat(acme.matches(http("https://MCP.Acme.Example/mcp/"))).isTrue();
    assertThat(acme.matches(http("https://mcp.acme.example/sse?project=p1"))).isTrue();
    assertThat(acme.matches(http("https://mcp.acme.example:443/mcp"))).isTrue();
    assertThat(acme.matches(http("https://mcp.acme-legacy.example/mcp"))).as("another of its hosts").isTrue();
    assertThat(acme.matches(http("https://mcp.acme.example/team/project"))).as("a path pattern").isTrue();
    assertThat(acme.transport()).isEqualTo("http");
  }

  @Test
  void theVendorsOtherServersOnTheHostAreLeftOut() {
    VerifiedProfiles.Profile acme = profile("acme");

    assertThat(acme.matches(http("https://mcp.acme.example/team/docs"))).isFalse();
    assertThat(acme.matches(http("https://mcp.acme.example/team/docs/"))).isFalse();
    assertThat(acme.matches(http("https://mcp.acme.example/team/cli"))).isTrue();
  }

  @Test
  void anotherAddressIsNotTheVendors() {
    VerifiedProfiles.Profile acme = profile("acme");

    assertThat(acme.matches(http("http://mcp.acme.example/mcp"))).as("plain HTTP").isFalse();
    assertThat(acme.matches(http("https://mcp.acme.example:8443/mcp"))).as("another port").isFalse();
    assertThat(acme.matches(http("https://user@mcp.acme.example/mcp"))).as("user info").isFalse();
    assertThat(acme.matches(http("https://mcp.acme.example.evil.example/mcp"))).as("a longer host").isFalse();
    assertThat(acme.matches(http("https://x.mcp.acme.example/mcp"))).as("a subdomain").isFalse();
    assertThat(acme.matches(http("https://mcp.acme.example/other"))).as("another path").isFalse();
    assertThat(acme.matches(http("https://mcp.acme.example/mcp/extra"))).as("a deeper path").isFalse();
    assertThat(acme.matches(stdio("npx", "acme"))).as("a command").isFalse();
    assertThat(acme.matches(http("not a url"))).isFalse();
    assertThat(acme.matches(null)).isFalse();
  }

  @Test
  void aSuffixMatchesOneLabelBelowIt() {
    VerifiedProfiles.Profile ocean = profile("ocean");

    assertThat(ocean.matches(http("https://apps.mcp.ocean.example/mcp"))).isTrue();
    assertThat(ocean.matches(http("https://droplets-2.mcp.ocean.example/mcp"))).isTrue();
    assertThat(ocean.matches(http("https://mcp.ocean.example/mcp"))).as("the suffix, listed as a host").isTrue();
    assertThat(ocean.matches(http("https://a.b.mcp.ocean.example/mcp"))).as("two labels").isFalse();
    assertThat(ocean.matches(http("https://evilmcp.ocean.example/mcp"))).as("not under it").isFalse();
    assertThat(ocean.matches(http("https://-x.mcp.ocean.example/mcp"))).as("not a DNS label").isFalse();
  }

  @Test
  void aCommandMatchesItsPackageOrExecutableAndSubcommand() {
    VerifiedProfiles.Profile fire = profile("fire");

    assertThat(fire.matches(stdio("npx", "-y", "fire-tools@1.2.3", "mcp"))).isTrue();
    assertThat(fire.matches(stdio("npx", "fire-tools", "mcp", "--dir", "/work"))).isTrue();
    assertThat(fire.matches(stdio("/usr/local/bin/npx", "--yes", "fire-tools@latest", "mcp"))).isTrue();
    assertThat(fire.matches(stdio("C:\\Program Files\\nodejs\\npx.cmd", "-y", "fire-tools", "mcp"))).isTrue();
    assertThat(fire.matches(stdio("pnpm", "dlx", "fire-tools", "mcp"))).isTrue();
    assertThat(fire.matches(stdio("fire", "mcp"))).isTrue();
    assertThat(fire.transport()).isEqualTo("stdio");

    assertThat(fire.matches(stdio("npx", "-y", "fire-tools", "deploy"))).as("another subcommand").isFalse();
    assertThat(fire.matches(stdio("npx", "-y", "fire-tools"))).as("no subcommand").isFalse();
    assertThat(fire.matches(stdio("npx", "-y", "fire-tools-mcp", "mcp"))).as("another package").isFalse();
    assertThat(fire.matches(stdio("pnpm", "fire-tools", "mcp"))).as("pnpm without dlx").isFalse();
    assertThat(fire.matches(stdio("node", "fire-tools", "mcp"))).as("not a runner").isFalse();
    assertThat(fire.matches(http("https://mcp.fire.example/mcp"))).isFalse();
  }

  @Test
  void aScopedPackageKeepsItsScope() {
    JsonNode root = JSON.readTree(FIXTURE.replace("\"fire-tools@1.2.3\"", "\"@fire/tools@1.2.3\"")
        .replace("\"npm\": \"fire-tools\"", "\"npm\": \"@fire/tools\""));
    VerifiedProfiles.Profile fire = VerifiedProfiles.parse(root).get(2);

    assertThat(fire.matches(stdio("npx", "-y", "@fire/tools@1.2.3", "mcp"))).isTrue();
    assertThat(fire.matches(stdio("npx", "-y", "@fire/tools", "mcp"))).isTrue();
    assertThat(fire.matches(stdio("npx", "-y", "@fire/other", "mcp"))).isFalse();
  }

  @Test
  void pinsSayHowTheyStandInTheUrl() {
    VerifiedProfiles.Profile acme = profile("acme");

    assertThat(VerifiedProfiles.pins(acme, http("https://mcp.acme.example/mcp?project=p1&read_only=true")))
        .extracting(VerifiedProfiles.PinState::set).containsExactly(true, true);
    assertThat(VerifiedProfiles.pins(acme, http("https://mcp.acme.example/mcp?read_only=false&project=p1")))
        .extracting(VerifiedProfiles.PinState::set).containsExactly(false, true);
    assertThat(VerifiedProfiles.pins(acme, http("https://mcp.acme.example/mcp")))
        .extracting(VerifiedProfiles.PinState::set).containsExactly(false, false);
    assertThat(VerifiedProfiles.pins(acme, http("https://mcp.acme.example/mcp?project={project}&read_only=true")))
        .as("a placeholder isn't the person's value").extracting(VerifiedProfiles.PinState::set)
        .containsExactly(true, false);
    assertThat(VerifiedProfiles.pins(acme, http("https://mcp.acme.example/mcp?project=&read_only=TRUE")))
        .extracting(VerifiedProfiles.PinState::set).containsExactly(false, false);
    assertThat(VerifiedProfiles.pins(acme, http("https://mcp.acme.example/mcp?read_only=true&read_only=false#x")))
        .as("the first of a repeated name counts").first().extracting(VerifiedProfiles.PinState::set)
        .isEqualTo(true);
  }

  @Test
  void aHeaderPinStandsInTheFixedHeadersWhateverTheNamesCase() {
    VerifiedProfiles.Profile ocean = profile("ocean");
    ObjectNode set = (ObjectNode) http("https://apps.mcp.ocean.example/mcp");
    set.putArray("fixedHeaders").addObject().put("name", "x-read-only").put("text", "true");
    ObjectNode other = (ObjectNode) http("https://apps.mcp.ocean.example/mcp");
    other.putArray("fixedHeaders").addObject().put("name", "X-Read-Only").put("text", "false");

    assertThat(ocean.pins()).extracting(VerifiedProfiles.Pin::in).containsExactly(VerifiedProfiles.In.HEADER);
    assertThat(VerifiedProfiles.pins(ocean, set)).extracting(VerifiedProfiles.PinState::set).containsExactly(true);
    assertThat(VerifiedProfiles.pins(ocean, other)).extracting(VerifiedProfiles.PinState::set)
        .containsExactly(false);
    assertThat(VerifiedProfiles.pins(ocean, http("https://apps.mcp.ocean.example/mcp")))
        .extracting(VerifiedProfiles.PinState::set).containsExactly(false);
    assertThat(VerifiedProfiles.setupConfig(ocean).path("fixedHeaders")).hasSize(2);
  }

  @Test
  void rulesNameToolsByTheServersOwnName() {
    VerifiedProfiles.Profile acme = profile("acme");

    assertThat(acme.rule("run_code")).get().extracting(VerifiedProfiles.ToolRule::start)
        .isEqualTo(VerifiedProfiles.Start.APPROVAL);
    assertThat(acme.rule("ask_agent")).get().extracting(VerifiedProfiles.ToolRule::kind)
        .isEqualTo(VerifiedProfiles.Kind.DELEGATING);
    assertThat(acme.rule("ask_agent")).get().extracting(VerifiedProfiles.ToolRule::start)
        .isEqualTo(VerifiedProfiles.Start.OFF);
    assertThat(acme.rule("list_projects")).isEmpty();
    assertThat(acme.rule("acme_run_code")).as("not the published, prefixed name").isEmpty();
  }

  @Test
  void theSetupOfAProfileIsWhatTheFormSends() {
    assertThat(VerifiedProfiles.setupConfig(profile("acme")).path("url").asString())
        .isEqualTo("https://mcp.acme.example/mcp?project={project}&read_only=true");
    assertThat(VerifiedProfiles.setupConfig(profile("fire")).path("command").size()).isEqualTo(4);
  }

  @Test
  void aBadIndexIsRefusedWithWhatIsWrong() {
    assertThatThrownBy(() -> VerifiedProfiles.parse(JSON.readTree("{\"schema\": 2, \"profiles\": []}")))
        .hasMessageContaining("schema");
    assertBad(FIXTURE.replace("\"id\": \"ocean\"", "\"id\": \"acme\""), "listed twice");
    assertBad(FIXTURE.replace("\"id\": \"ocean\"", "\"id\": \"Ocean\""), "bad id");
    assertBad(FIXTURE.replace("&read_only=true\"", "\""), "pins read_only");
    assertBad(FIXTURE.replace("\"setTo\": \"true\"", "\"setTo\": \"false\""), "pins read_only");
    assertBad(FIXTURE.replace("[{\"name\": \"project\", \"description\": \"Your project's ID.\"}]", "[]"),
        "placeholders");
    assertBad(FIXTURE.replace("\"start\": \"off\", \"kind\": \"delegating\"",
        "\"start\": \"approval\", \"kind\": \"delegating\""), "delegating");
    assertBad(FIXTURE.replace("\"start\": \"approval\", \"kind\": \"code\"",
        "\"start\": \"later\", \"kind\": \"code\""), "bad start or kind");
    assertBad(FIXTURE.replace("\"paths\": [\"/mcp\", \"/sse\", \"/team/*\"]", "\"paths\": [\"/other\"]"),
        "own setup");
    assertBad(FIXTURE.replace("\"documentation\": \"https://docs.acme.example/mcp\"",
        "\"documentation\": \"http://docs.acme.example/mcp\""), "documentation");
    assertBad(FIXTURE.replace("\"checked\": \"2026-11-11\"", "\"checked\": \"soon\""), "checked");
    assertBad(FIXTURE.replace("\"tool\": \"ask_agent\"", "\"tool\": \"run_code\""), "twice");
    assertBad(FIXTURE.replace("\"match\": {\"npm\": \"fire-tools\", \"bin\": \"fire\", \"args\": [\"mcp\"]}",
        "\"match\": {\"hosts\": [\"mcp.fire.example\"], \"paths\": [\"/mcp\"]}"), "doesn't fit its setup");
    assertBad(FIXTURE.replace("\"hostSuffix\": \"mcp.ocean.example\", \"paths\": [\"/mcp\"]",
        "\"hostSuffix\": \"mcp.ocean.example\", \"paths\": []"), "doesn't fit its setup");
  }

  @Test
  void aHeaderPinNeedsTheSetupToSendItAndNoHeaderCarriesACredential() {
    assertBad(FIXTURE.replace("{\"name\": \"X-Read-Only\", \"text\": \"true\"}, ", ""), "pins X-Read-Only");
    assertBad(FIXTURE.replace("{\"name\": \"X-Read-Only\", \"text\": \"true\"}",
        "{\"name\": \"X-Read-Only\", \"text\": \"yes\"}"), "pins X-Read-Only");
    assertBad(FIXTURE.replace("{\"name\": \"X-Toolsets\", \"text\": \"apps,dns\"}",
        "{\"name\": \"Authorization\", \"text\": \"Bearer x\"}"), "credential");
    assertBad(FIXTURE.replace("\"command\": [\"npx\", \"-y\", \"fire-tools@1.2.3\", \"mcp\"],",
        "\"command\": [\"npx\", \"-y\", \"fire-tools@1.2.3\", \"mcp\"], \"headers\": [{\"name\": \"X-A\", "
        + "\"text\": \"b\"}],"), "a command has no headers");
  }

  @Test
  void twoProfilesMayNotClaimTheSameServer() {
    String twin = FIXTURE.replace("{\"id\": \"fire\"", "{\"id\": \"fire-twin\", \"name\": \"Twin\", \"publisher\": "
        + "\"Fire\", \"category\": \"Cloud platform\", \"description\": \"The same.\", \"documentation\": "
        + "\"https://docs.fire.example/mcp\", \"checked\": \"2026-11-11\", \"command\": [\"fire\", \"mcp\"], "
        + "\"signIn\": \"Same.\", \"match\": {\"bin\": \"fire\", \"args\": [\"mcp\"]}},\n{\"id\": \"fire\"");

    assertBad(twin, "also matches");
  }

  private static void assertBad(String json, String message) {
    assertThatThrownBy(() -> VerifiedProfiles.parse(JSON.readTree(json)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
  }
}
