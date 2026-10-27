package ai.msrv.gaterook.core.platform.db;

import ai.msrv.gaterook.core.platform.BeforeMigrations;
import ai.msrv.gaterook.core.platform.GatewayDatabase;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.StartupFacts;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.cfg.MappingSettings;
import org.hibernate.cfg.PersistenceSettings;
import org.hibernate.cfg.ValidationSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.config.PropertiesBasedNamedQueriesFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway database (decision 3, A10): Spring Data JPA + Hibernate on SQLite, schema by Flyway with the mapping
 * checked against it at every start ({@code ddl-auto=validate}, beside the build: {@link ParallelSchemaValidation}),
 * the dialect resolved from JDBC metadata (never named: report 15 §9 rule 15).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayDatabaseProperties.class)
class GatewayDataSourceConfig {

  @Bean(destroyMethod = "close")
  GatewayPaths gatewayPaths(@Value("${gateway.home}") Path home) {
    return GatewayPaths.open(home);
  }

  @Bean(destroyMethod = "close")
  SqlitePools sqlitePools(GatewayPaths paths, GatewayDatabaseProperties properties) {
    return SqlitePools.open(paths.database(), properties);
  }

  @Bean
  @Primary
  DataSource dataSource(SqlitePools pools) {
    return pools.dataSource();
  }

  /** The database as a file: health facts and consistent copies (Settings › Backup). */
  @Bean
  GatewayDatabase gatewayDatabase(GatewayPaths paths, SqlitePools pools) {
    return new GatewayDatabase(paths.database(), pools.url(), pools.reader().orElse(pools.writer()));
  }

  /** Backup before a schema change, progress lines around it, failures with a reason Electron understands. */
  @Bean
  FlywayMigrationStrategy gatewayMigrationStrategy(GatewayPaths paths, StartupFacts facts,
      ObjectProvider<BeforeMigrations> before, ParallelSchemaValidation schemaValidation) {
    return new GatewayMigrationStrategy(paths, facts, before.orderedStream().toList(), schemaValidation);
  }

