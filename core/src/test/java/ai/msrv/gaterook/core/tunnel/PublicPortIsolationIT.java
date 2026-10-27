package ai.msrv.gaterook.core.tunnel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Isolation of the public listener (ARCHITECTURE.md §10 K3 DoD, §7.3, report 02 §8): every route the core has — the
 * patterns of every {@link HandlerMapping} bean, every servlet mapping of the container, {@code /error} — with every
 * method it takes, requested on 7466 with the tunnel's own {@code Host} from inside Anthropic's range: all of it 404
 * except the allowlist of the published endpoint and the OAuth paths. A mapping added later is covered without
 * touching this test; a kind of handler mapping it can't list fails it.
 */
class PublicPortIsolationIT {

  /** What the tunnel may serve, as raw paths of the published endpoint {@code stage-readonly}. */
  private static final Pattern ALLOWED = Pattern.compile("^(/mcp/stage-readonly|/oauth/(authorize|authorize/status"
      + "|token|register|revoke)|/\\.well-known/oauth-authorization-server|/\\.well-known/oauth-protected-resource"
      + "(/mcp/stage-readonly)?)$");

  private static TunnelWorld world;
  private static String host;

  @BeforeAll
  static void start() {
    world = TunnelWorld.start("isolation");
    world.endpoint("stage-readonly", Env.STAGE);
    world.endpoint("dev", Env.DEV);
    world.cloudflared().control(Map.of("mode", "ready", "hostname", "gw-iso-8f3k.trycloudflare.com"));
    world.publish("stage-readonly");
    host = world.host();
  }

  @AfterAll
  static void stop() {
    if (world != null) {
      world.close();
    }
  }

  /** Every request the core's routes stand for: method and concrete path. */
  private static Set<String> everyRoute() {
    Set<String> requests = new LinkedHashSet<>();
    Map<String, HandlerMapping> mappings = world.context().getBeansOfType(HandlerMapping.class);
    assertThat(mappings).as("the handler mappings of the dispatcher").isNotEmpty();
    int methods = 0;
    for (Map.Entry<String, HandlerMapping> m : mappings.entrySet()) {
      switch (m.getValue()) {
        case RequestMappingInfoHandlerMapping annotated -> {
          for (RequestMappingInfo info : annotated.getHandlerMethods().keySet()) {
            methods++;
            Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
              add(requests, pattern, verbs.isEmpty() ? List.of("GET", "POST") : verbs.stream().map(Enum::name)
                  .toList());
            }
          }
        }
        case AbstractUrlHandlerMapping urls -> {
          urls.getHandlerMap().keySet().forEach(pattern -> add(requests, pattern, List.of("GET", "POST")));
          if (urls.getRootHandler() != null) {
            add(requests, "/", List.of("GET", "POST"));
          }
        }
        case RouterFunctionMapping functions -> assertThat(functions.getRouterFunction())
            .as("router functions aren't walked: list their routes here first").isNull();
        default -> throw new AssertionError("A handler mapping this test can't list: " + m.getKey() + " ("
            + m.getValue().getClass().getName() + ")");
      }
    }
    assertThat(methods).as("the whole admin API is in the walk").isGreaterThan(100);
    // Servlets beside the dispatcher (none today) and the container's error page.
    jakarta.servlet.ServletContext servlets = ((org.springframework.web.context.WebApplicationContext) world.context())
        .getServletContext();
    servlets.getServletRegistrations().values().forEach(s -> s.getMappings().forEach(pattern -> add(requests,
        pattern.isEmpty() ? "/" : pattern, List.of("GET", "POST"))));
    add(requests, "/error", List.of("GET", "POST", "PUT", "DELETE"));
    return requests;
  }

  private static void add(Set<String> requests, String pattern, List<String> verbs) {
    for (String path : concrete(pattern)) {
      for (String verb : verbs) {
        requests.add(verb + " " + path);
      }
    }
  }

  @Test
  void everyMappingButTheAllowlistIs404OnThePublicPort() {
    Set<String> requests = everyRoute();
    List<String> leaked = new ArrayList<>();
    int allowed = 0;
    for (String request : requests) {
      String verb = request.substring(0, request.indexOf(' '));
      String path = request.substring(request.indexOf(' ') + 1);
      boolean body = verb.equals("POST") || verb.equals("PUT") || verb.equals("PATCH");
      TunnelWorld.Answer a = world.direct(world.publicPort(), host, verb, path, Map.of("CF-Connecting-IP",
          TunnelWorld.CLAUDE, "Content-Type", "application/json"), body ? "{}" : null);
      if (ALLOWED.matcher(path).matches()) {
        allowed++;
        continue;
      }
      if (a.status() != 404) {
        leaked.add(request + " → " + a.status());
      }
    }
    assertThat(leaked).as("served on the public port").isEmpty();
    assertThat(allowed).as("the allowlist itself was walked").isGreaterThanOrEqualTo(8);

    // The allowlist answers through the same door (none is a 404 for a mapping reason).
    assertThat(world.direct(world.publicPort(), host, "GET", "/.well-known/oauth-authorization-server",
        Map.of("CF-Connecting-IP", TunnelWorld.CLAUDE), null).status()).isEqualTo(200);
    assertThat(world.direct(world.publicPort(), host, "POST", "/mcp/stage-readonly", Map.of("CF-Connecting-IP",
        TunnelWorld.CLAUDE, "Content-Type", "application/json"), "{}").status()).isEqualTo(401);
  }

  @Test
  void theLocalPortRefusesTheTunnelsHostEverywhere() {
    List<String> leaked = new ArrayList<>();
    Set<String> paths = new LinkedHashSet<>();
    for (String request : everyRoute()) {
      paths.add(request.substring(request.indexOf(' ') + 1));
    }
    for (String path : paths) {
      TunnelWorld.Answer a = world.direct(world.localPort(), host, "GET", path, Map.of(), null);
      if (a.status() != 421) {
        leaked.add(path + " → " + a.status());
      }
    }
    assertThat(leaked).isEmpty();
  }

  /** The concrete paths a pattern stands for here: its variables as the published slug and as anything else. */
  private static List<String> concrete(String pattern) {
    String other = pattern.replaceAll("\\{\\*?[^}]+}", "x1").replace("**", "x1").replace("*", "x1");
    String published = pattern.replaceAll("\\{\\*?[^}]+}", "stage-readonly").replace("**", "x1").replace("*", "x1");
    return published.equals(other) ? List.of(other) : List.of(other, published);
  }
}
