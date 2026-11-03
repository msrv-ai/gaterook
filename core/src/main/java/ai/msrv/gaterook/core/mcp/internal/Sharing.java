package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.policy.PairView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The connections of an endpoint that share what only they can list — the resources and prompts of an external MCP
 * server whose person let it share them (ARCHITECTURE.md §5.4) — and the pair each is decided through. Such a pair is
 * a READ of the connection on the endpoint, in Read only: the person's choice to share is the confirmation, and the
 * policy still decides it (a policy that didn't verify, a prod connection outside a prod endpoint, a read-only
 * endpoint with a mutation). A connection that is unusable or paused by the user shares nothing.
 */
final class Sharing {

  /** How long a list of every sharing connection waits for the slowest one: the others are listed regardless. */
  static final Duration LIST_WAIT = Duration.ofSeconds(15);

  private static final ExecutorService LISTS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
      .name("mcp-share-", 0).factory());

  /** What is shared. */
  enum What { RESOURCES, PROMPTS }

  /** One sharing connection of the endpoint. */
  record Shared(EndpointSnapshot.Wired wired, Connector connector, PairView view) {

    ConnectionConfig config() {
      return wired.config();
    }

    String name() {
      return wired.config().name();
    }
  }

  private Sharing() {}

  /** Every connection of {@code s} that shares {@code what}, in the endpoint's order. */
  static List<Shared> of(EndpointSnapshot s, ConnectorRegistry registry, What what) {
    List<Shared> out = new ArrayList<>();
    if (s.blocked() || !s.enabled()) {
      return out;
    }
    for (EndpointSnapshot.Wired w : s.connections()) {
      if (!w.usable() || !w.enabled()) {
        continue;
      }
      Optional<Connector> connector = registry.connectorFor(w.config());
      if (connector.isEmpty()) {
        continue;
      }
      boolean shares;
      try {
        shares = what == What.RESOURCES ? connector.get().sharesResources(w.config())
            : connector.get().sharesPrompts(w.config());
      } catch (RuntimeException e) {
        shares = false;
      }
      if (shares) {
        out.add(new Shared(w, connector.get(), view(s, w, what)));
      }
    }
    return out;
  }

  /** The sharing connection whose namespace {@code uri} is under, if it shares resources. */
  static Optional<Shared> forUri(EndpointSnapshot s, ConnectorRegistry registry, String uri) {
    return of(s, registry, What.RESOURCES).stream().filter(x -> uri.startsWith(ResourceDescriptor.prefix(x.name()))
        || uri.startsWith(ResourceDescriptor.appPrefix(x.name()))).findFirst();
  }

  /** The READ pair of a sharing connection: what the policy decides its lists and reads by. */
  static PairView view(EndpointSnapshot s, EndpointSnapshot.Wired w, What what) {
    String tool = what == What.RESOURCES ? "resources_read" : "prompts_get";
    ToolDefinition read = ToolDefinition.builder(tool).build();
    return new PairView(s.id(), s.slug(), s.env(), s.readOnly(), w.config().id(), w.config().name(),
        w.config().env(), w.manifest(), read, "share:" + what.name().toLowerCase(java.util.Locale.ROOT) + ":"
            + w.config().name(), Mode.READ_ONLY, s.settings().path("limits"), null);
  }

  /** The READ pair an app of {@code tool} is read through: its HTML, not a call of the tool. */
  static PairView appView(EndpointSnapshot s, EndpointSnapshot.Wired w, String tool) {
    return new PairView(s.id(), s.slug(), s.env(), s.readOnly(), w.config().id(), w.config().name(),
        w.config().env(), w.manifest(), ToolDefinition.builder("resources_read").build(), "app:" + w.config().name()
            + ":" + tool, Mode.READ_ONLY, s.settings().path("limits"), null);
  }

  /**
   * {@code task} for every connection at once, each answer as it comes: what doesn't answer within
   * {@link #LIST_WAIT} is left out of a list, never holds it up.
   */
  static <T> List<T> each(List<Shared> shared, Function<Shared, T> task) {
    List<CompletableFuture<T>> running = new ArrayList<>();
    for (Shared x : shared) {
      running.add(CompletableFuture.supplyAsync(() -> task.apply(x), LISTS));
    }
    long deadline = System.nanoTime() + LIST_WAIT.toNanos();
    List<T> out = new ArrayList<>();
    for (CompletableFuture<T> f : running) {
      try {
        T value = f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (value != null) {
          out.add(value);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        // A connection that failed or is still answering is left out of this list.
      }
    }
    return out;
  }
}
