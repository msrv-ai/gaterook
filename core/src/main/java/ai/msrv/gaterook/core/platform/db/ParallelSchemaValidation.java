package ai.msrv.gaterook.core.platform.db;

import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.SessionFactory;
import org.hibernate.SessionFactoryObserver;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

/**
 * Hibernate's check of its mapping against the Flyway schema ({@code ddl-auto=validate}, decision 3), on a thread of
 * its own instead of inside the build of the persistence unit (ARCHITECTURE.md §2.4, warm start).
 *
 * <p>The build already runs beside the web server's setup, but the first repository waits for it, and the check —
 * reading the metadata of every table — was a sixth of it. So the check is taken out of the build: the customizer
 * turns {@code validate} into {@code none} for the build and keeps the request, an integrator hears the session
 * factory being created and runs exactly what Hibernate would have run then
 * ({@link SchemaManagementToolCoordinator#process} with {@code validate}) once Flyway has migrated the schema (the
 * build runs beside the migrations: {@code GatewayDataSourceConfig.persistenceUnitBesideMigrations}), and this bean
 * waits for it once all singletons exist — before the web server starts and the ready line goes out. A mapping that
 * doesn't match the schema still fails the start, with Hibernate's own exception.
 */
final class ParallelSchemaValidation implements HibernatePropertiesCustomizer, SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(ParallelSchemaValidation.class);

  /** Far longer than the check takes (milliseconds); only a check that never started could reach it. */
  private static final long PATIENCE_SECONDS = 60;

  private final ObjectProvider<EntityManagerFactory> factory;
  private final CompletableFuture<Void> migrations = new CompletableFuture<>();
  private final CompletableFuture<Void> done = new CompletableFuture<>();
  private volatile boolean requested;

  /** The factory only through a provider: it is built with this customizer. */
  ParallelSchemaValidation(ObjectProvider<EntityManagerFactory> factory) {
    this.factory = factory;
  }

  @Override
  public void customize(Map<String, Object> properties) {
    if (!"validate".equals(String.valueOf(properties.get(SchemaToolingSettings.HBM2DDL_AUTO)))) {
      done.complete(null);
      return;
    }
    requested = true;
    properties.put(SchemaToolingSettings.HBM2DDL_AUTO, "none");
    Object earlier = properties.get(JpaSettings.INTEGRATOR_PROVIDER);
    if (earlier != null) {
      throw new IllegalStateException("Another integrator provider is configured: " + earlier);
    }
    properties.put(JpaSettings.INTEGRATOR_PROVIDER, (IntegratorProvider) () -> List.of(new Validator()));
  }

  /** Waits for the check (it usually finished long before); its failure is the start's. */
  @Override
  public void afterSingletonsInstantiated() {
    if (!requested) {
      return;
    }
    // The build itself first: a build that failed never creates the factory the check waits for.
    factory.getObject().getMetamodel();
    try {
      done.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException failure) {
        throw failure;
      }
      throw new IllegalStateException("The schema validation failed", e.getCause());
    } catch (TimeoutException e) {
      throw new IllegalStateException("The schema validation didn't finish in " + PATIENCE_SECONDS + " s");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while the schema was validated", e);
    }
  }

  /** Flyway brought the schema up to date: the check may read it (the build runs beside the migrations). */
  void migrated() {
    migrations.complete(null);
  }

  /** Flyway failed: the start fails with that, and the check has nothing to read. */
  void migrationsFailed(Throwable failure) {
    migrations.completeExceptionally(failure);
  }

  /** Whether the check ran beside the build and passed (tests). */
  boolean passed() {
    return requested && done.isDone() && !done.isCompletedExceptionally();
  }

  /** Starts the check once the session factory exists, with the factory's settings and {@code validate}. */
  private final class Validator implements Integrator {

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor factory) {
      factory.addObserver(new SessionFactoryObserver() {
        @Override
        public void sessionFactoryCreated(SessionFactory created) {
          Map<String, Object> settings = new HashMap<>(created.getProperties());
          settings.put(SchemaToolingSettings.HBM2DDL_AUTO, "validate");
          Thread.ofVirtual().name("schema-validation").start(() -> {
            try {
              migrations.join();
              SchemaManagementToolCoordinator.process(metadata, factory.getServiceRegistry(), settings, action -> {
              });
              done.complete(null);
            } catch (CompletionException e) {
              // The migrations failed; that failure is the start's, not a mapping problem.
              done.completeExceptionally(e.getCause());
            } catch (RuntimeException | Error e) {
              log.error("The mapping doesn't match the database schema", e);
              done.completeExceptionally(e);
            }
          });
        }
      });
    }
  }
}
