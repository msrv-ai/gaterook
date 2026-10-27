package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.msrv.gaterook.core.platform.DiskFullException;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A full disk (2026-10-25): SQLite rolls the transaction back by itself, and the pooled writer used to stay out of step
 * with it, failing every write until a restart ({@link LostTransactionEviction}). {@code PRAGMA max_page_count} on the
 * writer's connection stands in for the disk: a write that needs a new page fails with {@code SQLITE_FULL}. The limit
 * belongs to the connection, so a writer that replaced a broken one has room again unless the test fills it too.
 */
@SpringBootTest
class DiskFullRecoveryTest {

  /** Far more than the free space of the database's last pages: the write needs new pages. */
  private static final String LARGE = "x".repeat(60 * 1024);

  @DynamicPropertySource
  static void gateway(DynamicPropertyRegistry registry) {
    TestGateway.register(registry, "disk-full");
    registry.add("gateway.backup.catch-up-delay", () -> "1h");
  }

  @Autowired SqlitePools pools;
  @Autowired PlatformTransactionManager transactions;
  @Autowired SettingStore settings;
  @Autowired SecretService secrets;
  @Autowired JsonMapper json;
  @Autowired WebApplicationContext context;

  @Test
  void writesWorkAgainOnceTheDiskHasSpaceWithoutARestart() throws SQLException {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    fillTheDisk();
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> store("recovery.full", LARGE)))
        .isInstanceOf(DataAccessException.class);

    freeTheDisk();
    tx.executeWithoutResult(s -> store("recovery.after", "saved"));
    assertThat(stored("recovery.after")).isEqualTo("saved");

    // A transaction is one again: on the stale connection each statement committed on its own, before the failure.
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      store("recovery.abandoned", "never");
      throw new IllegalStateException("the caller gives up");
    })).hasMessage("the caller gives up");
    assertThat(settings.find("recovery.abandoned")).isEmpty();
  }

  @Test
  void everyWriteOnAFullDiskSaysSoAndReadsGoOn() throws SQLException {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(s -> store("full.before", "kept"));
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        // The disk stays full: the writer that replaced the broken one meets it too.
        fillTheDisk();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store("full.during", LARGE)))
            .isInstanceOf(DiskFullException.class);
      }
      assertThat(stored("full.before")).as("read on the reader pool").isEqualTo("kept");
      assertThat(settings.find("full.during")).isEmpty();
    } finally {
      freeTheDisk();
    }
  }

  @Test
  void theAdminApiAnswers507WhileTheDiskIsFullAndSavesOnceItHasSpace() throws Exception {
    MockMvc api = MockMvcBuilders.webAppContextSetup(context).build();
    SecretInfo secret = secrets.createLocal(SecretOwner.connection("disk-full"), "/credentials/token",
        "disk-full/token", "first".toCharArray());
    String replace = "{\"value\":\"" + LARGE + "\"}";

    fillTheDisk();
    try {
      api.perform(put("/api/secrets/" + secret.id() + "/value").contentType(MediaType.APPLICATION_JSON)
              .content(replace))
          .andExpect(status().isInsufficientStorage())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.title").value("The disk is full"));
    } finally {
      freeTheDisk();
    }
    api.perform(put("/api/secrets/" + secret.id() + "/value").contentType(MediaType.APPLICATION_JSON)
            .content(replace))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

  private void store(String key, String value) {
    settings.put(key, json.getNodeFactory().stringNode(value), null);
  }

  private String stored(String key) {
    return settings.find(key).orElseThrow().value().asString();
  }

  /** The writer's connection may grow no more: the next write that needs a new page fails with SQLITE_FULL. */
  private void fillTheDisk() throws SQLException {
    try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
      long pages = single(s, "PRAGMA page_count");
      assertThat(single(s, "PRAGMA max_page_count = " + pages)).isEqualTo(pages);
    }
  }

  /** SQLite's own ceiling again (it caps the value). */
  private void freeTheDisk() throws SQLException {
    try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
      single(s, "PRAGMA max_page_count = 4294967294");
    }
  }

  private static long single(Statement statement, String sql) throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
