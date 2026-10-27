package ai.msrv.gaterook.core.platform;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * Freezes the bean definitions as soon as the bean factory post-processors are done (ARCHITECTURE.md §10, M1.7).
 * Spring caches by-type lookups only on a frozen configuration, and freezes it just before it instantiates the
 * singletons; until then — the post-processors, the web server with its filters and everything they depend on, now
 * also Flyway and the persistence unit — every autowired type was a walk over all bean definitions, which in the
 * native image is the largest single cost of creating beans. Nothing defines beans after the post-processors (the
 * AOT context registers them all up front); singletons registered later clear the cache as they always do.
 *
 * <p>A post-processor only to be created early: {@link PriorityOrdered} ones are instantiated first, right after the
 * bean factory post-processors and their metadata reset, which is the earliest safe point.
 */
final class EarlyFrozenConfiguration implements BeanPostProcessor, BeanFactoryAware, PriorityOrdered {

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    if (beanFactory instanceof ConfigurableListableBeanFactory listable) {
      listable.freezeConfiguration();
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
