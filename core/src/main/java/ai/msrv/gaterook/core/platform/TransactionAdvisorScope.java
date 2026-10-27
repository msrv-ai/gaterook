package ai.msrv.gaterook.core.platform;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.support.ClassFilters;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;

/**
 * {@code @Transactional} proxies only for the gateway classes that declare it. The auto-proxy creator asks the
 * transaction advisor about every bean of the context, and its pointcut resolves the transaction attribute of every
 * method of the class, its superclasses and interfaces through merged-annotation lookups; in the native image each of
 * them decodes reflection metadata, and almost all of it was spent on beans that never carry the annotation — the
 * framework's, and most of the gateway's own: about fifteen of its classes use {@code @Transactional}, the rest work
 * with {@code TransactionTemplate} or not at all (ARCHITECTURE.md §10, M1.7).
 *
 * <p>The advisor's class filter becomes "a gateway class that declares {@code @Transactional} (Spring's or JTA's) in
 * its own hierarchy" ({@link OwnAnnotations}) and Spring's filter; Spring's pointcut then decides method by method as
 * before. Repositories are not affected: Spring Data puts its own transaction interceptor inside each repository
 * proxy.
 *
 * <p>The scope is set before the advisor is first used: this post-processor is {@link PriorityOrdered} (its bean
 * method declares this type, so the context sees that before it creates the bean), hence registered ahead of the
 * auto-proxy creator, which looks the advisor up only when it post-processes its first bean.
 */
final class TransactionAdvisorScope implements BeanPostProcessor, PriorityOrdered {

  static final OwnAnnotations TRANSACTIONAL = new OwnAnnotations(
      "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

  /** A gateway class (or its CGLIB subclass) whose own hierarchy declares {@code @Transactional}. */
  static final ClassFilter OWN_TRANSACTIONAL_CLASSES = TRANSACTIONAL::declaredBy;

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof BeanFactoryTransactionAttributeSourceAdvisor advisor) {
      ClassFilter framework = advisor.getPointcut().getClassFilter();
      advisor.setClassFilter(ClassFilters.intersection(OWN_TRANSACTIONAL_CLASSES, framework));
    }
    return bean;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  static boolean mayBeTransactional(Class<?> type) {
    return TRANSACTIONAL.declaredBy(type);
  }
}
