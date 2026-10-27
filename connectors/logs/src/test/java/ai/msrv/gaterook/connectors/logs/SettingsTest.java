package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a Logs connection may name (report 07 §4, §6): addresses a person typed, checked before any resolution
 * (ARCHITECTURE.md §9 "SSRF"), and the projects, log groups and indices an agent may narrow to but never leave.
 */
class SettingsTest {

  @ParameterizedTest
  @ValueSource(strings = {"http://169.254.169.254:3100", "http://metadata.google.internal", "http://[fd00:ec2::254]",
      "http://0177.0.0.1:3100", "http://2130706433:3100", "http://user:pass@loki.dev.internal:3100",
      "ftp://loki.dev.internal", "http://loki.dev.internal:3100/?q=1", "http://loki.dev.internal:3100/#x"})
  void refusesAddressesThatAreNotAPlainHost(String url) {
    assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, "{\"backend\":\"loki\",\"url\":\"" + url + "\"}")))
        .isInstanceOf(Settings.InvalidSettingsException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://loki.dev.internal:3100", "http://10.30.4.12:3100", "http://127.0.0.1:3100/",
      "https://logs-prod-012.grafana.net/loki-path", "http://[::1]:3100"})
  void privateAndPublicHostsAreFine(String url) {
    Settings.Loki s = (Settings.Loki) Settings.parse(Configs.of("x", Env.DEV,
        "{\"backend\":\"loki\",\"url\":\"" + url + "\"}"));
    assertThat(s.base().toString()).doesNotEndWith("/");
    assertThat(s.auth().mode()).isEqualTo(Settings.AuthMode.BEARER);
    assertThat(s.auth().secretField()).isEqualTo("/auth/secret");
  }

  @Test
  void openSearchReadsOnlyNamedIndexPatterns() {
    for (String bad : new String[] {"*", "logs-*,secrets", "remote:logs-*", "-logs", "_all", "Logs"}) {
      assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, """
          {"backend":"opensearch","url":"https://search:9200","indices":["%s"]}""".formatted(bad))))
          .as(bad).isInstanceOf(Settings.InvalidSettingsException.class);
    }
    assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"opensearch","url":"https://search:9200/prefix","indices":["logs-*"]}""")))
        .hasMessageContaining("address only");
    assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"opensearch","url":"https://search:9200","indices":["logs-app-*"]}""")))
        .as("Basic is the form's default").hasMessage("Basic sign-in needs a username.");
    Settings.OpenSearch s = (Settings.OpenSearch) Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"opensearch","url":"https://search:9200","indices":["logs-app-*"],
         "auth":{"username":"gw_reader"}}"""));
    assertThat(s.labelFields()).contains("service.name");
    assertThat(s.auth().mode()).isEqualTo(Settings.AuthMode.BASIC);
    assertThat(s.fullApi()).as("Existing connections remain scoped").isFalse();
  }

  @Test
  void fullOpenSearchApiAccessIsExplicitAndCanOmitLogIndices() {
    Settings.OpenSearch full = (Settings.OpenSearch) Settings.parse(Configs.of("search", Env.DEV, """
        {"backend":"opensearch","url":"https://search:9200","apiAccess":"full","auth":{"mode":"none"}}"""));
    assertThat(full.fullApi()).isTrue();
    assertThat(full.indices()).containsExactly("*");
    Settings.OpenSearch scopedLogs = (Settings.OpenSearch) Settings.parse(Configs.of("search", Env.DEV, """
        {"backend":"opensearch","url":"https://search:9200","apiAccess":"full","indices":["logs-*"],
         "scopeQuery":"service.name:api","auth":{"mode":"none"}}"""));
    assertThat(scopedLogs.indices()).containsExactly("logs-*");
    assertThat(scopedLogs.scopeQuery()).isEqualTo("service.name:api");
    for (String mode : new String[] {"logs", "unknown"}) {
      assertThatThrownBy(() -> Settings.parse(Configs.of("search", Env.DEV, """
          {"backend":"opensearch","url":"https://search:9200","apiAccess":"%s","auth":{"mode":"none"}}"""
          .formatted(mode)))).isInstanceOf(Settings.InvalidSettingsException.class);
    }
    assertThatThrownBy(() -> Settings.parse(Configs.of("search", Env.DEV, """
        {"backend":"opensearch","url":"https://search:9200","apiAccess":"full","indices":[],
         "auth":{"mode":"none"}}"""))).isInstanceOf(Settings.InvalidSettingsException.class);
  }

  @Test
  void cloudSettingsNameWhatToFix() {
    assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"gcp-logging","resourceNames":["acme-prod"],"credentials":{"source":"adc"}}""")))
        .hasMessageContaining("isn't a project");
    assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"cloudwatch","region":"mars-1","logGroups":["/a"],"credentials":{"source":"profile",
         "profile":"p"}}"""))).hasMessageContaining("region");
    assertThatThrownBy(() -> Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/a"],"credentials":{"source":"accessKey",
         "accessKeyId":"nope"}}"""))).hasMessageContaining("access key ID");
    Settings.CloudWatch cw = (Settings.CloudWatch) Settings.parse(Configs.of("x", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["arn:aws:logs:eu-central-1:123456789012:log-group:/app:*"],
         "credentials":{"source":"profile","profile":"ops","roleArn":"arn:aws:iam::210987654321:role/gw"}}"""));
    assertThat(cw.account()).as("the role's account owns the quota").isEqualTo("210987654321");
    assertThat(cw.insights()).isTrue();
    Settings.Gcp gcp = (Settings.Gcp) Settings.parse(Configs.gcp());
    assertThat(gcp.quotaKey()).isEqualTo("gcp:projects/acme-prod");
    assertThat(gcp.quotaPerMinute()).isEqualTo(30);
  }

  @Test
  void severitiesOfEveryBackendMapOntoOneScale() {
    assertThat(Severity.of("warn")).isEqualTo(Severity.WARNING);
    assertThat(Severity.of("E")).isEqualTo(Severity.ERROR);
    assertThat(Severity.of("fatal")).isEqualTo(Severity.CRITICAL);
    assertThat(Severity.of("50")).isEqualTo(Severity.ERROR);
    assertThat(Severity.of("NOTICE")).isEqualTo(Severity.NOTICE);
    assertThat(Severity.of("unknown")).isEqualTo(Severity.DEFAULT);
    assertThat(Severity.of(null)).isEqualTo(Severity.DEFAULT);
    assertThat(Severity.ofLine("2026-10-22 12:00:00,123 [main] ERROR c.a.Payment - failed")).isEqualTo(Severity.ERROR);
    assertThat(Severity.ofLine("[WARN] disk 91%")).isEqualTo(Severity.WARNING);
    assertThat(Severity.ofLine("connected, no error so far")).as("a word in prose isn't a level")
        .isEqualTo(Severity.DEFAULT);
    assertThat(CloudWatchBackend.severity("{\"level\":\"debug\",\"msg\":\"x\"}")).isEqualTo(Severity.DEBUG);
    assertThat(CloudWatchBackend.severity("{\"log\":{\"level\":\"error\"}}")).isEqualTo(Severity.ERROR);
  }
}
