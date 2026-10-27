package ai.msrv.gaterook.core.platform.boot;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * The core's bean factory: Spring's, with by-type lookups that stay cheap while the singletons are created
 * (ARCHITECTURE.md §2.4, warm start).
 *
 * <p>Every autowired constructor argument is a by-type lookup. Spring caches them on a frozen configuration, but a
 * miss walks all ~500 bean definitions and asks each for its predicted type, through every smart post-processor —
 * and each new singleton evicts every cached type it is an instance of, so the lookup a later bean needs right after
 * its dependency was created misses again. In the native image those walks were the largest cost of the start after
 * the garbage collector. Two changes, both within Spring's own semantics:
 * <ul>
 *   <li>Cached names are evicted only when the new singleton changes them: it is an instance of the cached type and
 *       isn't among the names yet (as itself, or as {@code &name} for a factory bean). That is the case Spring's
 *       eviction exists for — a definition that didn't predict a type its instance has (a {@code @Bean} method that
 *       declares an interface, a proxy); where the bean already was one of the names nothing changed.</li>
 *   <li>The predicted type of a singleton whose creation hasn't started is remembered. It depends on the merged
 *       definition and on the smart post-processors (the auto-proxy creator predicts a proxy only for beans it
 *       already proxied, that is, created), so it holds until a definition, a post-processor or a singleton changes;
 *       once the creation starts, Spring predicts the instance's class, and is asked again.</li>
 * </ul>
 * Both clear whenever Spring clears its own by-type cache: definitions registered or removed, post-processors added,
 * singletons destroyed, the metadata cache cleared.
 *
 * <p>With {@code -Dgateway.platform.verify-type-cache=true} (or {@code GatewayLauncher.contexts(true)}) every answer
 * from either cache is checked against Spring's own, and a difference fails it: the tests start their cores that way.
 *
 * <p>While the context starts, other threads lock singletons leniently, as Spring's own background threads do in its
 * pre-instantiation phase: the persistence unit is built on a task thread from {@code onRefresh} on
 * ({@code GatewayDataSourceConfig.startPersistenceUnitEarly}), before that phase, and the start may create a bean
 * that waits for it (a repository needs the factory) while it holds the singleton lock. Hibernate's lookups on the
 * build thread (its tenant resolver, through {@code SpringBeanContainer}) then waited for that lock — a deadlock.
 * Lenient, they take the lock when it is free and otherwise skip a factory bean's shortcut instance; their answers
 * aren't cached here.
 */
final class GatewayBeanFactory extends DefaultListableBeanFactory {

  static final String VERIFY_PROPERTY = "gateway.platform.verify-type-cache";

  /** A remembered prediction of "no type". */
  private static final Class<?> UNPREDICTABLE = Void.class;

  /**
   * Spring's servlet context of the core with this bean factory: the plain one when the application runs its AOT
   * code (the native image), the annotation-driven one otherwise — as Boot's own factory chooses.
   */
  static ApplicationContextFactory contexts(boolean verify) {
    return type -> {
      GatewayBeanFactory beans = new GatewayBeanFactory(verify || Boolean.getBoolean(VERIFY_PROPERTY));
      return AotDetector.useGeneratedArtifacts() ? new ServletWebServerApplicationContext(beans)
          : new AnnotationConfigServletWebServerApplicationContext(beans);
    };
  }

  private final Map<Class<?>, String[]> withNonSingletons = new ConcurrentHashMap<>(256);
  private final Map<Class<?>, String[]> singletonsOnly = new ConcurrentHashMap<>(256);
  private final Map<String, Class<?>> predicted = new ConcurrentHashMap<>(512);
  private final Map<String, Class<?>> predictedAsFactoryBean = new ConcurrentHashMap<>(64);
  private final boolean verify;
  /** The thread that refreshes the context, until its singletons are instantiated; then {@code null}. */
  private volatile @Nullable Thread starting = Thread.currentThread();

  GatewayBeanFactory(boolean verify) {
    this.verify = verify;
  }

