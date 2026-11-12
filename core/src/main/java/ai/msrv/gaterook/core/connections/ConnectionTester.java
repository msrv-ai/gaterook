package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.connections.internal.ConnectionTargets;
import ai.msrv.gaterook.core.connections.internal.DraftSecrets;
import ai.msrv.gaterook.core.connections.internal.SecretFields;
import ai.msrv.gaterook.core.connections.internal.TestConfirmations;
import ai.msrv.gaterook.core.connections.internal.TestResults;
import ai.msrv.gaterook.core.connections.internal.TlsProbe;
import ai.msrv.gaterook.core.connectors.CallContext;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.JsonMergePatch;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection (ARCHITECTURE.md §6 connection-new): a draft before anything is saved, or a stored connection. The
 * connector's {@code test} runs on a virtual thread under a deadline, with the draft's secrets for this one request;
 * what it says is masked by the call's taint before anyone sees it. A test that needs the person's trust (host key,
 * certificate) stops and leaves a confirmation; "Trust" applies the connector's patch (Setup change {@code host-key})
 * and the form tests again.
 *
 * <p>A stored test also records the connection's health and the facts the connector found for {@code classify}
 * ({@code metadataUpdate}).
 */
@Component
public class ConnectionTester implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);

  /** A test's result, masked; {@code confirmationId} when it waits for "Trust". */
  public record Outcome(TestResult result, String confirmationId) {}

  /**
   * What "Trust" did: for a draft, its config with the patch ({@code config}); for a stored connection, the test that
   * ran again ({@code retest}).
   */
  public record ConfirmOutcome(String kind, JsonNode config, Outcome retest) {}

  private enum DraftOperation { TEST, DATABASES, FILES, PROJECTS }

  /**
   * How much of a draft must hold before it is used: nothing (local suggestions), discovery without the fields a
   * suggestion lists ({@code x-suggest}), discovery, or a test.
   */
  private enum DraftCheck { NONE, SUGGEST, DISCOVERY, PROJECTS, TEST }

  /** One use of a prepared draft: its config, the secrets for this request and the taint that masks them. */
  @FunctionalInterface
  private interface DraftUse<T> {
    T apply(ConnectorManifest manifest, ConnectionConfig cfg, DraftSecrets resolver, ScopedSecretResolver stores,
        Taint taint, ObjectNode draft);
  }

  /**
   * The deadline of local suggestions: they read files of this computer while the person types, and try the address of
   * a URL typed without a scheme ({@link TlsProbe}) for at most {@link #TLS_PROBE} twice.
   */
  private static final Duration LOCAL_SUGGESTIONS = Duration.ofSeconds(5);
  private static final Duration TLS_PROBE = Duration.ofMillis(1500);

  private final ConnectorRegistry registry;
  private final ConnectionConfigs configs;
  private final ConnectionService connections;
  private final SecretService secrets;
  private final CallContexts contexts;
  private final EnvironmentPolicies policies;
  private final Masker masker;
  private final EventBus events;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final TestConfirmations confirmations;
  private final Clock clock;
  private final Duration timeout;
  private final TransactionTemplate writeTx;
  private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("connection-test-", 0).factory());

  public ConnectionTester(ConnectorRegistry registry, ConnectionConfigs configs, ConnectionService connections,
      SecretService secrets, CallContexts contexts, EnvironmentPolicies policies, Masker masker, EventBus events,
      ObjectProvider<SetupChangeRecorder> setupChanges, Clock clock, PlatformTransactionManager transactions,
      @Value("${gateway.connections.test-timeout:15s}") Duration timeout) {
    this.registry = registry;
    this.configs = configs;
    this.connections = connections;
    this.secrets = secrets;
    this.contexts = contexts;
    this.policies = policies;
    this.masker = masker;
    this.events = events;
    this.setupChanges = setupChanges;
    this.confirmations = new TestConfirmations(clock);
    this.clock = clock;
    this.timeout = timeout;
    this.writeTx = new TransactionTemplate(transactions);
  }

  // ── Draft ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code POST /api/connections/test}: nothing is saved; the typed secret values live only in this request and are
   * zeroed. With {@code existing}, fields left empty use the stored secrets of that connection, which needs the
   * person's confirmation when the draft points them at another host.
   */
  public Outcome testDraft(String type, Env env, JsonNode config, Map<String, SecretInput> typed, String existing,
      String confirm) {
    return testDraft(type, env, config, typed, existing, confirm, DraftOperation.TEST, null);
  }

  /** Lists databases with the same secret handling, host checks and deadline as a draft test. */
  public Outcome discoverDatabases(String type, Env env, JsonNode config, Map<String, SecretInput> typed,
      String existing, String confirm) {
    return testDraft(type, env, config, typed, existing, confirm, DraftOperation.DATABASES, null);
  }

  /** Lists cloud projects with the same secret handling, host checks and deadline as a draft test. */
  public Outcome discoverProjects(String type, Env env, JsonNode config, Map<String, SecretInput> typed,
      String existing, String confirm) {
    return testDraft(type, env, config, typed, existing, confirm, DraftOperation.PROJECTS, null);
  }

  /** Browses remote files with the same secret handling, host checks and deadline as a draft test. */
  public Outcome browseFiles(String type, Env env, JsonNode config, Map<String, SecretInput> typed,
      String existing, String confirm, String path) {
    return testDraft(type, env, config, typed, existing, confirm, DraftOperation.FILES, path);
  }

  private Outcome testDraft(String type, Env env, JsonNode config, Map<String, SecretInput> typed, String existing,
      String confirm, DraftOperation operation, String path) {
    if (operation == DraftOperation.FILES && path != null
        && (path.length() > 4096 || !path.startsWith("/") || path.chars().anyMatch(c -> c < 32 || c == 127))) {
      clear(typed);
      throw ProblemException.unprocessable("Invalid folder", "Enter an absolute remote folder path.", "/path");
    }
    DraftCheck check = switch (operation) {
      case TEST -> DraftCheck.TEST;
      case PROJECTS -> DraftCheck.PROJECTS;
      case DATABASES, FILES -> DraftCheck.DISCOVERY;
    };
    return withDraft(type, env, config, typed, existing, confirm, check, true,
        (manifest, cfg, resolver, stores, taint, draft) -> {
          TestResult result = run(manifest, cfg, resolver, stores, taint, operation, path);
          // Trust applies to the draft: an edit saves the patched config with the rest of the form.
          return outcome(result, type, env, null, draft);
        });
  }

  /**
   * {@code POST /api/connections/suggest}: what the form can offer the person for this draft ({@link Suggestions}).
   * {@code LOCAL} needs no complete draft, reads no store and uses no stored secret: only what was typed, parsed on
   * this computer. {@code SERVICE} checks the draft as discovery does, except the fields the connector lists
   * ({@code x-suggest}), which may still be empty, and signs in with it, stored secrets included
   * (after the same new-host confirmation as a test). Masked by the request's taint: an option whose value a mask
   * would change is left out rather than offered changed.
   */
  public Suggestions suggest(String type, Env env, JsonNode config, Map<String, SecretInput> typed, String existing,
      String confirm, Suggestions.Scope scope) {
    boolean local = scope == Suggestions.Scope.LOCAL;
    return withDraft(type, env, config, typed, existing, confirm, local ? DraftCheck.NONE : DraftCheck.SUGGEST,
        !local, (manifest, cfg, resolver, stores, taint, draft) -> suggestions(manifest, cfg, resolver, stores, taint,
            scope));
  }

  /**
   * Prepares a draft for one request: checks it as {@code check} says, resolves typed secrets (values copied here,
   * references read through their store for this request) and, for an edit when {@code useStored}, the stored
   * secrets of the fields left empty. Typed values are zeroed afterwards whatever happens.
   */
  private <T> T withDraft(String type, Env env, JsonNode config, Map<String, SecretInput> typed, String existing,
      String confirm, DraftCheck check, boolean useStored, DraftUse<T> use) {
    Map<String, char[]> values = new HashMap<>();
    Map<String, SecretService.DraftReference> references = new HashMap<>();
    try {
      ConnectorManifest manifest = usable(type);
      if (config == null || !config.isObject() || SecretFields.mentionsPlaceholder(config)) {
        throw ProblemException.unprocessable("Invalid settings", "The settings must be an object without stored "
            + "secrets.", "/config");
      }
      ObjectNode draft = (ObjectNode) config.deepCopy();
      java.util.List<String> templates = SecretFields.templates(manifest.configSchema());
      ConnectionConfigs.Stored stored = existing == null ? null : configs.byName(existing).orElseThrow(() ->
          ProblemException.notFound("Connection not found", "There is no connection " + existing + "."));
      ConnectionService.checkSecrets(manifest.configSchema(), templates, typed)
          .forEach((field, input) -> {
            if (input.isLocal()) {
              values.put(field, input.value().clone());
            } else if (useStored) {
              // Read through its store for this one request (dynamic: leased and revoked when it ends).
              references.put(field, new SecretService.DraftReference(input.reference(), input.dynamic(),
                  input.store()));
            }
          });
      Set<String> present = new LinkedHashSet<>(values.keySet());
      present.addAll(references.keySet());
      Set<String> dynamic = new LinkedHashSet<>();
      references.forEach((field, reference) -> { if (reference.dynamic()) dynamic.add(field); });
      Set<String> secretFields = new LinkedHashSet<>(present);
      for (String t : templates) {
        if (!t.contains("*")) {
          secretFields.add(t);
        }
      }
      if (stored != null) {
        if (!stored.config().type().equals(type)) {
          throw ProblemException.unprocessable("Another type", existing + " is not a " + manifest.name()
              + " connection.", "/type");
        }
        if (!stored.verified()) {
          throw ProblemException.conflict("Changed outside GateRook", existing + " was changed outside "
              + "GateRook. Review it first.");
        }
        if (useStored) {
          boolean usesStored = stored.secretIds().keySet().stream().anyMatch(f -> !values.containsKey(f)
              && !references.containsKey(f));
          if (usesStored && !ConnectionService.confirmed(confirm, ConnectionService.CONFIRM_NEW_HOST)) {
            ConnectionTargets.changedTarget(stored.config().config(), draft).ifPresent(target -> {
              throw ProblemException.needsConfirmation("Send the stored secret to a new host?",
                  "Test sends the stored credentials of " + existing + " to " + masker.url(target) + ". Confirm, "
                      + "or enter new values.", ConnectionService.CONFIRM_NEW_HOST);
            });
          }
          for (String field : connections.dynamicSecretFields(stored.secretIds())) {
            if (!present.contains(field)) dynamic.add(field);
          }
          present.addAll(stored.secretIds().keySet());
          secretFields.addAll(stored.secretIds().keySet());
        }
      }
      switch (check) {
        case NONE -> { }
        case SUGGEST -> connections.validateSuggestion(manifest, draft, present, dynamic, env);
        case PROJECTS -> connections.validateProjectDiscovery(manifest, draft, present, dynamic, env);
        case DISCOVERY -> connections.validate(manifest, draft, present, dynamic, env, true);
        case TEST -> connections.validate(manifest, draft, present, dynamic, env, false);
      }
      String id = stored == null ? "draft-" + Ids.uuid() : stored.id();
      ConnectionConfig cfg = new ConnectionConfig(id, stored == null ? "draft" : stored.name(), type, env,
          stored == null ? 0 : stored.config().version(), draft.deepCopy(), Set.copyOf(secretFields),
          stored == null ? null : stored.config().metadata());
      Taint taint = new Taint();
      // Typed values first; then references typed into the form; then the stored secrets of the edited connection.
      ScopedSecretResolver fallback = !useStored || (stored == null && references.isEmpty()) ? null
          : secrets.draftResolver(cfg, references, taint);
      try (DraftSecrets resolver = new DraftSecrets(secretFields, values, fallback, taint)) {
        return use.apply(manifest, cfg, resolver, fallback, taint, draft);
      } finally {
        if (stored == null) {
          // Each draft request has an id of its own: what a connector keeps per connection for it is never used again.
          registry.connectorFor(cfg).ifPresent(connector -> forget(connector, cfg));
        }
      }
    } finally {
      values.values().forEach(v -> java.util.Arrays.fill(v, '\0'));
      clear(typed);
    }
  }

  /** Drops a draft's pools and clients; a connector that fails to is logged, never the answer's problem. */
  private void forget(Connector connector, ConnectionConfig cfg) {
    try {
      connector.onConnectionRemoved(cfg.id());
    } catch (RuntimeException e) {
      log.warn("{} connector couldn't drop the draft {}: {}", cfg.type(), cfg.id(), masker.log(String.valueOf(e)));
    }
  }

  private static void clear(Map<String, SecretInput> typed) {
    if (typed != null) {
      typed.values().forEach(i -> {
        if (i != null) {
          i.clear();
        }
      });
    }
  }

  /**
   * Runs {@code suggest} under its deadline (a few seconds for local suggestions) and masks what it says. A failure
   * of the connector is a note, never an error of the request: the form simply offers nothing new.
   */
  private Suggestions suggestions(ConnectorManifest manifest, ConnectionConfig cfg, SecretResolver resolver,
      ScopedSecretResolver stores, Taint taint, Suggestions.Scope scope) {
    Connector connector = registry.connectorFor(cfg).orElseThrow(() -> ProblemException.unprocessable(
        "Unknown connector", "There is no connector type " + cfg.type() + ".", "/type"));
    Duration deadline = scope == Suggestions.Scope.LOCAL && LOCAL_SUGGESTIONS.compareTo(timeout) < 0
        ? LOCAL_SUGGESTIONS : timeout;
    CallContext ctx = contexts.builder(Ids.next("sug_"), cfg, policies.limits(cfg.env()))
        .timeout(deadline).taint(taint).build();
    Future<Suggestions> future = executor.submit(() -> {
      ConnectionConfig call;
      try {
        call = ConnectionTexts.fill(cfg, manifest, stores);
      } catch (SecretUnavailableException e) {
        // A text field read from a store can't be read now: suggest from what the form says.
        call = cfg;
      }
      Suggestions own = connector.suggest(call, resolver, ctx, scope);
      if (scope != Suggestions.Scope.LOCAL) {
        return own;
      }
      // Auto for http:// or https://: an address typed without a scheme is tried once, nothing but a TLS hello sent.
      Suggestions schemes = TlsProbe.schemes(manifest.configSchema(), call.config(),
          (host, port) -> TlsProbe.probe(host, port, TLS_PROBE));
      return own == null ? schemes : own.merge(schemes);
    });
    Suggestions result;
    try {
      result = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      ctx.cancel();
      future.cancel(true);
      result = Suggestions.builder().note("No answer within " + deadline.toSeconds() + " s: nothing to suggest yet.")
          .build();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ConnectorException failed) {
        result = Suggestions.builder().note(failed.getMessage()).build();
      } else {
        log.warn("Suggestions of {} connector failed: {}", manifest.id(), masker.log(String.valueOf(e.getCause())));
        result = Suggestions.none();
      }
    } catch (InterruptedException e) {
      ctx.cancel();
      future.cancel(true);
      Thread.currentThread().interrupt();
      result = Suggestions.none();
    }
    return TestResults.maskSuggestions(result == null ? Suggestions.none() : result, masker, taint);
  }

  // ── Stored ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/connections/{name}/test}: records health and metadata, publishes {@code connection.health}. */
  public Outcome testStored(String name) {
    ConnectionConfigs.Stored stored = configs.byName(name).orElseThrow(() -> ProblemException.notFound(
        "Connection not found", "There is no connection " + name + "."));
    return testStored(stored, null, 0);
  }

  /** The same with {@code field} at {@code version} (Replace with "test before"); records nothing. */
  public Outcome testWithVersion(String connectionId, String field, int version) {
    ConnectionConfigs.Stored stored = configs.byId(connectionId).orElseThrow(() -> ProblemException.notFound(
        "Connection not found", "The connection was deleted."));
    return testStored(stored, field, version);
  }

  Outcome testStored(ConnectionConfigs.Stored stored, String field, int version) {
    return testStored(stored, field, version, true);
  }

  /** Optional background discovery must not replace the result of the connection's bounded health probe. */
  void learnFacts(ConnectionConfigs.Stored stored) {
    testStored(stored, null, 0, false);
  }

  private Outcome testStored(ConnectionConfigs.Stored stored, String field, int version, boolean recordHealth) {
    if (!stored.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", stored.name() + " was changed outside "
          + "GateRook. Review it first.");
    }
    if (ConnectionService.needsSetup(stored.config())) {
      throw ProblemException.conflict("Needs setup", "Complete " + stored.name()
          + " in the connection editor before testing it.");
    }
    ConnectorManifest manifest = usable(stored.config().type());
    Taint taint = new Taint();
    TestResult result;
    try (var resolver = field == null ? secrets.resolver(stored.config(), taint)
        : secrets.resolverWithVersion(stored.config(), taint, field, version)) {
      result = run(manifest, stored.config(), resolver, taint);
    }
    if (field == null) {
      if (recordHealth) {
        record(stored, result);
      } else {
        recordMetadata(stored, result);
      }
    }
    return outcome(result, stored.config().type(), stored.config().env(), stored.id(), null);
  }

  /** Health and facts of a finished stored test. */
  void record(ConnectionConfigs.Stored stored, TestResult result) {
    if (result.needsConfirmation() != null) {
      return;
    }
    HealthResult health = result.ok() ? HealthResult.healthy(result.latencyMs())
        : HealthResult.unreachable(result.error() == null ? "Unreachable" : result.error().message());
    connections.recordHealth(stored.id(), health.state(), result.ok() ? health.latencyMs() : null, health.message());
    publishHealth(stored.name(), stored.config().env(), health);
    recordMetadata(stored, result);
  }

  private void recordMetadata(ConnectionConfigs.Stored stored, TestResult result) {
    if (result.ok() && result.metadataUpdate() != null && !result.metadataUpdate().isNull()) {
      // Only onto the version this test checked: an edit made while it ran points elsewhere (review API #9).
      connections.recordMetadata(stored.id(), result.metadataUpdate(), stored.config().version());
    }
  }

  void publishHealth(String name, Env env, HealthResult health) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("name", name);
    data.put("env", env.id());
    data.put("state", health.state().name().toLowerCase(java.util.Locale.ROOT));
    if (health.state() != HealthResult.State.UNREACHABLE) {
      data.put("latencyMs", health.latencyMs());
    }
    data.put("message", health.message());
    data.put("checkedAt", clock.instant().toString());
    events.publish("connection.health", data);
  }

  // ── Trust ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST …/test/confirm {confirmationId}}: the person trusts what the test showed. */
  public ConfirmOutcome confirm(String confirmationId, String reason) {
    TestConfirmations.Pending p = confirmations.take(confirmationId).orElseThrow(() -> ProblemException.notFound(
        "Nothing to confirm", "This confirmation expired or was used. Test the connection again."));
    if (p.connectionId() != null) {
      connections.applyTrustedPatch(p.connectionId(), p.configPatch(), p.kind(), p.text(), reason);
      ConnectionConfigs.Stored stored = configs.byId(p.connectionId()).orElseThrow();
      return new ConfirmOutcome(p.kind(), null, testStored(stored, null, 0));
    }
    JsonNode patched = JsonMergePatch.apply(p.draftConfig(), p.configPatch());
    // A draft has no row yet: the trust is recorded now, the patched config is saved with the connection.
    writeTx.executeWithoutResult(s -> setupChanges.getObject().record(new SetupChange(
        SetupChangeRecorder.What.HOST_KEY, "Trusted " + p.kind() + " for a new " + p.type() + " connection", null,
        p.text(), reason, p.env(), null, null, p.configPatch())));
    return new ConfirmOutcome(p.kind(), patched, null);
  }

  // ── Running ─────────────────────────────────────────────────────────────────────────────────────────────────

  private Outcome outcome(TestResult result, String type, Env env, String connectionId, JsonNode draft) {
    TestResult.Confirmation c = result.needsConfirmation();
    if (c == null) {
      return new Outcome(result, null);
    }
    TestConfirmations.Pending p = confirmations.add(c.kind(), c.text(), c.data(), c.configPatch(), type, env,
        connectionId, draft);
    return new Outcome(result, p.id());
  }

  /** Runs {@code test} under the deadline and masks what it says. */
  TestResult run(ConnectorManifest manifest, ConnectionConfig cfg, SecretResolver resolver, Taint taint) {
    return run(manifest, cfg, resolver, resolver instanceof ScopedSecretResolver scoped ? scoped : null, taint,
        DraftOperation.TEST, null);
  }

  /**
   * @param stores the resolver that reads the text fields kept in a store ({@link ConnectionTexts}), or {@code null}
   *     when none can be
   */
  private TestResult run(ConnectorManifest manifest, ConnectionConfig cfg, SecretResolver resolver,
      ScopedSecretResolver stores, Taint taint, DraftOperation operation, String path) {
    Connector connector = registry.connectorFor(cfg).orElseThrow(() -> ProblemException.unprocessable(
        "Unknown connector", "There is no connector type " + cfg.type() + ".", "/type"));
    CallContext ctx = contexts.builder(Ids.next("tst_"), cfg, policies.limits(cfg.env()))
        .timeout(timeout).taint(taint).build();
    Future<TestResult> future = executor.submit(() -> {
      ConnectionConfig call;
      try {
        call = ConnectionTexts.fill(cfg, manifest, stores);
      } catch (SecretUnavailableException e) {
        return TestResult.failure("secret", e.getMessage(), null);
      }
      return switch (operation) {
        case TEST -> connector.test(call, resolver, ctx);
        case DATABASES -> connector.discoverDatabases(call, resolver, ctx);
        case FILES -> connector.browseFiles(call, resolver, ctx, path);
        case PROJECTS -> connector.discoverProjects(call, resolver, ctx);
      };
    });
    TestResult result;
    try {
      result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (result == null) {
        result = TestResult.failure("internal", "The connector gave no answer. See core.log.", null);
      }
    } catch (TimeoutException e) {
      ctx.cancel();
      future.cancel(true);
      result = TestResult.failure("timeout", "No answer within " + timeout.toSeconds() + " s. Check the host, the "
          + "port and the VPN, then test again.", null);
    } catch (ExecutionException e) {
      log.warn("Test of {} connection {} failed in the connector: {}", manifest.id(), cfg.name(),
          masker.log(String.valueOf(e.getCause())));
      result = TestResult.failure("internal", "The test failed inside the " + manifest.name() + " connector. See "
          + "core.log.", null);
    } catch (InterruptedException e) {
      ctx.cancel();
      future.cancel(true);
      Thread.currentThread().interrupt();
      result = TestResult.failure("cancelled", "The test was cancelled.", null);
    }
    return operation == DraftOperation.DATABASES ? TestResults.maskDatabaseDiscovery(result, masker, taint)
        : TestResults.mask(result, masker, taint);
  }

  /**
   * Why {@code field} of the connection can't be rotated at the source, or empty when its connector can
   * ({@link SecretResolver.CredentialRotator}).
   */
  public Optional<String> rotateUnavailable(String connectionId, String field) {
    // Asked for each row of Secrets, also while the connection is being created: a peek, the rotation verifies.
    Optional<ConnectionConfig> peeked = configs.peek(connectionId);
    if (peeked.isEmpty()) {
      return Optional.of("The connection was deleted.");
    }
    ConnectionConfig cfg = peeked.get();
    Optional<Connector> connector = registry.connectorFor(cfg);
    if (connector.isEmpty() || !(connector.get() instanceof SecretResolver.CredentialRotator rotator)) {
      return Optional.of("This connector can't issue a new value for itself. Replace it with a new value.");
    }
    if (!rotator.canRotate(cfg, field)) {
      return Optional.of("Only " + cfg.name() + "'s own sign-in can rotate at the source. Replace this one with a new "
          + "value.");
    }
    return Optional.empty();
  }

  /**
   * Rotate at the source: the connector signs in with the current value of {@code field} and makes {@code next} the
   * value there, within the test's deadline. {@code next} is a taint of the call, so no answer repeats it.
   *
   * @throws ProblemException 409 when it can't rotate or the source refused, with the connector's words
   * @throws SecretOwners.RotationOutcomeUnknown when no clear answer came (no answer within the deadline, the
   *     connector reports the outcome unknown, a timeout or an internal failure, a cancel): the source may use
   *     {@code next}
   */
  public void rotateAtSource(String connectionId, String field, char[] next) {
    ConnectionConfigs.Stored stored = configs.byId(connectionId).orElseThrow(() -> ProblemException.notFound(
        "Connection not found", "The connection was deleted."));
    if (!stored.verified()) {
      throw ProblemException.conflict("Changed outside GateRook", stored.name() + " was changed outside "
          + "GateRook. Review it first.");
    }
    ConnectorManifest manifest = usable(stored.config().type());
    ConnectionConfig cfg = stored.config();
    Optional<String> unavailable = rotateUnavailable(connectionId, field);
    if (unavailable.isPresent()) {
      throw ProblemException.conflict("Can't rotate", unavailable.get());
    }
    SecretResolver.CredentialRotator rotator = (SecretResolver.CredentialRotator) registry.connectorFor(cfg)
        .orElseThrow();
    Taint taint = new Taint();
    taint.add(CharBuffer.wrap(next));
    CallContext ctx = contexts.builder(Ids.next("rot_"), cfg, policies.limits(cfg.env())).timeout(timeout)
        .taint(taint).build();
    try (var resolver = secrets.resolver(cfg, taint)) {
      Future<?> future = executor.submit(() -> {
        rotator.rotate(ConnectionTexts.fill(cfg, manifest, resolver), resolver, field, next, ctx);
        return null;
      });
      // Only an answer of the source, or a refusal before anything was sent, means it kept the current value. No
      // answer in time, a dropped connection while the change ran, a failure inside the connector or a cancel leave
      // the outcome unknown: the source may already use the new value (SecretService keeps it then).
      try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        ctx.cancel();
        future.cancel(true);
        throw new SecretOwners.RotationOutcomeUnknown("No answer from " + cfg.name() + " within "
            + timeout.toSeconds() + " s.");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof ConnectorException failed) {
          String text = masker.text(String.valueOf(failed.getMessage()), taint, MaskPolicy.SECRETS_ONLY).text();
          ConnectorException.Kind kind = failed.kind();
          if (kind == ConnectorException.Kind.OUTCOME_UNKNOWN || kind == ConnectorException.Kind.TIMEOUT
              || kind == ConnectorException.Kind.INTERNAL) {
            throw new SecretOwners.RotationOutcomeUnknown(text);
          }
          throw ProblemException.conflict("The source refused", text + " The current value stays in use.");
        }
        log.warn("Rotation of {} connection {} failed in the connector: {}", manifest.id(), cfg.name(),
            masker.log(String.valueOf(e.getCause())));
        throw new SecretOwners.RotationOutcomeUnknown("The rotation failed inside the " + manifest.name()
            + " connector. See core.log.");
      } catch (InterruptedException e) {
        ctx.cancel();
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw new SecretOwners.RotationOutcomeUnknown("The rotation was cancelled before " + cfg.name()
            + " answered.");
      }
    }
  }

  private ConnectorManifest usable(String type) {
    registry.awaitInstalled(type);
    var t = registry.type(type == null ? "" : type).orElseThrow(() -> ProblemException.unprocessable(
        "Unknown connector", "There is no connector type " + type + ".", "/type"));
    if (!t.usable()) {
      throw ProblemException.unprocessable("Connector off", t.manifest().name() + " is switched off or was changed "
          + "outside GateRook.", "/type");
    }
    return t.manifest();
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
