package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * The start's singleton lock and the persistence unit's build thread (ARCHITECTURE.md A10, M1.7): the start holds
 * the lock while it creates a bean that waits for the build, and the build asks the bean factory for beans by type
 * meanwhile. Spring alone makes that thread wait for the lock outside its pre-instantiation phase — a deadlock.
 */
class GatewayBeanFactoryLockingTest {

  /** What the build looks up: no bean has this type, but a factory bean of unknown type must be asked. */
  interface TenantResolver {}

  /** A factory bean whose product type only an instance tells (like Spring Data's repository factory beans). */
  public static final class UnknownProduct implements FactoryBean<Object> {
    @Override
    public Object getObject() {
      return "product";
    }

    @Override
    public Class<?> getObjectType() {
      return String.class;
    }
  }

  @Test
  void aLookupOnAnotherThreadDoesNotWaitForTheLockTheStartHolds() throws Exception {
    GatewayBeanFactory beans = new GatewayBeanFactory(false);
    beans.registerBeanDefinition("repository", new RootBeanDefinition(UnknownProduct.class));
    CompletableFuture<String[]> build = new CompletableFuture<>();
    // Created on this (the starting) thread under the singleton lock; waits for the "build" like a repository waits
    // for the persistence unit.
    beans.registerBeanDefinition("waitsForTheBuild", new RootBeanDefinition(Object.class, () -> {
      Thread.ofPlatform().name("task-1").start(() -> build.complete(beans.getBeanNamesForType(TenantResolver.class,
          true, true)));
      try {
        return build.get(10, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        throw new IllegalStateException("The build thread waited for the singleton lock: a deadlock", e);
      } catch (InterruptedException | ExecutionException e) {
        throw new IllegalStateException(e);
      }
    }));

    beans.getBean("waitsForTheBuild");

    assertThat(build.get()).isEmpty();
    // The lenient answer isn't remembered: once the lock is free the factory bean is asked properly.
    assertThat(beans.getBeanNamesForType(String.class, true, true)).containsExactly("repository");
  }

  @Test
  void afterTheStartOtherThreadsLockAsSpringSays() throws Exception {
    GatewayBeanFactory beans = new GatewayBeanFactory(false);
    beans.preInstantiateSingletons();

    CompletableFuture<Boolean> other = new CompletableFuture<>();
    Thread.ofPlatform().start(() -> other.complete(beans.isCurrentThreadAllowedToHoldSingletonLock()));

    assertThat(other.get(10, TimeUnit.SECONDS)).isNull();
  }
}
