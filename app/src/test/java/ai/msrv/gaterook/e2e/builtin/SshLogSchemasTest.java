package ai.msrv.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.ssh.SshLogsConnector;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The published MCP contracts accept log-query recipes and reject ambiguous or unbounded requests. */
class SshLogSchemasTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaResources RESOURCES = SchemaResources.of(SshLogsConnector.class, "ssh-logs");

  private static Schema schema(String name) {
    return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(RESOURCES.load(name));
  }

  @Test
  void supportsLastNRecordsAndQueriesByPeriodLevelTextOrRegex() {
    assertThat(schema("ssh_logs_read.input").validate(JSON.readTree("""
        {"connection":"server-logs","file":"app.log","tail":true,"maxLines":100}
        """))).isEmpty();
    for (String request : new String[] {
        """
        {"connection":"server-logs","from":"-1h","to":"now"}""",
        """
        {"connection":"server-logs","files":["app.log","worker.log"],"query":"timeout|refused",
         "matchMode":"regex","caseSensitive":false,"levels":["ERROR","WARN"],
         "from":"2026-10-28T10:00:00+01:00","to":"2026-10-28T11:00:00+01:00","maxLines":100}
        """,
        """
        {"connection":"server-logs","file":"app.log","query":"","tail":true}""",
        """
        {"connection":"server-logs","query":"ERROR","cursor":"lc1.opaque-cursor"}"""}) {
      assertThat(schema("ssh_logs_search.input").validate(JSON.readTree(request))).as(request).isEmpty();
    }
  }

  @Test
  void rejectsAmbiguousFileSelectionAndInvalidLimits() {
    for (String request : new String[] {
        """
        {"connection":"server-logs","file":"app.log","files":["worker.log"]}""",
        """
        {"connection":"server-logs","files":[]}""",
        """
        {"connection":"server-logs","files":["app.log","app.log"]}""",
        """
        {"connection":"server-logs","scanBytes":67108865}""",
        """
        {"connection":"server-logs","scanBytes":0}""",
        """
        {"connection":"server-logs","maxLines":1001}""",
        """
        {"connection":"server-logs","matchMode":"shell"}""",
        """
        {"connection":"server-logs","caseSensitive":"false"}"""}) {
      assertThat(schema("ssh_logs_search.input").validate(JSON.readTree(request))).as(request).isNotEmpty();
    }
    assertThat(schema("ssh_logs_read.input").validate(JSON.readTree("""
        {"connection":"server-logs","tail":true}
        """))).isNotEmpty();
  }

  @Test
  void supportsTextAndJsonTimestampConfigurationWithoutChangingFilePermissions() {
    for (String parsing : new String[] {
        """
        "format":"auto","timezone":"UTC"
        """,
        """
        "format":"text","timezone":"Europe/Madrid","timestampPattern":"uuuu-MM-dd HH:mm:ss,SSS"
        """,
        """
        "format":"jsonl","timestampField":"event.time","levelField":"/log/level","timestampUnit":"seconds"
        """}) {
      assertThat(schema("config").validate(JSON.readTree("""
          {"connection":{"host":"logs.example.test","username":"logreader"},
           "credentials":{"method":"password","password":"fictional-test-password"},
           "logs":{"root":"/var/log","allowedFiles":["app.log"],%s}}
          """.formatted(parsing)))).as(parsing).isEmpty();
    }
  }
}
