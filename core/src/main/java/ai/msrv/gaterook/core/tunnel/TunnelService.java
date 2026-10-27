package ai.msrv.gaterook.core.tunnel;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.core.endpoints.EndpointChanged;
import ai.msrv.gaterook.core.endpoints.EndpointRemoved;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.RiskAnalyzer;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.tunnel.internal.AgentProcess;
import ai.msrv.gaterook.core.tunnel.internal.CallerGate;
import ai.msrv.gaterook.core.tunnel.internal.CloudflaredAgent;
import ai.msrv.gaterook.core.tunnel.internal.NgrokAgent;
import ai.msrv.gaterook.core.tunnel.internal.SelfTest;
import ai.msrv.gaterook.core.tunnel.internal.TunnelAgent;
import ai.msrv.gaterook.core.tunnel.internal.TunnelBinaries;
import ai.msrv.gaterook.core.tunnel.internal.TunnelException;
import ai.msrv.gaterook.core.tunnel.internal.TunnelProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tunnel (ARCHITECTURE.md A6, §7.3, report 02): Publish one endpoint through Cloudflare (quick or named) or ngrok,
 * stop it, check it, and Settings › Tunnel with its single Callers switch.
 *
 * <ul>
 *   <li><b>Publish</b> is the only way on (Undo never publishes again): it chooses the endpoint (its
 *       {@code published} flag, which stays while the tunnel is off), records the Setup change and starts the agent
 *       in the background ({@code starting}). The URL goes public only once the agent knows its hostname and is
 *       connected to its edge; then the self-test runs. A prod endpoint is never published, and one that gets a prod
 *       connection or is deleted takes the tunnel down.</li>
 *   <li><b>One endpoint</b> (MVP): publishing another while the tunnel is on is refused; turn it off first.</li>
 *   <li>The tunnel is always <b>off after a start</b> of the core: a quick tunnel's address is gone with its process,
 *       and nothing goes public without the person asking.</li>
 *   <li>States: {@code off → starting → on}, {@code starting|on → error} (the agent failed or exited),
 *       {@code * → off} (stop). Every change is a {@code tunnel.state} event and a {@link TunnelStateChanged}.</li>
 * </ul>
 */
@Service
public class TunnelService implements DisposableBean {

  static final String SETTINGS_KEY = "tunnel";
  static final String HISTORY_KEY = "tunnel.history";
  static final int HISTORY_MAX = 50;

  private static final Logger log = LoggerFactory.getLogger(TunnelService.class);
  private static final SecretOwner OWNER = new SecretOwner(SecretOwner.Kind.TUNNEL, TunnelSettings.SECRET_OWNER);

  /** What the tunnel does, swapped whole under {@link #lock}. */
  private record Now(String state, TunnelProvider provider, String endpoint, String host, Instant since, String error,
      TunnelCheck check, long generation, TunnelAgent.Started running, Instant sessionFrom) {}

  private final TunnelPublication publication;
  private final CallerGate gate;
  private final TunnelBinaries binaries;
  private final CloudflaredAgent cloudflared;
  private final NgrokAgent ngrok;
  private final ObjectProvider<TunnelProbe> probes;
  private final SettingStore store;
  private final SettingsService settingsService;
  private final SecretService secrets;
  private final EndpointService endpoints;
  private final EndpointSnapshotService snapshots;
  private final RiskAnalyzer risks;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final EventBus events;
  private final ApplicationEventPublisher publisher;
  private final Masker masker;
  private final Bootstrap bootstrap;
  private final Path home;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile Now now;
  private volatile String ngrokInstall = "idle";
  /** The thread starting the agent of the current generation (guarded by {@link #lock}). */
  private Thread starter;

  public TunnelService(TunnelPublication publication, CallerGate gate, TunnelBinaries binaries,
      CloudflaredAgent cloudflared, NgrokAgent ngrok, ObjectProvider<TunnelProbe> probes, SettingStore store,
      SettingsService settingsService, SecretService secrets, EndpointService endpoints,
      EndpointSnapshotService snapshots, RiskAnalyzer risks, ObjectProvider<SetupChangeRecorder> setupChanges,
      EventBus events, ApplicationEventPublisher publisher, Masker masker, Bootstrap bootstrap,
      @Value("${gateway.home}") Path home, Clock clock, PlatformTransactionManager transactions) {
    this.publication = publication;
    this.gate = gate;
    this.binaries = binaries;
    this.cloudflared = cloudflared;
    this.ngrok = ngrok;
    this.probes = probes;
    this.store = store;
    this.settingsService = settingsService;
    this.secrets = secrets;
    this.endpoints = endpoints;
    this.snapshots = snapshots;
    this.risks = risks;
    this.setupChanges = setupChanges;
    this.events = events;
    this.publisher = publisher;
    this.masker = masker;
    this.bootstrap = bootstrap;
    this.home = home;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.now = new Now("off", null, null, null, clock.instant(), null, null, 0, null, null);
  }