  /**
   * JSON columns through Boot's Jackson 3 mapper. Hibernate would otherwise pick a mapper by {@code Class.forName}
   * probing, Jackson 2 first; the instance pins the choice and Boot's settings (report 15 §9 rule 16).
   */
  @Bean
  HibernatePropertiesCustomizer jsonFormatMapper(JsonMapper jsonMapper) {
    return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER, new Jackson3JsonFormatMapper(jsonMapper));
  }

  /**
   * Start-up cost of the persistence unit (ARCHITECTURE.md §10, M1.7): the entity list is Spring's, so Hibernate
   * neither scans nor looks for class files ({@link ListedClassesScanner}), and there is no Bean Validation to
   * integrate (the core has no constraint annotations; without a provider Hibernate would still probe for one).
   */
  @Bean
  HibernatePropertiesCustomizer persistenceUnitStartup() {
    return properties -> {
      ClassLoader classes = GatewayDataSourceConfig.class.getClassLoader();
      properties.put(PersistenceSettings.SCANNER, new ListedClassesScanner(classes));
      properties.put(ValidationSettings.JAKARTA_VALIDATION_MODE, "none");
    };
  }

  /** The mapping check of {@code ddl-auto=validate} beside the persistence unit's build, not inside it. */
  @Bean
  ParallelSchemaValidation parallelSchemaValidation(ObjectProvider<EntityManagerFactory> entityManagerFactory) {
    return new ParallelSchemaValidation(entityManagerFactory);
  }

  /**
   * Hibernate builds the persistence unit on the task executor ({@code spring.jpa.bootstrap: async}). Asking for the
   * factory here, when the web server factory is customized (the first thing the context does in {@code onRefresh}),
   * starts that build early, so that it (a fifth of the native start, ARCHITECTURE.md §10, M1.7) overlaps Flyway,
   * Tomcat's setup and the first beans instead of starting when the first repository needs it; the factory comes
   * first, so the build starts before Flyway runs ({@link #persistenceUnitBesideMigrations}). The customizer itself
   * changes nothing; without a web server (MockMvc tests) both are simply created in the usual order.
   */
  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> startPersistenceUnitEarly(
      EntityManagerFactory entityManagerFactory, FlywayMigrationInitializer migrations) {
    return factory -> {
    };
  }

  /**
   * The persistence unit is built beside Flyway, not after it (ARCHITECTURE.md §2.4, warm start). Boot makes the
   * factory depend on the migrations, so its build — the critical path of the start — waited for them. The build
   * reads no table: what does is a query, and the mapping check. So the dependency moves to where the tables are
   * read: Spring Data's repositories and the shared {@code EntityManager}s depend on the migrations instead, and the
   * check waits for them itself ({@link ParallelSchemaValidation}). No query can run before the schema is migrated,
   * as before. Runs at AOT processing too, so the generated definitions already have the moved dependencies.
   */
  @Bean
  static BeanFactoryPostProcessor persistenceUnitBesideMigrations() {
    return beanFactory -> {
      for (String name : beanFactory.getBeanDefinitionNames()) {
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        String type = definition.getBeanClassName();
        if (ENTITY_MANAGER_FACTORY.equals(name)) {
          definition.setDependsOn(without(definition.getDependsOn(), MIGRATIONS));
        } else if (JpaRepositoryFactoryBean.class.getName().equals(type)
            || SharedEntityManagerCreator.class.getName().equals(type)) {
          definition.setDependsOn(with(definition.getDependsOn(), MIGRATION_INITIALIZER));
        }
      }
    };
  }

  /** Boot's names for the persistence unit and the migrations' beans. */
  private static final String ENTITY_MANAGER_FACTORY = "entityManagerFactory";
  private static final String MIGRATION_INITIALIZER = "flywayInitializer";
  private static final List<String> MIGRATIONS = List.of("flyway", MIGRATION_INITIALIZER);

  private static String[] without(String[] dependsOn, List<String> names) {
    return dependsOn == null ? null
        : Arrays.stream(dependsOn).filter(name -> !names.contains(name)).toArray(String[]::new);
  }

  private static String[] with(String[] dependsOn, String name) {
    if (dependsOn == null) {
      return new String[] {name};
    }
    return Arrays.asList(dependsOn).contains(name) ? dependsOn
        : Stream.concat(Arrays.stream(dependsOn), Stream.of(name)).toArray(String[]::new);
  }

  /**
   * Repositories without the parts the core doesn't use, each of which costs the native start (ARCHITECTURE.md §10,
   * M1.7):
   * <ul>
   *   <li>Spring Data's mapping context: every repository would warm it up with its entity — every property
   *       introspected by reflection and annotation lookups, a tenth of the start — for auditing ({@code @CreatedDate}
   *       and the like), Spring Data REST and its web support. Queries, projections and the JPA metamodel don't need
   *       it; the context ({@code jpaMappingContext}) is lazy and so is never built.</li>
   *   <li>Named queries from {@code META-INF/jpa-named-queries.properties}: one factory bean per repository, each
   *       scanning the class path for a file the core doesn't have; a repository without them gets none.</li>
   * </ul>
   * Runs at AOT processing too, so the generated definitions already come without them.
   */
  @Bean
  static BeanFactoryPostProcessor leanRepositoryDefinitions() {
    return beanFactory -> {
      for (String name : beanFactory.getBeanDefinitionNames()) {
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        String type = definition.getBeanClassName();
        if (JpaRepositoryFactoryBean.class.getName().equals(type)) {
          definition.getPropertyValues().removePropertyValue("mappingContext");
          definition.getPropertyValues().removePropertyValue("namedQueries");
        } else if (PropertiesBasedNamedQueriesFactoryBean.class.getName().equals(type)
            && beanFactory instanceof BeanDefinitionRegistry registry) {
          registry.removeBeanDefinition(name);
        }
      }
    };
  }

  /** Constraint violations and lock errors as Spring's exception types (see {@link SqliteExceptionTranslator}). */
  @Bean
  static BeanPostProcessor sqliteExceptionTranslation() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HibernateJpaVendorAdapter adapter) {
          adapter.getJpaDialect().setJdbcExceptionTranslator(new SqliteExceptionTranslator());
        }
        return bean;
      }
    };
  }

  /** Proves the pools and the read-only routing before the local API opens. */
  @Bean
  DatabaseSelfCheck databaseSelfCheck(SqlitePools pools, GatewayDatabaseProperties properties) {
    return new DatabaseSelfCheck(pools, properties);
  }
}
