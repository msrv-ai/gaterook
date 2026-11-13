package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.egress.EgressClients;
import com.gaterook.core.connectors.CallContext;
import com.gaterook.core.connectors.EnvironmentPolicies;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.MemoryPipe;
import com.gaterook.plugin.protocol.RpcException;
import com.gaterook.plugin.protocol.host.ConnectorEndpoint;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A plugin's setup help through {@code connector/suggest}: its answer crosses GPP/1 with a test's secrets and context,
 * bounded and shown as a classification's words are; a local suggestion never starts the plugin's host; a host from
 * before the method offers nothing; the plugin's own refusal is a connector error the form shows as a note.
 */
class RemoteConnectorSuggestTest {

  private static final Limits LIMITS = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofDays(7));
  private static final ExecContext.HandleCodec NO_HANDLES = new ExecContext.HandleCodec() {
    @Override
    public String seal(String purpose, JsonNode payload, Duration ttl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JsonNode> open(String purpose, String handle) {
      return Optional.empty();
    }
  };
  private static final ConnectorManifest MANIFEST = ConnectorManifest.builder("fixture", "1.0.0",
      ConnectorManifest.Kind.PLUGIN).name("Fixture").build();
  private static final ConnectionConfig CFG = new ConnectionConfig("draft-1", "draft", "fixture", Env.DEV, 0,
      JsonMapper.builder().build().readTree("{\"host\":\"ch.example.test\"}"), Set.of("/password"), null);

  @TempDir
  Path tmp;

  private final List<Suggestions.Scope> asked = new CopyOnWriteArrayList<>();
  private final List<AutoCloseable> peers = new ArrayList<>();
  private volatile Suggestions answer = Suggestions.none();
  private volatile ConnectorException refusal;

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable c : peers) {
      c.close();
    }
  }

  /** The plugin's connector: it answers what the test set and records the scopes it was asked in. */
  private Connector plugin() {
    return new Connector() {
      @Override
      public ConnectorManifest manifest() {
        return MANIFEST;
      }

      @Override
      public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
        return Classification.read();
      }

      @Override
      public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
        throw new UnsupportedOperationException();
      }

      @Override
      public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        return TestResult.success(1, null, null, null);
      }

      @Override
      public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
          Suggestions.Scope scope) throws ConnectorException {
        asked.add(scope);
        if (refusal != null) {
          throw refusal;
        }
        return answer;
      }
    };
  }

  /** A host over in-memory pipes that runs connector/* through the shared endpoint, or doesn't know suggest. */
  private HostProcess host(boolean knowsSuggest) {
    MemoryPipe coreToHost = new MemoryPipe();
    MemoryPipe hostToCore = new MemoryPipe();
    ConnectorEndpoint.Loaded loaded = new ConnectorEndpoint.Loaded("fixture@1.0.0", plugin(), tmp);
    JsonRpcPeer[] hostPeer = new JsonRpcPeer[1];
    ConnectorEndpoint endpoint = new ConnectorEndpoint(() -> hostPeer[0], new ConnectorEndpoint.Generations() {
      @Override
      public Optional<ConnectorEndpoint.Loaded> find(String generation) {
        return generation.equals(loaded.generation()) ? Optional.of(loaded) : Optional.empty();
      }

      @Override
      public Collection<ConnectorEndpoint.Loaded> all() {
        return List.of(loaded);
      }
    }, null, Clock.systemUTC(), () -> System.Logger.Level.INFO);
    hostPeer[0] = new JsonRpcPeer(coreToHost.in(), hostToCore.out(), (method, params, call) -> {
      if (!knowsSuggest && Gpp.CONNECTOR_SUGGEST.equals(method)) {
        throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
      }
      return endpoint.handle(method, params, call);
    }, JsonRpcPeer.Options.defaults().name("plugin").hostIds(true)).start();
    JsonRpcPeer core = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), (method, params, call) -> null,
        JsonRpcPeer.Options.defaults().name("core")).start();
    peers.add(hostPeer[0]);
    peers.add(core);
    Process process = mock(Process.class);
    when(process.isAlive()).thenReturn(true);
    when(process.descendants()).thenAnswer(invocation -> Stream.empty());
    when(process.onExit()).thenReturn(new CompletableFuture<>());
    HostProcess host = new HostProcess("fixture@1.0.0", 1, process, core,
        new PluginLog(tmp.resolve("plugin.log"), Clock.systemUTC()), Instant.now());
    host.state(HostProcess.State.RUNNING);
    return host;
  }

  private record Plugin(RemoteConnector remote, HostLauncher launcher) {}

  private Plugin plugin(boolean knowsSuggest) throws Exception {
    HostProcess host = host(knowsSuggest);
    HostLauncher launcher = mock(HostLauncher.class);
    when(launcher.start(any(), any())).thenReturn(host);
    PluginSupervisor supervisor = new PluginSupervisor("fixture", launcher, Clock.systemUTC());
    supervisor.spec(new HostLauncher.Spec("fixture", "1.0.0", tmp.resolve("fixture.jar"), "00", 64, Map.of(),
        List.of()));
    EnvironmentPolicies policies = mock(EnvironmentPolicies.class);
    when(policies.allowedPrivateRanges(any(), any())).thenReturn(List.of());
    EgressClients egress = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(7465), null));
    return new Plugin(new RemoteConnector(MANIFEST, supervisor, new SecretCallbackRegistry(), policies, egress, true),
        launcher);
  }

  private static CallContext ctx() {
    return CallContext.builder("sug_1", Env.DEV, LIMITS, Clock.systemUTC()).timeout(Duration.ofSeconds(5))
        .handles(NO_HANDLES).build();
  }

  @Test
  void aPluginsSuggestionsCrossTheWireAndALocalOneNeverStartsItsHost() throws Exception {
    answer = Suggestions.builder()
        .value("/database", "the server", "analytics", List.of(Option.of("analytics", "12 tables"),
            Option.of("bad‮one")))
        .note("Signed in as gw_reader\u0007.")
        .build();
    Plugin p = plugin(true);

    // Nothing runs yet: a local suggestion doesn't start a JVM while the person types.
    assertThat(p.remote().suggest(CFG, null, ctx(), Suggestions.Scope.LOCAL).isEmpty()).isTrue();
    verify(p.launcher(), never()).start(any(), any());
    assertThat(asked).isEmpty();

    Suggestions s = p.remote().suggest(CFG, null, ctx(), Suggestions.Scope.SERVICE);

    Suggestions.Field database = s.fields().get("/database");
    assertThat(database.value()).isEqualTo("analytics");
    assertThat(database.source()).isEqualTo("the server");
    // A value with an invisible character isn't offered: the option picked is what fills the field.
    assertThat(database.options()).extracting(Option::value).containsExactly("analytics");
    assertThat(database.options().getFirst().detail()).isEqualTo("12 tables");
    assertThat(database.complete()).isFalse();
    // The plugin's words are shown with their control characters visible.
    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).startsWith("Signed in as gw_reader")
        .doesNotContain("\u0007"));

    // Once the host runs, local suggestions reach it too.
    p.remote().suggest(CFG, null, ctx(), Suggestions.Scope.LOCAL);
    assertThat(asked).containsExactly(Suggestions.Scope.SERVICE, Suggestions.Scope.LOCAL);
  }

  @Test
  void aHostFromBeforeTheMethodOffersNothing() throws Exception {
    answer = Suggestions.builder().note("never sent").build();
    Plugin p = plugin(false);

    Suggestions s = p.remote().suggest(CFG, null, ctx(), Suggestions.Scope.SERVICE);

    assertThat(s.isEmpty()).isTrue();
    assertThat(asked).isEmpty();
  }

  @Test
  void thePluginsRefusalIsAConnectorErrorOfItsKind() throws Exception {
    refusal = ConnectorException.unavailable("ClickHouse can't be reached at ch.example.test:8443.", null);
    Plugin p = plugin(true);

    assertThatThrownBy(() -> p.remote().suggest(CFG, null, ctx(), Suggestions.Scope.SERVICE))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).isEqualTo("ClickHouse can't be reached at ch.example.test:8443.");
        });
  }

  @Test
  void onlyJsonPointersAndCleanValuesAreKept() {
    Suggestions said = Suggestions.builder()
        .options("database", "the server", List.of(Option.of("analytics")))
        .options("/user", "the server", List.of(Option.of("x".repeat(RemoteConnector.MAX_SUGGESTED_VALUE + 1))))
        .value("/port", "the server", "8123", List.of())
        .build();

    Suggestions shown = RemoteConnector.fromPlugin(said);

    assertThat(shown.fields().keySet()).containsExactly("/port");
    assertThat(shown.fields().get("/port").value()).isEqualTo("8123");
  }
}