  // ── Start and stop of the core ──────────────────────────────────────────────────────────────────────────────

  /**
   * Arms the Callers gate from the setting, closes the history entry a crash left open and kills the agent it left
   * behind (which may take a few seconds: TERM first).
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      TunnelSettings s = settings();
      gate.setAnthropicOnly(s.anthropicOnly());
      closeOpenSession(clock.instant(), "MCP Gateway stopped");
    } catch (RuntimeException e) {
      // A locked core (no master key) still serves the status screen; the gate stays on, its safe default.
      log.warn("Tunnel settings not loaded at start: {}", e.toString());
    }
    AgentProcess.killOrphan(runDir().resolve("tunnel.pid"));
  }

  @Override
  public void destroy() {
    lock.lock();
    try {
      Now n = now;
      if (n.running() != null) {
        n.running().process().stop();
      }
      Thread start = interruptStart(n);
      publication.clear();
      if (start != null) {
        try {
          // The agent being started dies with its start (TERM, then KILL): never left behind by a quit.
          start.join(Duration.ofSeconds(7));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (n.sessionFrom() != null) {
        try {
          closeSession(n, clock.instant());
          recordChangeSafely(n.endpoint(), "On", "Off", "MCP Gateway stopped");
        } catch (RuntimeException e) {
          log.debug("Tunnel history not written at shutdown: {}", e.toString());
        }
      }
      now = new Now("off", n.provider(), n.endpoint(), n.host(), clock.instant(), null, null, n.generation() + 1,
          null, null);
    } finally {
      lock.unlock();
    }
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code GET /api/tunnel}. */
  public TunnelState state() {
    Now n = now;
    TunnelSettings s = settings();
    String endpoint = n.endpoint() != null && !"off".equals(n.state()) ? n.endpoint() : s.endpoint();
    TunnelProvider provider = n.provider() != null && !"off".equals(n.state()) ? n.provider() : s.provider();
    Optional<TunnelPublication.Publication> pub = publication.current();
    String host = pub.map(TunnelPublication.Publication::host).orElse(n.host());
    String url = pub.map(TunnelPublication.Publication::resource).orElse(null);
    String state = n.state();
    if (publication.overridden() && "off".equals(state)) {
      // The test override pretends a tunnel: the listener serves it, so the state says so.
      state = "on";
      endpoint = pub.get().endpointSlug();
      provider = TunnelProvider.TEST;
    }
    TunnelBinaries.Status cf = binaries.cloudflaredStatus();
    TunnelBinaries.Status ng = binaries.ngrokStatus();
    String ngrokError = ng.error() != null ? ng.error() : "downloading".equals(ngrokInstall) ? "Downloading ngrok "
        + ng.version() + "…" : null;
    return new TunnelState(provider, state, host, url, endpoint, n.since(), s.anthropicOnly(),
        new TunnelState.Agent(cf.version(), cf.present(), cf.sha256Ok(), cf.error()),
        new TunnelState.Agent(ng.version(), ng.present(), ng.sha256Ok(), ngrokError), n.error(),
        gate.refusedCount(), gate.servedCount(), n.check(), history());
  }

  /** {@code off}, {@code starting}, {@code on} or {@code error}: the sidebar's counter and {@code /api/health}. */
  public String summary() {
    return publication.overridden() && "off".equals(now.state()) ? "on" : now.state();
  }

  /** Anthropic's outbound ranges the Callers gate admits (built into the release). */
  public static List<String> anthropicRanges() {
    return CallerGate.anthropicRanges();
  }

  /** The day {@link #anthropicRanges()} was checked against Anthropic's documentation. */
  public static String anthropicRangesVerified() {
    return CallerGate.anthropicRangesVerified();
  }

  /** The public hostname while the tunnel publishes, for {@code /api/health}. */
  public Optional<String> host() {
    return publication.current().map(TunnelPublication.Publication::host);
  }

  public TunnelSettings settings() {
    Optional<SettingStore.Setting> stored = store.find(SETTINGS_KEY);
    JsonNode v = stored.map(SettingStore.Setting::value).orElse(JsonNodeFactory.instance.objectNode());
    TunnelProvider provider = TunnelProvider.of(v.path("provider").asString(null))
        .orElse(TunnelProvider.CLOUDFLARE_QUICK);
    Set<String> stored2 = storedSecretFields();
    return new TunnelSettings(provider, text(v.path("namedHostname")), text(v.path("ngrokDomain")),
        v.path("anthropicOnly").asBoolean(true), publishedEndpoint().orElse(null),
        stored2.contains(TunnelSettings.TUNNEL_TOKEN_FIELD), stored2.contains(TunnelSettings.NGROK_AUTHTOKEN_FIELD),
        stored.map(SettingStore.Setting::version).orElse(0L));
  }

