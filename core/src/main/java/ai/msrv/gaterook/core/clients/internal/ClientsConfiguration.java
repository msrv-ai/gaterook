package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import ai.msrv.gaterook.core.endpoints.ClientGrants;
import ai.msrv.gaterook.core.platform.BuildFlags;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wiring of the clients module: where clients live on this computer ({@link ClientEnvironment}), the header that ties a
 * bridge to the core that issued its token, and the native-image hint for the bridge script inside every {@code .mcpb}.
 *
 * <p>Tests and the demo world point the clients at another home ({@code gateway.clients.user-home}, or
 * {@code <gateway.home>/demo-user-home} with {@code GW_DEMO=1}): a release ignores both and always uses the person's own.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ClientsConfiguration.Hints.class)
public class ClientsConfiguration {

  /** {@code X-Gateway-Bridge-Instance} of every {@code /mcp} answer (ClientGrants.TokenAnswer#instance). */
  public static final String INSTANCE_HEADER = "X-Gateway-Bridge-Instance";

  private static final Logger log = LoggerFactory.getLogger(ClientsConfiguration.class);

  @Bean
  ClientEnvironment clientEnvironment(@Value("${gateway.home}") Path home,
      @Value("${gateway.resources:}") String resources, Environment environment) {
    boolean release = BuildFlags.RELEASE;
    String override = release ? null : environment.getProperty("gateway.clients.user-home");
    String demo = environment.getProperty("GW_DEMO", environment.getProperty("gateway.demo", "false"));
    boolean demoWorld = !release && (demo.equals("1") || demo.equalsIgnoreCase("true"));
    Path userHome;
    boolean sandboxed;
    if (override != null && !override.isBlank()) {
      userHome = Path.of(override);
      sandboxed = true;
    } else if (demoWorld) {
      // The demo's clients live in the demo home: Connect never touches the developer's real Claude, Codex or Cursor.
      userHome = home.resolve("demo-user-home");
      sandboxed = true;
    } else {
      userHome = Path.of(System.getProperty("user.home"));
      sandboxed = false;
    }
    Os os = Os.current();
    String forced = release ? null : environment.getProperty("gateway.clients.os");
    if (forced != null && !forced.isBlank()) {
      os = Os.valueOf(forced.toUpperCase(Locale.ROOT));
    }
    if (sandboxed) {
      log.info("Clients are looked for in {} (not this user's home)", userHome);
    }
    return new ClientEnvironment(os, userHome, home, resources == null || resources.isBlank() ? null
        : Path.of(resources), System.getenv(), sandboxed, ClientEnvironment.readHostname(System.getenv()));
  }

  /**
   * Every answer on {@code /mcp/*} carries this run's bridge instance id. A bridge compares it with the id its token
   * came with: an answer without it, or with another, isn't from the core that issued the token (a process squatting
   * the port after a restart), and the bridge says MCP Gateway isn't running instead of sending it the next request.
   * The id is random per run and means nothing else.
   */
  @Bean
  FilterRegistrationBean<OncePerRequestFilter> bridgeInstanceFilter(ObjectProvider<ClientGrants> grants) {
    // ClientGrants is looked up on the first request, not here: filters are created while the web server starts,
    // before the singletons, and a repository asked for at that point waits for the JPA bootstrap that runs in the
    // background — which needs the bean factory's lock this thread holds (a deadlock of the start).
    OncePerRequestFilter filter = new OncePerRequestFilter() {
      private volatile String instance;

      @Override
      protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        String id = instance;
        if (id == null) {
          id = grants.getObject().instance();
          instance = id;
        }
        response.setHeader(INSTANCE_HEADER, id);
        chain.doFilter(request, response);
      }
    };
    FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/mcp/*");
    // After the local guard: a request it refuses learns nothing.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    registration.setName("bridgeInstanceFilter");
    return registration;
  }

  /** The bridge script McpbBuilder packs. */
  static final class Hints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.resources().registerPattern(McpbBuilder.BRIDGE_RESOURCE);
    }
  }
}
