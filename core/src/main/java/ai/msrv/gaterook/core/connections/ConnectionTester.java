package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.connections.internal.ConnectionTargets;
import ai.msrv.gaterook.core.connections.internal.DraftSecrets;
import ai.msrv.gaterook.core.connections.internal.SecretFields;
import ai.msrv.gaterook.core.connections.internal.TestConfirmations;
import ai.msrv.gaterook.core.connections.internal.TestResults;
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
      if (typed != null) {
        typed.forEach((field, input) -> {
          if (!SecretFields.matches(templates, field)) {
            throw ProblemException.unprocessable("Not a secret field", field + " is not a secret field of this "
                + "connector.", "/secrets");
          }
          if (input == null || (!input.isLocal() && (input.reference() == null || input.reference().isBlank()))) {
            throw ProblemException.unprocessable("Secret missing", "Enter a value for " + field + ".", "/secrets");
          }
          if (input.isLocal()) {
            values.put(field, input.value().clone());
          } else {
            // Read through its store for this one test (dynamic: leased and revoked when the test ends).
            references.put(field, new SecretService.DraftReference(input.reference(), input.dynamic(),
                input.store()));
          }
        });
      }
      ConnectionConfigs.Stored stored = existing == null ? null : configs.byName(existing).orElseThrow(() ->
          ProblemException.notFound("Connection not found", "There is no connection " + existing + "."));
      Set<String> present = new LinkedHashSet<>(values.keySet());
      present.addAll(references.keySet());
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
          throw ProblemException.conflict("Changed outside MCP Gateway", existing + " was changed outside MCP "
              + "Gateway. Review it first.");
        }
        boolean usesStored = stored.secretIds().keySet().stream().anyMatch(f -> !values.containsKey(f)
            && !references.containsKey(f));
        if (usesStored && !ConnectionService.confirmed(confirm, ConnectionService.CONFIRM_NEW_HOST)) {
          ConnectionTargets.changedTarget(stored.config().config(), draft).ifPresent(target -> {
            throw ProblemException.needsConfirmation("Send the stored secret to a new host?",
                "Test sends the stored credentials of " + existing + " to " + target + ". Confirm, or enter new "
                    + "values.", ConnectionService.CONFIRM_NEW_HOST);
          });
        }
        present.addAll(stored.secretIds().keySet());
        secretFields.addAll(stored.secretIds().keySet());
      }
      connections.validate(manifest, draft, present, env);
      String id = stored == null ? "draft-" + Ids.uuid() : stored.id();
      ConnectionConfig cfg = new ConnectionConfig(id, stored == null ? "draft" : stored.name(), type, env,
          stored == null ? 0 : stored.config().version(), draft.deepCopy(), Set.copyOf(secretFields),
          stored == null ? null : stored.config().metadata());
      Taint taint = new Taint();
      // Typed values first; then references typed into the form; then the stored secrets of the edited connection.
      SecretResolver fallback = stored == null && references.isEmpty() ? null
          : secrets.draftResolver(cfg, references, taint);
      try (DraftSecrets resolver = new DraftSecrets(secretFields, values, fallback, taint)) {
        TestResult result = run(manifest, cfg, resolver, taint);
        // Trust applies to the draft: an edit saves the patched config with the rest of the form.
        return outcome(result, type, env, null, draft);
      }
    } finally {
      values.values().forEach(v -> java.util.Arrays.fill(v, '\0'));
      if (typed != null) {
        typed.values().forEach(i -> {
          if (i != null) {
            i.clear();
          }
        });
      }
    }
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
    if (!stored.verified()) {
      throw ProblemException.conflict("Changed outside MCP Gateway", stored.name() + " was changed outside MCP "
          + "Gateway. Review it first.");
    }
    ConnectorManifest manifest = usable(stored.config().type());
    Taint taint = new Taint();
    TestResult result;
    try (var resolver = field == null ? secrets.resolver(stored.config(), taint)
        : secrets.resolverWithVersion(stored.config(), taint, field, version)) {
      result = run(manifest, stored.config(), resolver, taint);
    }
    if (field == null) {
      record(stored, result);
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
    Connector connector = registry.connectorFor(cfg).orElseThrow(() -> ProblemException.unprocessable(
        "Unknown connector", "There is no connector type " + cfg.type() + ".", "/type"));
    CallContext ctx = contexts.builder(Ids.next("tst_"), cfg, policies.limits(cfg.env()))
        .timeout(timeout).taint(taint).build();
    Future<TestResult> future = executor.submit(() -> connector.test(cfg, resolver, ctx));
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
    return TestResults.mask(result, masker, taint);
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
   */
  public void rotateAtSource(String connectionId, String field, char[] next) {
    ConnectionConfigs.Stored stored = configs.byId(connectionId).orElseThrow(() -> ProblemException.notFound(
        "Connection not found", "The connection was deleted."));
    if (!stored.verified()) {
      throw ProblemException.conflict("Changed outside MCP Gateway", stored.name() + " was changed outside MCP "
          + "Gateway. Review it first.");
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
        rotator.rotate(cfg, resolver, field, next, ctx);
        return null;
      });
      try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        ctx.cancel();
        future.cancel(true);
        throw ProblemException.conflict("Rotation didn't finish", "No answer from " + cfg.name() + " within "
            + timeout.toSeconds() + " s. The current value stays in use.");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof ConnectorException refused) {
          throw ProblemException.conflict("The source refused", masker.text(String.valueOf(refused.getMessage()),
              taint, MaskPolicy.SECRETS_ONLY).text() + " The current value stays in use.");
        }
        log.warn("Rotation of {} connection {} failed in the connector: {}", manifest.id(), cfg.name(),
            masker.log(String.valueOf(e.getCause())));
        throw ProblemException.conflict("Rotation failed", "The rotation failed inside the " + manifest.name()
            + " connector. See core.log. The current value stays in use.");
      } catch (InterruptedException e) {
        ctx.cancel();
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw ProblemException.conflict("Rotation cancelled", "The current value stays in use.");
      }
    }
  }

  private ConnectorManifest usable(String type) {
    var t = registry.type(type == null ? "" : type).orElseThrow(() -> ProblemException.unprocessable(
        "Unknown connector", "There is no connector type " + type + ".", "/type"));
    if (!t.usable()) {
      throw ProblemException.unprocessable("Connector off", t.manifest().name() + " is switched off or was changed "
          + "outside MCP Gateway.", "/type");
    }
    return t.manifest();
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