  /** The endpoint whose row says the tunnel publishes it. */
  private Optional<String> publishedEndpoint() {
    return endpoints.list().stream().filter(EndpointService.Summary::published).map(EndpointService.Summary::slug)
        .findFirst();
  }

  private Set<String> storedSecretFields() {
    Set<String> out = new java.util.HashSet<>();
    for (SecretInfo i : secrets.listForOwner(OWNER)) {
      out.add(i.field());
    }
    return out;
  }

  // ── Publish ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/tunnel/publish/preview}: nothing changes. */
  public PublishPreview preview(String slug) {
    EndpointService.Summary e = endpoints.get(slug);
    EndpointSnapshot snapshot = endpoints.snapshot(slug);
    TunnelSettings s = settings();
    List<String> local = new ArrayList<>(List.of("The app and its admin API (127.0.0.1:" + bootstrap.port() + ")"));
    for (EndpointService.Summary other : endpoints.list()) {
      if (!other.slug().equals(slug)) {
        local.add("/mcp/" + other.slug());
      }
    }
    List<PublishPreview.Connection> connections = snapshot.connections().stream()
        .map(w -> new PublishPreview.Connection(w.config().name(), w.config().env().id())).toList();
    return new PublishPreview(slug, e.env().id(), s.provider(), List.of("/mcp/" + slug, "/oauth/*",
        "/.well-known/oauth-*"), List.copyOf(local), connections,
        new PublishPreview.Tools(e.tools().read(), e.tools().approval(), e.tools().off()), riskText(slug, e.risk()),
        refusal(slug, snapshot, s).orElse(null), s.anthropicOnly(), warnings(s, snapshot));
  }

  /**
   * {@code POST /api/tunnel/publish {endpoint}}: 202 — the agent starts in the background and the state follows as
   * {@code tunnel.state} events.
   *
   * @throws ProblemException 422 for an endpoint that can't be published, 409 while another one is public
   */
  public TunnelState publish(String slug, String reason) {
    lock.lock();
    try {
      EndpointSnapshot snapshot = endpoints.snapshot(slug);
      TunnelSettings s = settings();
      Now n = now;
      if (("on".equals(n.state()) || "starting".equals(n.state())) && slug.equals(n.endpoint())) {
        return state();
      }
      Optional<String> refusal = refusal(slug, snapshot, s);
      if (refusal.isPresent()) {
        if ("on".equals(n.state()) || "starting".equals(n.state())) {
          throw ProblemException.conflict("Tunnel in use", refusal.get());
        }
        throw ProblemException.unprocessable("Can't publish", refusal.get(), "/endpoint");
      }
      Path binary = binary(s.provider());
      Optional<String> previous = publishedEndpoint().filter(p -> !p.equals(slug));
      writeTx.executeWithoutResult(tx -> {
        previous.ifPresent(p -> endpoints.setPublished(p, false));
        endpoints.setPublished(slug, true);
        recordChange(slug, "Off", "On", reason);
      });
      long generation = n.generation() + 1;
      now = new Now("starting", s.provider(), slug, null, clock.instant(), null, null, generation, null, null);
      announce();
      String hostname = s.provider() == TunnelProvider.NGROK ? s.ngrokDomain() : s.namedHostname();
      starter = Thread.ofVirtual().name("tunnel-start")
          .start(() -> run(generation, s.provider(), slug, binary, hostname));
      return state();
    } finally {
      lock.unlock();
    }
  }

  private Path binary(TunnelProvider provider) {
    try {
      if (provider == TunnelProvider.NGROK) {
        return binaries.ngrok().orElseThrow(() -> ProblemException.conflict("ngrok not downloaded",
            "ngrok " + binaries.ngrokStatus().version() + " isn't downloaded yet. Choose ngrok in Settings › Tunnel "
                + "and wait for the download."));
      }
      return binaries.cloudflared();
    } catch (TunnelException e) {
      throw ProblemException.conflict("Tunnel agent unavailable", e.getMessage());
    }
  }

