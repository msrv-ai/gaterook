package ai.msrv.gaterook.core.platform;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * {@code @EnableAsync} for the gateway's own beans only. Spring's processor asks every bean of the context whether a
 * class or method carries {@code @Async}, walking the class hierarchy and every method with merged-annotation lookups;
 * in the native image that was a tenth of the core's start (ARCHITECTURE.md §10, M1.7), almost all of it spent on
 * beans that never have an {@code @Async} method. Only classes under {@code ai.msrv.gaterook} (and their CGLIB
 * subclasses, which keep the name) that declare {@code @Async} in their own hierarchy ({@link OwnAnnotations}) are
 * asked; everything else is not eligible.
 *
 * <p>The executor is Boot's {@code AsyncConfigurer} ({@code applicationTaskExecutor}, virtual threads), resolved on
 * the first asynchronous call, as {@code @EnableAsync} resolves it.
 */
final class GatewayAsync extends AsyncAnnotationBeanPostProcessor {

  static final OwnAnnotations ASYNC = new OwnAnnotations("org.springframework.scheduling.annotation.Async",
      "jakarta.ejb.Asynchronous");

  GatewayAsync(ObjectProvider<AsyncConfigurer> configurers) {
    configure(() -> {
      AsyncConfigurer configurer = configurers.getIfUnique();
      Executor executor = configurer == null ? null : configurer.getAsyncExecutor();
      return executor;
    }, () -> {
      AsyncConfigurer configurer = configurers.getIfUnique();
      return configurer == null ? null : configurer.getAsyncUncaughtExceptionHandler();
    });
  }

  @Override
  protected boolean isEligible(Class<?> targetClass) {
    return ASYNC.declaredBy(targetClass) && super.isEligible(targetClass);
  }
}
