package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class SshLogFileQueryTest {
  @Test
  void queryLengthCountsUnicodeCharactersLikeThePublishedSchema() throws Exception {
    String query = "\uD83D\uDE00".repeat(1024);
    var args = JsonNodeFactory.instance.objectNode().put("query", query);
    assertThat(SshLogFileQuery.parse(args).matches(query + ".log")).isTrue();
    args.put("query", query + "x");
    assertThatThrownBy(() -> SshLogFileQuery.parse(args)).isInstanceOf(ConnectorException.class);
  }
}