  /** On a virtual thread: starts the agent; only the generation that is still current may go public. */
  private void run(long generation, TunnelProvider provider, String slug, Path binary, String hostname) {
    Taint taint = new Taint();
    char[] secret = null;
    TunnelAgent.Started started = null;
    try {
      secret = secret(provider, taint);
      TunnelAgent.StartRequest request = new TunnelAgent.StartRequest(provider, binary, bootstrap.publicPort(),
          hostname, secret, runDir(), logsDir());
      started = (provider == TunnelProvider.NGROK ? ngrok : cloudflared).start(request);
    } catch (TunnelException e) {
      failed(generation, mask(e.getMessage(), taint));
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failed(generation, "The tunnel didn't start: it was stopped.");
      return;
    } catch (RuntimeException e) {
      log.warn("Tunnel start failed", e);
      failed(generation, mask("The tunnel didn't start: " + e.getMessage(), taint));
      return;
    } finally {
      if (secret != null) {
        Arrays.fill(secret, '\0');
      }
    }
    lock.lock();
    try {
      if (now.generation() != generation) {
        started.process().stop();
        return;
      }
      TunnelPublication.Publication p = new TunnelPublication.Publication(started.host(), slug, provider);
      gate.takeCounters();
      publication.publish(p);
      Instant at = clock.instant();
      now = new Now("on", provider, slug, p.host(), at, null, null, generation, started, at);
      openSession(now);
      log.info("Tunnel on: https://{}/mcp/{} via {} ({})", p.host(), slug, provider.label(), started.detail());
    } finally {
      lock.unlock();
    }
    announce();
    TunnelAgent.Started running = started;
    Thread.ofVirtual().name("tunnel-watch").start(() -> watch(generation, running, taint));
    check(generation);
  }

  /** The agent exited on its own: the URL stops being public at once. */
  private void watch(long generation, TunnelAgent.Started started, Taint taint) {
    int exit;
    try {
      exit = started.process().waitFor();
    } catch (InterruptedException e) {
      return;
    }
    String tail = mask(String.join(" ", lastLines(started)), taint);
    lock.lock();
    try {
      Now n = now;
      if (n.generation() != generation || !"on".equals(n.state())) {
        return;
      }
      publication.clear();
      closeSession(n, clock.instant());
      String message = n.provider().label() + " stopped (exit " + exit + "). Publish again to get a URL."
          + (tail.isBlank() ? "" : " Last log line: " + tail);
      now = new Now("error", n.provider(), n.endpoint(), n.host(), clock.instant(), message, n.check(),
          generation + 1, null, null);
      recordChangeSafely(n.endpoint(), "On", "Off", "The tunnel agent stopped");
    } finally {
      lock.unlock();
    }
    announce();
  }

  private static List<String> lastLines(TunnelAgent.Started started) {
    List<String> tail = started.process().logTail();
    String last = TunnelAgent.lastError(tail);
    return last == null ? List.of() : List.of(last);
  }

  private void failed(long generation, String message) {
    lock.lock();
    try {
      Now n = now;
      if (n.generation() != generation) {
        return;
      }
      publication.clear();
      now = new Now("error", n.provider(), n.endpoint(), null, clock.instant(), message, null, generation + 1, null,
          null);
      recordChangeSafely(n.endpoint(), "Starting", "Off", "Didn't start: " + message);
      log.warn("Tunnel didn't start: {}", message);
    } finally {
      lock.unlock();
    }
    announce();
  }

  /** {@code POST /api/tunnel/stop}: the URL stops being public at once; the endpoint stays the tunnel's. */
  public TunnelState stop(String reason) {
    lock.lock();
    try {
      Now n = now;
      if ("off".equals(n.state())) {
        return state();
      }
      if (n.running() != null) {
        n.running().process().stop();
      }
      interruptStart(n);
      publication.clear();
      boolean wasUp = "on".equals(n.state()) || "starting".equals(n.state());
      if (n.sessionFrom() != null) {
        closeSession(n, clock.instant());
      }
      if (wasUp) {
        recordChangeSafely(n.endpoint(), "On", "Off", reason);
      }
      now = new Now("off", n.provider(), n.endpoint(), n.host(), clock.instant(), null, null, n.generation() + 1,
          null, null);
    } finally {
      lock.unlock();
    }
    announce();
    return state();
  }

  /**
   * While the agent is still starting, interrupts its start: the agent stops the process it launched at once
   * instead of when it would have been ready. Under {@link #lock}; returns the interrupted thread, if any.
   */
  private Thread interruptStart(Now n) {
    Thread t = starter;
    starter = null;
    if (t == null || !"starting".equals(n.state()) || !t.isAlive()) {
      return null;
    }
    t.interrupt();
    return t;
  }

  // ── Check ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/tunnel/check}: the self-test now (≤ ~40 s); 409 while nothing is public. */
  public TunnelCheck check() {
    Now n = now;
    if (!"on".equals(n.state()) || publication.current().isEmpty()) {
      throw ProblemException.conflict("Tunnel off", "Publish an endpoint first: the check calls its public URL.");
    }
    return check(n.generation());
  }

