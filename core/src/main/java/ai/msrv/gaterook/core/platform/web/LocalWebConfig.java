package ai.msrv.gaterook.core.platform.web;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.SettingStore;
import jakarta.servlet.DispatcherType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** The local listener: bound to 127.0.0.1 in code (never from Settings) and guarded by {@link LocalGuardFilter}. */
@Configuration(proxyBeanMethods = false)
class LocalWebConfig {

  /** After the tunnel's {@code PublicIngressFilter} ({@code HIGHEST_PRECEDENCE}), before everything else. */
  static final int GUARD_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  @Bean
  FilterRegistrationBean<LocalGuardFilter> localGuardFilter(Bootstrap bootstrap, ObjectProvider<SettingStore> settings,
      Clock clock) {
    LocalGuardFilter filter = new LocalGuardFilter(bootstrap, new AllowedOrigins(settings, bootstrap),
        new ReplayGuard(clock), clock);
    FilterRegistrationBean<LocalGuardFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setName("localGuardFilter");
    registration.setOrder(GUARD_ORDER);
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }

  /** Right after the guard: the locked mode only ever sees requests the guard let through. */
  @Bean
  FilterRegistrationBean<LockedModeFilter> lockedModeFilter(MasterKeyLock lock) {
    FilterRegistrationBean<LockedModeFilter> registration = new FilterRegistrationBean<>(new LockedModeFilter(lock));
    registration.setName("lockedModeFilter");
    registration.setOrder(GUARD_ORDER + 1);
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }

  /** Loopback only, whatever {@code server.address} says: the desktop never accepts calls from the network. */
  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> loopbackOnly() {
    return new WebServerFactoryCustomizer<>() {
      @Override
      public void customize(ConfigurableServletWebServerFactory factory) {
        try {
          factory.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        } catch (UnknownHostException e) {
          throw new IllegalStateException(e);
        }
      }
    };
  }
}
