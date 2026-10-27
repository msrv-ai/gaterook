package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class RunbookCompilerTest {

  private static final String LOGS_PARAMS = """
      [{"name":"service","type":"enum","values":["api","worker"],"required":true},
       {"name":"lines","type":"int","min":1,"max":500,"default":100},
       {"name":"timestamps","type":"bool","whenTrue":"--timestamps","whenFalse":""}]""";

  private static Runbook compile(ObjectNode definition) {
    RunbookCompiler.Outcome o = RunbookCompiler.compile(definition);
    assertThat(o.errors()).isEmpty();
    return o.runbook();
  }

  private static ObjectNode params(String json) {
    return (ObjectNode) TestConfigs.json(json);
  }

  @Test
  void rendersTypedValuesQuotedAndDefaultsApplied() throws Exception {
    Runbook rb = compile(TestConfigs.runbook("compose-logs",
        "docker compose logs --tail {lines} {timestamps} {service}", true, LOGS_PARAMS));

    Map<String, JsonNode> values = rb.values(params("{\"service\":\"api\"}"));

    assertThat(rb.render(values, false, 0)).isEqualTo("docker compose logs --tail '100' 'api'");
    assertThat(rb.render(rb.values(params("{\"service\":\"worker\",\"lines\":5,\"timestamps\":true}")), false, 0))
        .isEqualTo("docker compose logs --tail '5' --timestamps 'worker'");
    assertThat(rb.signature()).isEqualTo("compose-logs(service: api|worker, lines?: 1–500, timestamps?: true|false)");
    assertThat(rb.readOnly()).isTrue();
    assertThat(rb.freeText()).isFalse();
  }

  @Test
  void anAbsentOptionalTakesItsSpaceAlong() throws Exception {
    Runbook rb = compile(TestConfigs.runbook("compose-ps", "docker compose ps {service}", true,
        "[{\"name\":\"service\",\"type\":\"enum\",\"values\":[\"api\"]}]"));

    assertThat(rb.render(rb.values(null), false, 0)).isEqualTo("docker compose ps");
  }

  @Test
  void workdirAndRemoteDeadlineWrapTheLine() throws Exception {
    ObjectNode d = TestConfigs.runbook("disk", "df -h", true, null);
    d.put("workdir", "/srv/it's here");
    d.put("timeoutSeconds", 20);
    Runbook rb = compile(d);

    assertThat(rb.render(rb.values(null), false, 20)).isEqualTo("cd '/srv/it'\"'\"'s here' && df -h");
    assertThat(rb.render(rb.values(null), true, 20))
        .isEqualTo("timeout -k 5 20 sh -c " + ShellQuote.quote("cd '/srv/it'\"'\"'s here' && df -h"));
  }

  @Test
  void valuesAreCheckedAgainstTheirTypes() {
    Runbook rb = compile(TestConfigs.runbook("compose-logs",
        "docker compose logs --tail {lines} {timestamps} {service}", true, LOGS_PARAMS));

    assertThatThrownBy(() -> rb.values(params("{\"service\":\"db\"}"))).hasMessage("service must be one of api, worker");
    assertThatThrownBy(() -> rb.values(params("{\"service\":\"api\",\"lines\":501}")))
        .hasMessage("lines must be an integer 1..500");
    assertThatThrownBy(() -> rb.values(params("{\"service\":\"api\",\"lines\":\"5\"}")))
        .hasMessage("lines must be an integer 1..500");
    assertThatThrownBy(() -> rb.values(params("{\"lines\":5}"))).hasMessageStartingWith("service is required.");
    assertThatThrownBy(() -> rb.values(params("{\"service\":\"api\",\"rm\":true}")))
        .hasMessageStartingWith("rm is not a parameter of compose-logs.");
  }

  @Test
  void stringsNeedAPatternAndRefuseOptionsAndControlCharacters() {
    RunbookCompiler.Outcome noPattern = RunbookCompiler.compile(TestConfigs.runbook("grep-log",
        "grep -F {text} /var/log/app.log", true, "[{\"name\":\"text\",\"type\":\"string\"}]"));
    assertThat(noPattern.errors()).anySatisfy(e -> assertThat(e).contains("a string needs a pattern"));

    Runbook rb = compile(TestConfigs.runbook("grep-log", "grep -F -- {text} /var/log/app.log", true,
        "[{\"name\":\"text\",\"type\":\"string\",\"pattern\":\"[ -~]{1,64}\",\"required\":true}]"));
    assertThat(rb.freeText()).isTrue();
    assertThatThrownBy(() -> rb.values(params("{\"text\":\"--help\"}"))).hasMessageContaining("can't start with -");
    assertThatThrownBy(() -> rb.values(params("{\"text\":\"a\\nb\"}"))).hasMessageContaining("control");
    assertThatThrownBy(() -> rb.values(params("{\"text\":\"a\\u200bb\"}"))).hasMessageContaining("invisible");
    assertThatThrownBy(() -> rb.values(params("{\"text\":\"üml\"}"))).hasMessage("text must match [ -~]{1,64}");
  }

  @Test
  void readOnlyCountsOnlyForTheConfirmedCommand() {
    ObjectNode d = TestConfigs.runbook("restart-api", "docker compose restart api", true, null);
    d.put("readOnlyConfirmedFor", "docker compose ps");

    RunbookCompiler.Outcome o = RunbookCompiler.compile(d);

    assertThat(o.ok()).isTrue();
    assertThat(o.runbook().readOnly()).isFalse();
    assertThat(o.warnings()).anySatisfy(w -> assertThat(w).contains("command changed since it was confirmed"));
    assertThat(o.warnings()).anySatisfy(w -> assertThat(w).contains("restart"));

    ObjectNode imported = TestConfigs.runbook("ps", "docker compose ps", false, null);
    imported.put("readOnly", true);
    assertThat(RunbookCompiler.compile(imported).runbook().readOnly()).isFalse();
  }

  @Test
  void theContributionKeepsWhatWasCompiled() {
    Runbook rb = compile(TestConfigs.runbook("compose-logs",
        "docker compose logs --tail {lines} {timestamps} {service}", true, LOGS_PARAMS));

    RunbookCompiler.Outcome back = RunbookCompiler.fromContribution(rb.toJson());

    assertThat(back.errors()).isEmpty();
    assertThat(back.runbook().readOnly()).isTrue();
    assertThat(back.runbook().toJson()).isEqualTo(rb.toJson());
  }

  @Test
  void refusesWhatCanLeaveItsQuotes() {
    assertThat(RunbookCompiler.compile(TestConfigs.runbook("grep-log", "grep '{text}' /var/log/app.log", true,
        "[{\"name\":\"text\",\"type\":\"string\",\"pattern\":\"[a-z]+\"}]")).errors())
        .anySatisfy(e -> assertThat(e).contains("inside '…'"));
    assertThat(RunbookCompiler.compile(TestConfigs.runbook("unused", "uptime", true,
        "[{\"name\":\"lines\",\"type\":\"int\",\"min\":1,\"max\":5}]")).errors())
        .anySatisfy(e -> assertThat(e).contains("doesn't use {lines}"));
    assertThat(RunbookCompiler.compile(TestConfigs.runbook("Bad_Name", "uptime", true, null)).errors())
        .anySatisfy(e -> assertThat(e).startsWith("name:"));
    ObjectNode twoLines = TestConfigs.runbook("two", "uptime\nid", true, null);
    assertThat(RunbookCompiler.compile(twoLines).errors()).anySatisfy(e -> assertThat(e).contains("one line"));
    ObjectNode badBool = TestConfigs.runbook("bools", "ls {all}", true,
        "[{\"name\":\"all\",\"type\":\"bool\",\"whenTrue\":\"-a;id\",\"whenFalse\":\"\"}]");
    assertThat(RunbookCompiler.compile(badBool).errors()).anySatisfy(e -> assertThat(e).contains("literals"));
    ObjectNode unknown = TestConfigs.runbook("x1", "uptime", true, null);
    unknown.set("shell", JsonNodeFactory.instance.stringNode("bash"));
    assertThat(RunbookCompiler.compile(unknown).errors()).contains("shell is not a field of a runbook");
  }

  @Test
  void warnsAboutCommandsThatNeverEndAndDanglingOptions() {
    RunbookCompiler.Outcome o = RunbookCompiler.compile(TestConfigs.runbook("follow",
        "journalctl -f --lines {lines}", true, "[{\"name\":\"lines\",\"type\":\"int\",\"min\":1,\"max\":5}]"));

    assertThat(o.warnings()).anySatisfy(w -> assertThat(w).startsWith("-f never ends"));
    assertThat(o.warnings()).anySatisfy(w -> assertThat(w).startsWith("lines is optional without a default"));
  }
}
