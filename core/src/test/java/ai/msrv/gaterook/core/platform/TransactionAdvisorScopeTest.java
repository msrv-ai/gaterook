package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AopUtils;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;

/**
 * The scoped advisors ({@link TransactionAdvisorScope}, {@link GatewayAsync}) proxy exactly what Spring's own would
 * (M1.7, start-up cost): no class of the core that Spring finds {@code @Transactional} or {@code @Async} on is left
 * out by the cheaper hierarchy check.
 */
class TransactionAdvisorScopeTest {

  interface Port {
    @Transactional
    void write();
  }

  static class ViaInterface implements Port {
    @Override
    public void write() {}
  }

  static class OnMethod {
    @Transactional(readOnly = true)
    public void read() {}
  }

  @Transactional
  static class OnType {
    public void any() {}
  }

  /** What a CGLIB subclass looks like to the filter: a gateway name, the annotation only on the superclass. */
  static class Subclass extends OnMethod {}

  static class Plain {
    public void nothing() {}
  }

  @Test
  void theFilterPassesEveryWayAGatewayClassCanBeTransactional() {
    assertThat(TransactionAdvisorScope.mayBeTransactional(ViaInterface.class)).isTrue();
    assertThat(TransactionAdvisorScope.mayBeTransactional(OnMethod.class)).isTrue();
    assertThat(TransactionAdvisorScope.mayBeTransactional(OnType.class)).isTrue();
    assertThat(TransactionAdvisorScope.mayBeTransactional(Subclass.class)).isTrue();
    assertThat(TransactionAdvisorScope.mayBeTransactional(Plain.class)).isFalse();
    assertThat(TransactionAdvisorScope.mayBeTransactional(String.class)).isFalse();
  }

  @Test
  void noClassOfTheCoreThatSpringWouldProxyForTransactionsIsLeftOut() {
    BeanFactoryTransactionAttributeSourceAdvisor spring = new BeanFactoryTransactionAttributeSourceAdvisor();
    spring.setTransactionAttributeSource(new AnnotationTransactionAttributeSource(false));
    assertNoneLeftOut(spring.getPointcut(), TransactionAdvisorScope.TRANSACTIONAL);
  }

  @Test
  void noClassOfTheCoreThatSpringWouldProxyForAsyncIsLeftOut() {
    assertNoneLeftOut(new AsyncAnnotationAdvisor().getPointcut(), GatewayAsync.ASYNC);
  }

  private static void assertNoneLeftOut(Pointcut spring, OwnAnnotations ours) {
    List<String> matched = new ArrayList<>();
    List<String> missed = new ArrayList<>();
    for (JavaClass c : new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ai.msrv.gaterook.core")) {
      if (c.isInterface() || c.isAnnotation() || c.isEnum() || c.isRecord()) {
        continue;
      }
      Class<?> type = c.reflect();
      if (AopUtils.canApply(spring, type)) {
        matched.add(type.getName());
        if (!ours.declaredBy(type)) {
          missed.add(type.getName());
        }
      }
    }
    assertThat(matched).as("the core does use the annotation").isNotEmpty();
    assertThat(missed).isEmpty();
  }
}
