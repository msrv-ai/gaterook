package ai.msrv.gaterook.core.admin;

import ai.msrv.gaterook.core.platform.Bootstrap;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The second lock on the admin API (ARCHITECTURE.md §6, report 02 §6.1): a handler of {@code /api/**} or
 * {@code /internal/**} that a request on the public (tunnel) port somehow reached answers 404, whatever the filters
 * before it did. The first lock is the tunnel's ingress filter, which never lets these paths through.
 */
@Configuration(proxyBeanMethods = false)
class AdminPortGuard implements WebMvcConfigurer {

  private final Bootstrap bootstrap;

  AdminPortGuard(Bootstrap bootstrap) {
    this.bootstrap = bootstrap;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getLocalPort() == bootstrap.publicPort()) {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
          response.setContentLength(0);
          return false;
        }
        return true;
      }
    }).addPathPatterns("/api/**", "/internal/**");
  }
}
