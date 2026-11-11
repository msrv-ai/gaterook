package ai.msrv.gaterook.core.admin.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The demo's past results are as big as the whole results their previews sum up: traffic out reads like real use. */
class DemoHistoryTest {

  @Test
  void aResultIsAsBigAsWhatItsPreviewSumsUp() {
    assertThat(DemoHistory.resultSize("sql_query", "12 rows")).isEqualTo(180 + 12 * 96);
    assertThat(DemoHistory.resultSize("logs_search", "0 lines")).isEqualTo(120);
    assertThat(DemoHistory.resultSize("k8s_get", "3 pods")).isEqualTo(240 + 3 * 900);
    assertThat(DemoHistory.resultSize("mongo_find", "5 documents")).isEqualTo(160 + 5 * 420);
    assertThat(DemoHistory.resultSize("http_request", "200 OK · 5.3 KB")).isEqualTo(5_427);
    assertThat(DemoHistory.resultSize("ssh_run", "api, worker, order-sync: running")).isEqualTo(1_400);
    assertThat(DemoHistory.resultSize("ssh_run", "done")).isEqualTo(260);
    assertThat(DemoHistory.resultSize("redis_get", "hit")).isEqualTo(180);
    assertThat(DemoHistory.resultSize("sql_execute", "4 rows")).as("a count of changed rows").isEqualTo(6);
  }
}
