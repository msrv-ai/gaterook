package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Steps own migration ranges (ARCHITECTURE.md §4) and merge in any order: a home made after K5 holds V80 but not the
 * V4 of the API step or the V50 of the K4 review, which arrived later. Flyway's default calls them ignored and refuses
 * to migrate; the core's {@code spring.flyway.out-of-order} applies them (2026-10-27: such a home didn't start).
 */
class MigrationsOutOfOrderTest {

  @TempDir
  Path dir;

  @Test
  void aHomeWithALaterRangeTakesTheNewMigrationsOfEarlierRanges() throws Exception {
    String url = olderHome();
    MigrateResult result = flyway(url, outOfOrder()).migrate();

    assertThat(result.migrations).extracting(m -> m.version).containsExactly("4", "50");
    assertThat(flyway(url, true).info().pending()).isEmpty();
  }

  @Test
  void flywaysDefaultWouldNotStartThatHome() throws Exception {
    String url = olderHome();
    assertThatThrownBy(() -> flyway(url, false).migrate()).isInstanceOf(FlywayException.class)
        .hasMessageContaining("not applied");
  }

  /** A database migrated by every migration but V4 and V50, as a home made on 2026-10-27 before they came. */
  private String olderHome() throws Exception {
    Path all = Path.of(getClass().getResource("/db/migration").toURI());
    Path older = Files.createDirectories(dir.resolve("older"));
    List<Path> files;
    try (var list = Files.list(all)) {
      files = list.toList();
    }
    for (Path f : files) {
      String name = f.getFileName().toString();
      if (!name.startsWith("V4__") && !name.startsWith("V50__")) {
        Files.copy(f, older.resolve(name));
      }
    }
    String url = "jdbc:sqlite:" + dir.resolve("gateway.db");
    Flyway.configure().dataSource(url, null, null).locations("filesystem:" + older).load().migrate();
    return url;
  }

  private static Flyway flyway(String url, boolean outOfOrder) {
    return Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").outOfOrder(outOfOrder)
        .load();
  }

  @SuppressWarnings("unchecked")
  private boolean outOfOrder() {
    Map<String, Object> yml = new Yaml().load(getClass().getResourceAsStream("/application.yml"));
    Map<String, Object> spring = (Map<String, Object>) yml.get("spring");
    return Boolean.TRUE.equals(((Map<String, Object>) spring.get("flyway")).get("out-of-order"));
  }
}
