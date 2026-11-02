package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConnectionEnabledMigrationTest {

  @TempDir Path dir;

  @Test
  void existingConnectionsStayEnabledWithoutChangingSignedFields() {
    String url = "jdbc:sqlite:" + dir.resolve("gateway.db");
    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("84").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
    jdbc.update("insert into connection (id, name, type, env, config, created_at, updated_at, mac, version) "
        + "values ('conn', 'sample', 'postgresql', 'dev', '{}', 1, 2, X'123456', 7)");
    var before = jdbc.queryForMap("select * from connection where id = 'conn'");
    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();
    var after = jdbc.queryForMap("select * from connection where id = 'conn'");
    assertThat(after.remove("enabled")).isEqualTo(1);
    assertThat((byte[]) after.remove("mac")).containsExactly((byte[]) before.remove("mac"));
    assertThat(after).isEqualTo(before);
  }
}
