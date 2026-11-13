package com.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V92 rebuilds {@code external_store} to accept {@code azure-kv}: on a database that has stores, references bound to
 * them, a store's own sign-in secret and a Vault lease, opened with foreign keys enforced as the core opens it
 * ({@link SqlitePools#url}).
 */
class AzureKeyVaultStoreMigrationTest {

  @TempDir
  Path dir;

  @Test
  void existingStoresKeepTheirRowsAndReferencesAndTheNewKindIsAccepted() {
    String url = "jdbc:sqlite:" + dir.resolve("gateway.db") + "?foreign_keys=true";
    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("91").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
    assertThat(jdbc.queryForObject("pragma foreign_keys", Integer.class)).isEqualTo(1);

    jdbc.update("insert into external_store (id, kind, name, config, auth_secret_id, state, last_checked_at, "
        + "created_at, version) values ('st-vault', 'vault', 'vault', '{\"address\":\"https://vault.example.test\","
        + "\"auth\":{\"method\":\"approle\",\"roleId\":\"gaterook\"}}', 'sec-sign-in', 'connected', 1760000000000, "
        + "1750000000000, 4)");
    jdbc.update("insert into external_store (id, kind, name, config, auth_secret_id, state, last_checked_at, "
        + "created_at, version) values ('st-gcp', 'gcp-sm', 'gcp-acme-stage', '{\"project\":\"acme-stage\"}', null, "
        + "'not-checked', null, 1750000000001, 0)");
    jdbc.update("insert into external_store (id, kind, name, config, auth_secret_id, state, last_checked_at, "
        + "created_at, version) values ('st-aws', 'aws-sm', 'aws-eu-central-1', '{\"region\":\"eu-central-1\"}', "
        + "null, 'failing', 1760000000002, 1750000000002, 2)");
    secret(jdbc, "sec-sign-in", "vault/approle-secret-id", "local", "store", "st-vault", "/auth/secretId", null, null);
    secret(jdbc, "sec-ref", "stage-db/password", "reference", "connection", "conn-1", "/credentials/password",
        "vault://kv/stage/db#password", "st-vault");
    secret(jdbc, "sec-gcp", "billing-api/token", "reference", "connection", "conn-2", "/auth/token",
        "gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest", "st-gcp");
    secret(jdbc, "sec-dynamic", "prod-replica/password", "dynamic", "connection", "conn-3", "/credentials/password",
        "vault://database/creds/prod-replica-readonly", "st-vault");
    secret(jdbc, "sec-unbound", "orders/password", "reference", "connection", "conn-4", "/credentials/password",
        "aws-sm://us-east-1/orders#password", null);
    jdbc.update("insert into secret_lease (lease_id, store_id, secret_id, issued_at, expires_at) values "
        + "('database/creds/prod-replica-readonly/lease1', 'st-vault', 'sec-dynamic', 1760000000000, 1760000900000)");
    List<Map<String, Object>> stores = jdbc.queryForList("select * from external_store order by id");
    List<Map<String, Object>> secrets = jdbc.queryForList("select * from secret order by id");
    List<Map<String, Object>> leases = jdbc.queryForList("select * from secret_lease order by lease_id");

    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();

    assertThat(jdbc.queryForList("select version from flyway_schema_history where success = 1", String.class))
        .contains("92");
    assertThat(jdbc.queryForList("select * from external_store order by id")).isEqualTo(stores);
    assertThat(jdbc.queryForList("select * from secret order by id")).isEqualTo(secrets);
    assertThat(jdbc.queryForList("select * from secret_lease order by lease_id")).isEqualTo(leases);
    assertThat(jdbc.queryForList("pragma foreign_key_check")).isEmpty();
    assertThat(jdbc.queryForList("select name from sqlite_master where type = 'table' and name like 'external%'",
        String.class)).containsExactly("external_store");
    // The references still name their table: the rebuild left their foreign keys as they were.
    assertThat(jdbc.queryForList("select \"table\" from pragma_foreign_key_list('secret')", String.class))
        .containsExactly("external_store");
    assertThat(jdbc.queryForList("select \"table\" from pragma_foreign_key_list('secret_lease')", String.class))
        .contains("external_store", "secret");

    jdbc.update("insert into external_store (id, kind, name, config, state, created_at, version) values "
        + "('st-azure', 'azure-kv', 'azure-acme-kv', '{\"vault\":\"acme-kv\"}', 'not-checked', 1770000000000, 0)");
    secret(jdbc, "sec-azure", "billing-db/password", "reference", "connection", "conn-5", "/credentials/password",
        "azure-kv://acme-kv/billing-db#password", "st-azure");
    assertThatThrownBy(() -> jdbc.update("insert into external_store (id, kind, name, config, state, created_at, "
        + "version) values ('st-other', 'azure-xx', 'other', '{}', 'not-checked', 1, 0)"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("insert into external_store (id, kind, name, config, state, created_at, "
        + "version) values ('st-twin', 'vault', 'vault', '{}', 'not-checked', 1, 0)"))
        .as("names stay unique").isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> secret(jdbc, "sec-dangling", "x/password", "reference", "connection", "conn-6",
        "/credentials/password", "azure-kv://other-kv/x", "st-missing"))
        .as("a reference still needs an existing store").isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("delete from external_store where id = 'st-vault'"))
        .as("a store with references can't be deleted under them").isInstanceOf(DataAccessException.class);
    assertThat(jdbc.queryForList("pragma foreign_key_check")).isEmpty();
  }

  private static void secret(JdbcTemplate jdbc, String id, String name, String kind, String ownerKind, String ownerId,
      String field, String ref, String store) {
    jdbc.update("insert into secret (id, name, kind, owner_kind, owner_id, field, ref_uri, store_id, created_at, "
        + "version) values (?, ?, ?, ?, ?, ?, ?, ?, 1750000000000, 0)", id, name, kind, ownerKind, ownerId, field, ref,
        store);
  }
}
