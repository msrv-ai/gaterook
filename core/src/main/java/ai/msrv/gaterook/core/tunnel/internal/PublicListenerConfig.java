package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import jakarta.servlet.DispatcherType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The public listener of the tunnel (ARCHITECTURE.md A6, §7.3): a second Tomcat connector on {@code 127.0.0.1} at
 * the bootstrap's public port, guarded by {@link PublicIngressFilter} before every other filter. The address is set
 * here explicitly and the connector gets its own virtual-thread executor: Boot's customizers only touch the main
 * connector.
 *
 * <p>Opened whenever Electron started the core (the bootstrap names the port, so a taken port fails the start like
 * the local one, {@code PORT_IN_USE}); a development or test core started without a bootstrap line opens it only with
 * {@code gateway.public-listener=true}, so in-process tests don't fight over port 7466.
 */
@Configuration(proxyBeanMethods = false)
class PublicListenerConfig {

  /** 127.0.0.1 itself, never what {@code localhost} or the IPv6 preference would make of it. */
  private static final InetAddress LOOPBACK = loopback();

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> publicConnector(Bootstrap bootstrap,
      @Value("${gateway.public-listener:false}") boolean inDevelopment) {
    return factory -> {
      if (bootstrap.dev() && !inDevelopment) {
        return;
      }
      // The protocol as an instance and its address through its setter: Connector(String) loads the class
      // reflectively and setProperty walks the setters by reflection, neither of which the native image allows here.
      Http11NioProtocol protocol = new Http11NioProtocol();
      protocol.setAddress(LOOPBACK);
      protocol.setExecutor(new VirtualThreadExecutor("tomcat-public-"));
      Connector connector = new Connector(protocol);
      connector.setPort(bootstrap.publicPort());
      connector.setXpoweredBy(false);
      connector.setMaxPostSize(PublicIngressFilter.MAX_BODY);
      factory.addAdditionalConnectors(connector);
    };
  }

  /** Settings › Tunnel › Callers and the self-test's nonces; nothing but the clock, so filters can take it early. */
  @Bean
  CallerGate callerGate(Clock clock) {
    return new CallerGate(clock);
  }

  @Bean
  FilterRegistrationBean<PublicIngressFilter> publicIngressFilter(Bootstrap bootstrap, TunnelPublication tunnel,
      CallerGate gate) {
    FilterRegistrationBean<PublicIngressFilter> registration =
        new FilterRegistrationBean<>(new PublicIngressFilter(bootstrap, tunnel, gate));
    registration.setName("publicIngressFilter");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }

  private static InetAddress loopback() {
    try {
      return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }
}