  private TunnelCheck check(long generation) {
    Optional<TunnelPublication.Publication> p = publication.current();
    if (p.isEmpty()) {
      return null;
    }
    TunnelCheck result;
    try {
      EndpointSnapshot snapshot = snapshots.bySlug(p.get().endpointSlug()).orElse(null);
      List<String> warnings = snapshot == null ? List.of() : warnings(settings(), snapshot);
      result = new SelfTest(probes.getIfAvailable(TunnelProbe.Http::new), gate, clock).run(p.get(), warnings);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (RuntimeException e) {
      log.warn("Tunnel self-test failed", e);
      result = new TunnelCheck(false, "The check failed: " + e.getMessage(), null, null, List.of(), List.of(),
          List.of(), clock.instant());
    }
    lock.lock();
    try {
      Now n = now;
      if (n.generation() == generation && "on".equals(n.state())) {
        now = new Now(n.state(), n.provider(), n.endpoint(), n.host(), n.since(), n.error(), result, n.generation(),
            n.running(), n.sessionFrom());
      }
    } finally {
      lock.unlock();
    }
    announce();
    return result;
  }

  // ── Settings ────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code PUT /api/settings/tunnel}; a {@code null} field stays as it is. Tokens are replaced, never shown. */
  public record SettingsUpdate(String provider, String namedHostname, String ngrokDomain, char[] tunnelToken,
      char[] ngrokAuthtoken) {

    @Override
    public String toString() {
      return "SettingsUpdate[provider=" + provider + ", namedHostname=" + namedHostname + ", ngrokDomain="
          + ngrokDomain + ", tokens=‹redacted›]";
    }
  }

  /**
   * Saves the provider and its address. Choosing ngrok downloads its pinned agent in the background (decision 8).
   *
   * @throws ProblemException 422 at the field, 409 while the tunnel is on and the provider would change
   * @throws OptimisticLockingFailureException when someone saved meanwhile
   */
  public TunnelSettings updateSettings(SettingsUpdate update, long version, String reason) {
    try {
      TunnelSettings before = settings();
      if (before.version() != version) {
        throw new OptimisticLockingFailureException("Tunnel settings are at version " + before.version());
      }
      TunnelProvider provider = update.provider() == null ? before.provider()
          : TunnelProvider.of(update.provider()).orElseThrow(() -> ProblemException.unprocessable("Unknown provider",
              "Choose cloudflare-quick, cloudflare-named or ngrok.", "/provider"));
      String named = update.namedHostname() == null ? before.namedHostname() : blankToNull(update.namedHostname());
      String domain = update.ngrokDomain() == null ? before.ngrokDomain() : blankToNull(update.ngrokDomain());
      if (named != null && !TunnelPublication.validHost(named)) {
        throw ProblemException.unprocessable("Invalid hostname", "Use the hostname you routed to the tunnel in "
            + "Cloudflare, like gw.example.com.", "/namedHostname");
      }
      if (domain != null && !TunnelPublication.validHost(domain)) {
        throw ProblemException.unprocessable("Invalid domain", "Use your reserved ngrok domain, like "
            + "gw-acme.ngrok.app.", "/ngrokDomain");
      }
      String state = now.state();
      boolean up = "on".equals(state) || "starting".equals(state);
      if (up && (provider != before.provider() || !java.util.Objects.equals(named, before.namedHostname())
          || !java.util.Objects.equals(domain, before.ngrokDomain()))) {
        throw ProblemException.conflict("Tunnel on", "Turn the tunnel off to change its provider or address.");
      }
      writeTx.executeWithoutResult(tx -> {
        ObjectNode value = storedValue();
        value.put("provider", provider.id());
        putOrNull(value, "namedHostname", named);
        putOrNull(value, "ngrokDomain", domain);
        store.put(SETTINGS_KEY, value, version);
        SetupChangeRecorder recorder = setupChanges.getObject();
        if (provider != before.provider()) {
          recorder.record(new SetupChange(SetupChangeRecorder.What.SETTINGS, "Settings › Tunnel · provider",
              before.provider().id(), provider.id(), reason, null, null, null, null));
        }
        if (!java.util.Objects.equals(named, before.namedHostname())) {
          recorder.record(new SetupChange(SetupChangeRecorder.What.SETTINGS, "Settings › Tunnel · namedHostname",
              before.namedHostname(), named, reason, null, null, null, null));
        }
        if (!java.util.Objects.equals(domain, before.ngrokDomain())) {
          recorder.record(new SetupChange(SetupChangeRecorder.What.SETTINGS, "Settings › Tunnel · ngrokDomain",
              before.ngrokDomain(), domain, reason, null, null, null, null));
        }
        storeSecret(TunnelSettings.TUNNEL_TOKEN_FIELD, "tunnel/named-tunnel-token", update.tunnelToken(), reason);
        storeSecret(TunnelSettings.NGROK_AUTHTOKEN_FIELD, "tunnel/ngrok-authtoken", update.ngrokAuthtoken(), reason);
      });
      if (provider == TunnelProvider.NGROK && !binaries.ngrokStatus().present()) {
        installNgrokInBackground();
      }
      return settings();
    } finally {
      wipe(update.tunnelToken());
      wipe(update.ngrokAuthtoken());
    }
  }

  private void storeSecret(String field, String name, char[] value, String reason) {
    if (value == null || value.length == 0) {
      return;
    }
    char[] copy = value.clone();
    Optional<SecretInfo> existing = secrets.listForOwner(OWNER).stream().filter(i -> field.equals(i.field()))
        .findFirst();
    if (existing.isPresent()) {
      secrets.replace(existing.get().id(), copy, false, reason);
    } else {
      secrets.createLocal(OWNER, field, name, copy);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SECRET, name, null, "Stored", reason,
          null, null, null, null));
    }
  }

  /** The person chose ngrok: its pinned agent is fetched now, the state shows the download. */
  public void installNgrokInBackground() {
    if ("downloading".equals(ngrokInstall)) {
      return;
    }
    ngrokInstall = "downloading";
    announce();
    Thread.ofVirtual().name("ngrok-download").start(() -> {
      try {
        binaries.installNgrok();
        ngrokInstall = "ready";
      } catch (TunnelException e) {
        ngrokInstall = "failed";
        log.warn("ngrok download failed: {}", e.getMessage());
      }
      announce();
    });
  }

  /** {@code PUT /api/settings/tunnel/callers {anthropicOnly}}: applies at once, also while the tunnel is off. */
  public TunnelSettings setCallers(boolean anthropicOnly, String reason) {
    TunnelSettings before = settings();
    if (before.anthropicOnly() != anthropicOnly) {
      writeTx.executeWithoutResult(tx -> {
        ObjectNode value = storedValue();
        value.put("anthropicOnly", anthropicOnly);
        store.put(SETTINGS_KEY, value, before.version());
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.TUNNEL, "Tunnel › Callers",
            callers(before.anthropicOnly()), callers(anthropicOnly), reason, null, null, null, null));
      });
      gate.setAnthropicOnly(anthropicOnly);
      announce();
    }
    return settings();
  }

  private static String callers(boolean anthropicOnly) {
    return anthropicOnly ? "Anthropic IP ranges only" : "Any address";
  }

  private ObjectNode storedValue() {
    return store.find(SETTINGS_KEY).map(SettingStore.Setting::value).filter(JsonNode::isObject)
        .map(v -> (ObjectNode) v.deepCopy()).orElseGet(JsonNodeFactory.instance::objectNode);
  }

  // ── Endpoint changes ────────────────────────────────────────────────────────────────────────────────────────

  /** A published endpoint that got a prod connection (or can't be published any more) takes the tunnel down. */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onEndpointChanged(EndpointChanged e) {
    Now n = now;
    if (n.endpoint() == null || !n.endpoint().equals(e.slug()) || "off".equals(n.state())) {
      return;
    }
    Optional<EndpointSnapshot> snapshot = snapshots.get(e.endpointId());
    if (snapshot.isEmpty()) {
      stop("Endpoint " + e.slug() + " was deleted");
    } else if (snapshot.get().env() == Env.PROD) {
      stop(e.slug() + " has a prod connection now, and a prod endpoint is never published");
      endpoints.setPublished(e.slug(), false);
    } else if (snapshot.get().blocked()) {
      stop(e.slug() + " was changed outside MCP Gateway");
    }
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onEndpointRemoved(EndpointRemoved e) {
    Now n = now;
    if (e.slug().equals(n.endpoint()) && !"off".equals(n.state())) {
      stop("Endpoint " + e.slug() + " was deleted");
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private Optional<String> refusal(String slug, EndpointSnapshot snapshot, TunnelSettings s) {
    if (snapshot.blocked()) {
      return Optional.of(slug + " was changed outside MCP Gateway. Review it first.");
    }
    if (snapshot.env() == Env.PROD) {
      return Optional.of(slug + " has prod connections, and a prod endpoint is never published through the "
          + "tunnel.");
    }
    if (!snapshot.enabled()) {
      return Optional.of(slug + " is disabled. Enable it first.");
    }
    Now n = now;
    if (("on".equals(n.state()) || "starting".equals(n.state())) && !slug.equals(n.endpoint())) {
      return Optional.of(n.endpoint() + " is public now: one endpoint at a time. Turn the tunnel off first.");
    }
    if (s.provider() == TunnelProvider.CLOUDFLARE_NAMED) {
      if (s.namedHostname() == null) {
        return Optional.of("A named tunnel needs its hostname: set it in Settings › Tunnel.");
      }
      if (!s.tunnelTokenStored()) {
        return Optional.of("A named tunnel needs its token: paste it in Settings › Tunnel.");
      }
    }
    if (s.provider() == TunnelProvider.NGROK && !s.ngrokAuthtokenStored()) {
      return Optional.of("ngrok needs your authtoken: paste it in Settings › Tunnel.");
    }
    return Optional.empty();
  }

  private List<String> warnings(TunnelSettings s, EndpointSnapshot snapshot) {
    List<String> out = new ArrayList<>();
    boolean asks = snapshot.onPairs().stream().anyMatch(p -> p.tool().mutating()
        && p.mode() == ai.msrv.gaterook.connector.api.ToolDefinition.Mode.APPROVAL);
    if (asks) {
      out.add("claude.ai can request changes; each one still waits for you here.");
    }
    if (!s.anthropicOnly()) {
      out.add("Callers: any address can reach the public URL, not only Anthropic's.");
    }
    if ("cimd+dcr".equals(settingsService.value("authorization", "clientRegistration").asString("cimd"))) {
      out.add("Dynamic client registration is on: any client that reaches the URL can register (it still needs your "
          + "consent here).");
    }
    if (!s.provider().stableAddress(s.ngrokDomain())) {
      out.add("A quick tunnel gets a new address each time it starts; claude.ai must be pointed at the new URL. "
          + "Use a named tunnel for a stable hostname.");
    }
    return out;
  }

  private String riskText(String slug, RiskAnalyzer.Report report) {
    if (report == null) {
      return null;
    }
    return switch (report.state()) {
      case "present" -> "Risky combination, 3 of 3: " + slug + " holds private data, untrusted content and an "
          + "external channel, and the tunnel publishes it.";
      case "limited" -> "Risky combination with a limited channel: " + slug + " holds private data and untrusted "
          + "content; its external channel is limited to fixed runbooks.";
      case "partial" -> "2 of 3 risk ingredients on " + slug + ".";
      default -> null;
    };
  }

  private char[] secret(TunnelProvider provider, Taint taint) throws TunnelException {
    String field = switch (provider) {
      case CLOUDFLARE_NAMED -> TunnelSettings.TUNNEL_TOKEN_FIELD;
      case NGROK -> TunnelSettings.NGROK_AUTHTOKEN_FIELD;
      default -> null;
    };
    if (field == null) {
      return null;
    }
    try (ScopedSecretResolver resolver = secrets.resolver(OWNER, Set.of(field), taint);
        SecretResolver.Secret s = resolver.secret(field)) {
      return s.chars().clone();
    } catch (ai.msrv.gaterook.connector.api.SecretUnavailableException e) {
      throw new TunnelException("The tunnel's token can't be read: " + e.getMessage());
    }
  }

  private String mask(String text, Taint taint) {
    return text == null ? null : masker.text(text, taint, MaskPolicy.SECRETS_ONLY).text();
  }

  private void recordChange(String endpoint, String from, String to, String reason) {
    EndpointSnapshot snapshot = endpoint == null ? null : snapshots.bySlug(endpoint).orElse(null);
    setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.TUNNEL,
        endpoint == null ? "Tunnel" : "Tunnel · " + endpoint, from, to, reason,
        snapshot == null ? null : snapshot.env(), endpoint, null, null));
  }

  private void recordChangeSafely(String endpoint, String from, String to, String reason) {
    try {
      writeTx.executeWithoutResult(tx -> recordChange(endpoint, from, to, reason));
    } catch (RuntimeException e) {
      log.warn("Setup change of the tunnel not recorded: {}", e.toString());
    }
  }

  private void announce() {
    Now n = now;
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("state", summary());
    data.put("host", publication.current().map(TunnelPublication.Publication::host).orElse(null));
    data.put("endpoint", n.endpoint());
    data.put("provider", n.provider() == null ? null : n.provider().id());
    data.put("error", n.error());
    events.publish("tunnel.state", data);
    publisher.publishEvent(new TunnelStateChanged(n.state(), publication.current()
        .map(TunnelPublication.Publication::host).orElse(null), n.endpoint(), n.provider()));
  }

  // ── Demo world ──────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The demo world's tunnel (BLUEPRINT §9, {@code GW_DEMO} only): {@code slug} is the tunnel's endpoint, it was public
   * at {@code host} from {@code from} to {@code to} with {@code calls} calls, and both switches are in Setup changes.
   * The tunnel itself stays off, as after every start.
   */
  public void seedDemo(String slug, String host, TunnelProvider provider, Instant from, Instant to, long calls,
      String reason) {
    writeTx.executeWithoutResult(tx -> {
      endpoints.setPublished(slug, true);
      recordChange(slug, "Off", "On", reason);
      recordChange(slug, "On", "Off", null);
    });
    ArrayNode list = historyArray();
    ObjectNode session = JsonNodeFactory.instance.objectNode();
    session.put("from", from.toEpochMilli());
    session.put("to", to.toEpochMilli());
    session.put("endpoint", slug);
    session.put("host", host);
    session.put("provider", provider.id());
    session.put("calls", calls);
    session.put("refused", 0);
    list.insert(0, session);
    writeHistory(list);
  }

  // ── History ─────────────────────────────────────────────────────────────────────────────────────────────────

  public List<TunnelState.Session> history() {
    JsonNode stored = store.find(HISTORY_KEY).map(SettingStore.Setting::value).orElse(null);
    List<TunnelState.Session> out = new ArrayList<>();
    if (stored != null && stored.isArray()) {
      for (JsonNode s : stored) {
        out.add(new TunnelState.Session(instant(s.path("from")), instant(s.path("to")),
            s.path("endpoint").asString(null), s.path("host").asString(null), s.path("provider").asString(null),
            s.path("calls").asLong(0), s.path("refused").asLong(0)));
      }
    }
    Now n = now;
    if (n.sessionFrom() != null && !out.isEmpty() && out.getFirst().to() == null) {
      TunnelState.Session open = out.getFirst();
      out.set(0, new TunnelState.Session(open.from(), null, open.endpoint(), open.host(), open.provider(),
          gate.servedCount(), gate.refusedCount()));
    }
    return out;
  }

  private void openSession(Now n) {
    ArrayNode list = historyArray();
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("from", n.sessionFrom().toEpochMilli());
    s.putNull("to");
    s.put("endpoint", n.endpoint());
    s.put("host", n.host());
    s.put("provider", n.provider().id());
    s.put("calls", 0);
    s.put("refused", 0);
    list.insert(0, s);
    while (list.size() > HISTORY_MAX) {
      list.remove(list.size() - 1);
    }
    writeHistory(list);
  }

  private void closeSession(Now n, Instant at) {
    long[] counters = gate.takeCounters();
    ArrayNode list = historyArray();
    if (!list.isEmpty() && list.get(0).path("to").isNull()) {
      ObjectNode s = (ObjectNode) list.get(0);
      s.put("to", at.toEpochMilli());
      s.put("refused", counters[0]);
      s.put("calls", counters[1]);
      writeHistory(list);
    }
  }

  private void closeOpenSession(Instant at, String why) {
    ArrayNode list = historyArray();
    if (!list.isEmpty() && list.get(0).path("to").isNull()) {
      ((ObjectNode) list.get(0)).put("to", at.toEpochMilli());
      writeHistory(list);
      log.info("Tunnel history: the session of {} ended when {}", list.get(0).path("endpoint").asString(""),
          why.toLowerCase(java.util.Locale.ROOT));
    }
  }

  private ArrayNode historyArray() {
    JsonNode stored = store.find(HISTORY_KEY).map(SettingStore.Setting::value).orElse(null);
    return stored != null && stored.isArray() ? (ArrayNode) stored.deepCopy()
        : JsonNodeFactory.instance.arrayNode();
  }

  private void writeHistory(ArrayNode list) {
    try {
      store.put(HISTORY_KEY, list, null);
    } catch (RuntimeException e) {
      log.warn("Tunnel history not written: {}", e.toString());
    }
  }

  private static Instant instant(JsonNode n) {
    return n.isNumber() ? Instant.ofEpochMilli(n.asLong()) : null;
  }

  // ── Small helpers ───────────────────────────────────────────────────────────────────────────────────────────

  private Path runDir() {
    Path run = home.resolve("run");
    try {
      Files.createDirectories(run);
      GatewayPaths.ownerOnlyDirectory(run);
    } catch (IOException e) {
      log.warn("Can't prepare {}: {}", run, e.toString());
    }
    return run;
  }

  private Path logsDir() {
    return home.resolve("logs");
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode() || n.asString("").isBlank() ? null : n.asString();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip().toLowerCase(java.util.Locale.ROOT);
  }

  private static void putOrNull(ObjectNode node, String key, String value) {
    if (value == null) {
      node.putNull(key);
    } else {
      node.put(key, value);
    }
  }

  private static void wipe(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }
}
