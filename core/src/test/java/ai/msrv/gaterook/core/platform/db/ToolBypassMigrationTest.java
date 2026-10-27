package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ToolBypassMigrationTest {

  @TempDir
  Path dir;

  @Test
  void existingModesSignaturesAndConstraintsSurviveTheBypassMigration() {
    String url = "jdbc:sqlite:" + dir.resolve("gateway.db");
    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("80").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
    jdbc.update("insert into endpoint (id, slug, env, access, settings, published, oauth_enabled, created_at, "
        + "updated_at, version) values ('ep', 'sample', 'dev', 'standard', '{}', 0, 0, 1, 1, 0)");
    jdbc.update("insert into endpoint_tool (id, endpoint_id, tool_key, mode, updated_at, mac, version) "
        + "values ('tool', 'ep', 'list_connections', 'READ_ONLY', 7, X'123456', 3)");
    var before = jdbc.queryForMap("select * from endpoint_tool where id = 'tool'");
    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();
    var after = jdbc.queryForMap("select * from endpoint_tool where id = 'tool'");
    assertThat((byte[]) after.remove("mac")).containsExactly((byte[]) before.remove("mac"));
    assertThat(after).isEqualTo(before);
    assertThat(jdbc.update("update endpoint_tool set mode = 'BYPASS' where id = 'tool'")).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update("update endpoint_tool set mode = 'UNKNOWN' where id = 'tool'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("insert into endpoint_tool (id, endpoint_id, tool_key, mode, "
        + "updated_at, version) values ('other', 'ep', 'list_connections', 'BYPASS', 7, 0)"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(jdbc.queryForList("pragma foreign_key_check")).isEmpty();
  }
}