  // ── Names by type ───────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
    if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
      return super.getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
    }
    Map<Class<?>, String[]> cache = includeNonSingletons ? withNonSingletons : singletonsOnly;
    String[] cached = cache.get(type);
    if (cached != null) {
      if (verify) {
        String[] spring = super.getBeanNamesForType(type, includeNonSingletons, true);
        if (!Arrays.equals(spring, cached)) {
          throw new IllegalStateException("The by-type cache answers " + Arrays.toString(cached) + " for "
              + type.getName() + ", Spring " + Arrays.toString(spring));
        }
      }
      return cached;
    }
    String[] names = super.getBeanNamesForType(type, includeNonSingletons, true);
    if (ClassUtils.isCacheSafe(type, getBeanClassLoader()) && !lenient()) {
      cache.put(type, names);
    }
    return names;
  }

  @Override
  protected void addSingleton(String beanName, Object singletonObject) {
    super.addSingleton(beanName, singletonObject);
    predicted.remove(beanName);
    predictedAsFactoryBean.remove(beanName);
    evictJoined(withNonSingletons, beanName, singletonObject);
    evictJoined(singletonsOnly, beanName, singletonObject);
  }

  private static void evictJoined(Map<Class<?>, String[]> cache, String beanName, Object singleton) {
    if (cache.isEmpty()) {
      return;
    }
    String factoryName = BeanFactory.FACTORY_BEAN_PREFIX + beanName;
    cache.entrySet().removeIf(e -> e.getKey().isInstance(singleton) && !contains(e.getValue(), beanName, factoryName));
  }

  private static boolean contains(String[] names, String beanName, String factoryName) {
    for (String name : names) {
      if (name.equals(beanName) || name.equals(factoryName)) {
        return true;
      }
    }
    return false;
  }

  // ── Locking while the context starts ────────────────────────────────────────────────────────────────────────

  @Override
  protected @Nullable Boolean isCurrentThreadAllowedToHoldSingletonLock() {
    Boolean spring = super.isCurrentThreadAllowedToHoldSingletonLock();
    // Spring's null is "always the full lock"; outside its pre-instantiation phase that is every thread's answer.
    return spring == null && lenient() ? Boolean.TRUE : spring;
  }

  @Override
  public void preInstantiateSingletons() {
    super.preInstantiateSingletons();
    starting = null;
  }

  /** Another thread than the starting one, while the context starts. */
  private boolean lenient() {
    Thread main = starting;
    return main != null && main != Thread.currentThread();
  }

  // ── Predicted types ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  protected @Nullable Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
    // Once its creation started, Spring knows the instance's class (resolvedTargetType) and predicts that instead.
    if (!isConfigurationFrozen() || !mbd.isSingleton() || containsSingleton(beanName)
        || isCurrentlyInCreation(beanName)) {
      return super.predictBeanType(beanName, mbd, typesToMatch);
    }
    // Spring's prediction differs only for the question "is it a factory bean?" (typesToMatch = FactoryBean).
    boolean asFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
    Map<String, Class<?>> memo = asFactoryBean ? predictedAsFactoryBean : predicted;
    Class<?> known = memo.get(beanName);
    if (known != null) {
      Class<?> answer = known == UNPREDICTABLE ? null : known;
      if (verify) {
        Class<?> spring = super.predictBeanType(beanName, mbd, typesToMatch);
        if (!Objects.equals(spring, answer)) {
          throw new IllegalStateException("The type predicted for " + beanName + " is remembered as " + answer
              + ", Spring predicts " + spring);
        }
      }
      return answer;
    }
    Class<?> type = super.predictBeanType(beanName, mbd, typesToMatch);
    memo.put(beanName, type == null ? UNPREDICTABLE : type);
    return type;
  }

  // ── Where Spring clears its by-type cache ───────────────────────────────────────────────────────────────────

  @Override
  public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
      throws BeanDefinitionStoreException {
    super.registerBeanDefinition(beanName, beanDefinition);
    clearByType();
  }

  @Override
  public void registerSingleton(String beanName, Object singletonObject) {
    super.registerSingleton(beanName, singletonObject);
    // As Spring does: a manual singleton joins every Object lookup.
    withNonSingletons.remove(Object.class);
    singletonsOnly.remove(Object.class);
  }

  @Override
  public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
    super.addBeanPostProcessor(beanPostProcessor);
    clearByType();
  }

  @Override
  public void addBeanPostProcessors(Collection<? extends BeanPostProcessor> beanPostProcessors) {
    super.addBeanPostProcessors(beanPostProcessors);
    clearByType();
  }

  @Override
  public void clearMetadataCache() {
    super.clearMetadataCache();
    clearByType();
  }

  @Override
  public void destroySingletons() {
    super.destroySingletons();
    clearByType();
  }

  @Override
  public void destroySingleton(String beanName) {
    super.destroySingleton(beanName);
    clearByType();
  }

  private void clearByType() {
    // The super constructor may get here before the fields are assigned.
    if (withNonSingletons != null) {
      withNonSingletons.clear();
      singletonsOnly.clear();
      predicted.clear();
      predictedAsFactoryBean.clear();
    }
  }
}
