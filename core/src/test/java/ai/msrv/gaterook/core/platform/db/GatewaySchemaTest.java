package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.TestGateway;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.engine.spi.ManagedEntity;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The schema gate of ARCHITECTURE.md §4 and M1.2: Flyway on an empty database, then {@code ddl-auto=validate} (the
 * context starts only if every entity matches the migrations), every table and trigger of V1 present, every entity
 * enhanced at build time (report 15 rule 13: on the JVM a missing enhancement is masked by ByteBuddy, in native it
 * fails).
 */
@SpringBootTest
class GatewaySchemaTest {

  /** The 33 tables of V1 (ARCHITECTURE.md §4), one entity each. */
  static final Set<String> TABLES = Set.of("setting", "policy", "external_store", "secret", "secret_version",
      "secret_lease", "master_key_state", "plugin", "connector_type", "connection", "mcp_server", "template",
      "template_blob", "template_version", "endpoint", "endpoint_connection", "endpoint_tool", "client_grant",
      "api_token", "client_link", "oauth_client", "oauth_grant", "oauth_token", "approval", "approval_event",
      "tool_call", "setup_change", "trace", "audit_export", "notification_channel", "notify_link",
      "notify_delivery", "notify_pairing");

  @DynamicPropertySource
  static void gateway(DynamicPropertyRegistry registry) {
    TestGateway.register(registry, "schema");
    // application.yml says so already; pinned here so that this gate can't be switched off with it.
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  JdbcTemplate jdbc;

  /** The core has no JdbcTemplate bean (JPA only, decision 3): the test makes its own over the primary pool. */
  @Autowired
  void jdbc(javax.sql.DataSource dataSource) {
    jdbc = new JdbcTemplate(dataSource);
  }

  @Autowired
  EntityManagerFactory entityManagerFactory;

  @Autowired
  ParallelSchemaValidation schemaValidation;

  /** The context started, so the mapping matched: the check ran beside the persistence unit's build and passed. */
  @Test
  void theMappingIsValidatedBesideTheBuildOfThePersistenceUnit() {
    assertThat(schemaValidation.passed()).isTrue();
    assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
        .as("the build itself doesn't validate").isEqualTo("none");
  }

  @Test
  void flywayCreatesEveryTableOfV1AndAppliesTheM1Migrations() {
    List<String> tables = jdbc.queryForList("select name from sqlite_master where type = 'table' "
        + "and name not like 'sqlite_%' and name <> 'flyway_schema_history'", String.class);

    assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    assertThat(jdbc.queryForList("select name from sqlite_master where type = 'view'", String.class))
        .containsExactly("runbook");
    // V2 and V3 (M1 range V1–V9): the template row's MAC, the audit's index of approval requests. Later steps add
    // migrations in their own ranges (§4), so the M1 ones are checked by number, not as the latest.
    assertThat(jdbc.queryForList("select version from flyway_schema_history where success = 1", String.class))
        .contains("1", "2", "3");
    // V30 (notifications, K2): the MACs of linked accounts and of the channels' settings.
    assertThat(jdbc.queryForList("select name from pragma_table_info('notify_link')", String.class)).contains("mac");
    assertThat(jdbc.queryForList("select name from pragma_table_info('notification_channel')", String.class))
        .contains("mac");
    assertThat(jdbc.queryForList("select name from pragma_table_info('template')", String.class)).contains("mac");
    assertThat(jdbc.queryForList("select name from sqlite_master where type = 'index' and tbl_name = 'tool_call'",
        String.class)).contains("tool_call_approval");
  }

  @Test
  void auditAndTemplateTriggersExist() {
    assertThat(jdbc.queryForList("select name from sqlite_master where type = 'trigger'", String.class))
        .containsExactlyInAnyOrder("tool_call_no_update", "tool_call_no_early_delete", "setup_change_no_update",
            "setup_change_no_early_delete", "template_version_no_update");
  }

  @Test
  void everyTableHasExactlyOneEnhancedEntity() {
    MappingMetamodel metamodel = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
    Set<String> mapped = metamodel.streamEntityDescriptors()
        .map(descriptor -> ((org.hibernate.persister.entity.AbstractEntityPersister) descriptor).getTableName())
        .collect(Collectors.toSet());

    assertThat(mapped).containsExactlyInAnyOrderElementsOf(TABLES);
    metamodel.forEachEntityDescriptor(descriptor -> assertThat(ManagedEntity.class)
        .as(descriptor.getEntityName() + " is enhanced at build time")
        .isAssignableFrom(descriptor.getMappedClass()));
  }
}
