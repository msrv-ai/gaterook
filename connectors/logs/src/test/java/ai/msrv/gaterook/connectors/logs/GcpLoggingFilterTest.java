package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GcpLoggingFilterTest {

  @Test
  void acceptsExplorerCommentsAndPreservesDoubleDashesInsideQuotedValues() {
    assertThat(GcpLoggingFilter.expression("""
        -- Container output
        resource.type="k8s_container"
        (textPayload:"--dry-run" OR jsonPayload.message =~ "warn--.*") -- trailing comment
        """))
        .isEqualTo("resource.type=\"k8s_container\"\n"
            + "(textPayload:\"--dry-run\" OR jsonPayload.message =~ \"warn--.*\")");
    assertThat(GcpLoggingFilter.expression("-- first\r\nseverity>=ERROR -- last")).isEqualTo("severity>=ERROR");
    assertThat(GcpLoggingFilter.expression("-- only a comment")).isEmpty();
  }

  @Test
  void preservesEscapedQuotesBackslashesAndParenthesesInsideStrings() {
    String query = "textPayload:\"quoted \\\"--still text\\\" (value)\" AND jsonPayload.path=\"C:\\\\\"";
    assertThat(GcpLoggingFilter.expression(query + " -- outside")).isEqualTo(query);
    assertThat(GcpLoggingFilter.expression("labels.\"app--name\"=\"api\" AND log_id(\"stdout\")"))
        .isEqualTo("labels.\"app--name\"=\"api\" AND log_id(\"stdout\")");
  }

  @Test
  void rejectsExpressionBoundariesThatCouldEscapeTheGatewayClauses() {
    for (String query : List.of("severity>=ERROR) AND timestamp>=\"2015-01-01T00:00:00Z\" --",
        "(severity>=ERROR", "textPayload:\"unfinished -- text", "textPayload:\"unfinished\\\"")) {
      assertThatThrownBy(() -> GcpLoggingFilter.expression(query)).as(query)
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cloud Logging filter");
    }
  }
}
