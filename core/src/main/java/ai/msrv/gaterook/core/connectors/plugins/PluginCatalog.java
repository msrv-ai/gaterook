package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The signed catalog as the core keeps it (ARCHITECTURE.md §7.5, report 11 §4.4, step K6): the last good
 * {@code index.json} verified with {@link CatalogVerifier}, held in memory and in the setting {@code catalog.last-good},
 * so the Catalog tab works offline ("Checked 3 h ago") and installed plugins work without our servers.
 *
 * <p>A refresh downloads {@code index.json} and {@code index.json.sig} from {@code gateway.plugins.catalog-url}
 * through {@link CatalogDownloads}. What a failed refresh leaves is the last good index, marked:
 * <ul>
 *   <li>unreachable — {@code offline}, the signature as it was, {@code problem} says why;</li>
 *   <li>a signature that doesn't hold, an unknown key, a rollback — {@code offline} and {@code signature.ok=false}:
 *       nothing of the new index is offered;</li>
 *   <li>expired — the entries stay visible with {@code signature.ok=false}, but nothing installs from them
 *       ({@link #installProblem}) until a fresh index comes ("Catalog signature expired").</li>
 * </ul>
 * The Catalog tab checks again when the last check is older than six hours; a failing check is retried at most every
 * five minutes, so an offline core doesn't wait on the network at every look.
 *
 * <p>Rollback protection (ARCHITECTURE.md §4, V87): an index is accepted only when its sequence isn't below the highest
 * one the core vouches for. That sequence is kept apart from the index, in {@code catalog.sequence}, and both settings
 * are signed rows ({@link SettingStore#SIGNED}), written together: another program editing {@code gateway.db} can't
 * lower it by putting an older, validly signed index in place of the stored one, nor by deleting that. The stored
 * index is trusted only when both rows verify and it isn't older than the sequence. Otherwise it is still shown and
 * its revocations still count, but nothing installs or updates from it, the Catalog tab checks again at its next look,
 * and the integrity alert {@code setting}/{@code catalog.last-good} stays until a fresh signed index is accepted. The
 * sequence an index must reach is the highest of the trusted one and those of the validly signed indexes stored, so
 * an edit can't raise it past what the catalog itself signed. The stored state is read at first use, after the start
 * that applies V87 signed what an older version stored ({@link #signLegacyRows}).
 */
public final class PluginCatalog {

  static final String SETTING = "catalog.last-good";
  /** {@code {"sequence": n}}: the highest sequence of an index the core accepted, written with {@link #SETTING}. */
  static final String SEQUENCE = "catalog.sequence";
  static final long MAX_INDEX_BYTES = 10L * 1024 * 1024;
  static final long MAX_SIG_BYTES = 64L * 1024;
  /** The Catalog tab looks again after this long (report 11 §4.4: "checked 3 h ago", every 6 h). */
  static final Duration STALE = Duration.ofHours(6);
  /** A failing check isn't tried again sooner than this on its own. */
  static final Duration RETRY = Duration.ofMinutes(5);
  static final String UNTRUSTED = "The stored catalog was changed outside GateRook.";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final Logger log = LoggerFactory.getLogger(PluginCatalog.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The current state of the catalog for {@code GET /api/catalog}.
   *
   * @param checkedAt when the shown index was last fetched and verified, or {@code null}
   * @param source    where it came from (the index URL), {@code none} without a configured catalog
   * @param offline   the shown index is the cached one: the last check failed or never ran
   * @param problem   why the last check failed or why nothing installs from it, or {@code null}
   */
  public record State(Instant checkedAt, String source, boolean offline, SignatureInfo signature,
      CatalogVerifier.Verified verified, String problem) {}

  public record SignatureInfo(String keyId, long sequence, Instant expiresAt, boolean ok) {}

  private final CatalogVerifier verifier;
  private final SettingStore settings;
  private final CatalogDownloads downloads;
  private final String baseUrl;
  private final IntegrityAlerts alerts;
  private final MasterKeyLock lock;
  private final TransactionTemplate writeTx;
  private final Clock clock;
  private final AtomicReference<State> state = new AtomicReference<>();
  private volatile Instant lastAttempt;
  /** The stored state was read ({@link #loaded}). */
  private volatile boolean loaded;
  /** The current index is one the core vouches for: fetched and accepted, or stored in rows that verify. */
  private volatile boolean trusted;
  /** The sequence below which an index is a rollback. */
  private volatile long floor = -1;

  public PluginCatalog(CatalogVerifier verifier, SettingStore settings, CatalogDownloads downloads, String baseUrl,
      IntegrityAlerts alerts, MasterKeyLock lock, PlatformTransactionManager transactions, Clock clock) {
    this.verifier = verifier;
    this.settings = settings;
    this.downloads = downloads;
    this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    this.alerts = alerts;
    this.lock = lock;
    this.writeTx = new TransactionTemplate(transactions);
    this.clock = clock;
  }

  /** Whether a catalog URL is configured at all. */
  public boolean configured() {
    return !baseUrl.isBlank();
  }

  /** The verified index held now (possibly expired or untrusted), or empty when none has ever been accepted. */
  public Optional<CatalogVerifier.Verified> current() {
    State s = loaded();
    return Optional.ofNullable(s == null ? null : s.verified());
  }

  /** The state now: an index that expired since it was accepted says so. */
  public State state() {
    State s = loaded();
    if (s == null) {
      return new State(null, configured() ? indexUrl() : "none", true, null, null,
          configured() ? "The catalog hasn't been checked yet." : "No catalog is configured for this build.");
    }
    if (s.verified() != null && expired(s.verified()) && s.signature() != null && s.signature().ok()) {
      return new State(s.checkedAt(), s.source(), s.offline(), new SignatureInfo(s.signature().keyId(),
          s.signature().sequence(), s.signature().expiresAt(), false), s.verified(), expiredText(s.verified()));
    }
    return s;
  }

  /**
   * The state, checked again first when the last check is older than six hours (or never ran, or left an index the
   * core can't vouch for) — at most every five minutes while checks fail.
   */
  public synchronized State stateRefreshingIfStale() {
    if (!configured()) {
      return state();
    }
    State s = loaded();
    Instant now = clock.instant();
    boolean stale = s == null || s.checkedAt() == null || !trusted || s.checkedAt().plus(STALE).isBefore(now);
    boolean mayTry = lastAttempt == null || lastAttempt.plus(RETRY).isBefore(now);
    return stale && mayTry ? refresh() : state();
  }

  /** The catalog entry {@code id@version} of the current index, if it lists it. */
  public Optional<JsonNode> entry(String id, String version) {
    return current().flatMap(v -> {
      for (JsonNode e : v.index().path("entries")) {
        if (id.equals(e.path("id").asString()) && version.equals(entryVersion(e))) {
          return Optional.of(e);
        }
      }
      return Optional.empty();
    });
  }

  /** The entry {@code id@version} of {@code kind}, if the current index lists it. */
  public Optional<JsonNode> entry(String kind, String id, String version) {
    return entry(id, version).filter(e -> kind.equals(e.path("kind").asString("plugin")));
  }

  /**
   * Why nothing may be installed or updated from the catalog now (it was never accepted, the stored one can't be
   * vouched for, or it expired), or empty. Installed plugins keep working either way.
   */
  public Optional<String> installProblem() {
    Optional<CatalogVerifier.Verified> v = current();
    if (v.isEmpty()) {
      return Optional.of(configured() ? "The catalog hasn't been loaded: check it in Connectors › Catalog."
          : "No catalog is configured for this build.");
    }
    if (!trusted) {
      return Optional.of(UNTRUSTED + " Nothing installs or updates from it until a new one is checked in "
          + "Connectors › Catalog.");
    }
    return expired(v.get()) ? Optional.of(expiredText(v.get()) + " Nothing installs from it until a new one is "
        + "checked.") : Optional.empty();
  }

  /** Whether a JAR's SHA-256 is on the current index's revocation list. */
  public boolean isRevoked(String sha256) {
    return current().map(v -> v.isRevoked(sha256)).orElse(false);
  }

  /** Why {@code sha256} was revoked, if it was. */
  public Optional<String> revocationReason(String sha256) {
    return current().flatMap(v -> v.revoked().stream().filter(r -> r.sha256().equalsIgnoreCase(sha256)).findFirst())
        .map(CatalogVerifier.Revoked::reason);
  }

  /** The URL the current index came from: relative artifact addresses resolve against it. */
  public String source() {
    State s = loaded();
    return s == null || s.source() == null || s.source().equals("none") ? indexUrl() : s.source();
  }

  /** Downloads and verifies the index; keeps it when it is good and not older than the last good one. */
  public synchronized State refresh() {
    loaded();
    lastAttempt = clock.instant();
    if (!configured()) {
      log.debug("No catalog URL is configured; the Catalog stays offline");
      return markOffline("none", "No catalog is configured for this build.", null);
    }
    String url = indexUrl();
    byte[] index;
    byte[] sig;
    try {
      HttpUrl indexAt = CatalogDownloads.https(url, "The catalog");
      index = downloads.get(indexAt, MAX_INDEX_BYTES, TIMEOUT, "application/json, */*;q=0.5", "index.json");
      sig = downloads.get(indexAt.resolve("index.json.sig"), MAX_SIG_BYTES, TIMEOUT, "application/json, */*;q=0.5",
          "index.json.sig");
    } catch (CatalogDownloads.DownloadException e) {
      log.info("The catalog couldn't be checked: {}", e.getMessage());
      return markOffline(url, "The catalog can't be reached: " + e.getMessage(), null);
    }
    try {
      return accept(index, sig, url);
    } catch (CatalogVerifier.RejectedException e) {
      log.warn("The catalog at {} was refused: {}", url, e.getMessage());
      return markOffline(url, e.getMessage(), Boolean.FALSE);
    }
  }

  /**
   * Accepts a signed index from bytes (a refresh, or a test): verified, not older than the highest sequence the core
   * vouches for, then stored and kept. Accepting one is what makes a stored state the core couldn't vouch for trusted
   * again.
   */
  public synchronized State accept(byte[] indexJson, byte[] sigJson, String source)
      throws CatalogVerifier.RejectedException {
    loaded();
    CatalogVerifier.Signature signature = CatalogVerifier.signature(sigJson);
    CatalogVerifier.Verified verified = verifier.verify(indexJson, signature, floor);
    Instant checkedAt = clock.instant();
    persist(indexJson, sigJson, source, checkedAt, verified.sequence());
    State s = new State(checkedAt, source, false, new SignatureInfo(signature.keyId(), verified.sequence(),
        verified.expiresAt(), true), verified, null);
    state.set(s);
    floor = Math.max(floor, verified.sequence());
    trusted = true;
    alerts.resolve(SettingStore.ALERT_KIND, SETTING);
    return s;
  }

  /**
   * The start that applies V87 to an older database ({@code LegacyRows}): signs the stored index as the older version
   * kept it and records its sequence as the highest accepted one. Inside the caller's write transaction.
   *
   * @return what was signed, for the Setup change, or {@code null} when nothing was stored
   */
  synchronized String signLegacyRows() {
    if (!settings.signLegacy(SETTING)) {
      return null;
    }
    long sequence = settings.find(SETTING).map(s -> genuine(s.value())).map(g -> g.verified().sequence())
        .orElse(-1L);
    // A database rolled back to before V87 may still hold the sequence it reached then: never lower it.
    long kept = settings.find(SEQUENCE).map(s -> s.value().path("sequence").asLong(-1)).orElse(-1L);
    settings.put(SEQUENCE, JSON.createObjectNode().put("sequence", Math.max(sequence, kept)), null);
    // Read again at the next use, in case something looked before the signing.
    loaded = false;
    return "the catalog's last good index";
  }

  /** The stored state, read once; again after a locked start, whose rows couldn't be checked, once the key opens. */
  private State loaded() {
    if (!loaded) {
      synchronized (this) {
        if (!loaded) {
          loadLastGood();
          loaded = !lock.locked();
        }
      }
    }
    return state.get();
  }

  private void loadLastGood() {
    Optional<SettingStore.Setting> stored = settings.find(SETTING);
    Optional<SettingStore.Setting> sequence = settings.find(SEQUENCE);
    long vouched = sequence.filter(SettingStore.Setting::verified)
        .map(s -> s.value().path("sequence").asLong(-1)).orElse(-1L);
    // As the core left them: both rows verify, or neither exists (every accepted index writes both).
    boolean intact = stored.isEmpty() ? sequence.isEmpty()
        : stored.get().verified() && sequence.map(SettingStore.Setting::verified).orElse(false);
    Genuine g = stored.map(s -> genuine(s.value())).orElse(null);
    boolean older = g != null && g.verified().sequence() < vouched;
    trusted = intact && g != null && !older;
    floor = Math.max(vouched, g == null ? -1 : g.verified().sequence());
    if ((!intact || older) && !lock.locked()) {
      alerts.report(SettingStore.ALERT_KIND, SETTING, "The catalog", "nothing installs or updates from the stored "
          + "catalog until a new one is checked in Connectors › Catalog");
    }
    if (g == null) {
      // Nothing stored, or an index the catalog's keys no longer verify (another release's keys): nothing to show.
      state.set(null);
      return;
    }
    JsonNode v = stored.get().value();
    CatalogVerifier.Verified verified = g.verified();
    boolean fresh = !expired(verified);
    String problem = !trusted ? UNTRUSTED + " Nothing installs or updates from it until a new one is checked."
        : fresh ? null : expiredText(verified);
    // What the core can't vouch for, it doesn't repeat: no time of a check, and artifacts resolve against the
    // configured catalog rather than the stored address.
    state.set(new State(trusted ? instant(v.path("checkedAt").asString(null)) : null, trusted
        ? v.path("source").asString("") : indexUrl(), true, new SignatureInfo(g.signature().keyId(),
        verified.sequence(), verified.expiresAt(), trusted && fresh), verified, problem));
  }

  /** A stored index whose signature holds, with that signature. */
  private record Genuine(CatalogVerifier.Signature signature, CatalogVerifier.Verified verified) {}

  /**
   * The stored index verified again with the catalog's keys, so that an edited setting can't smuggle one in; an expired
   * one still counts (shown, refused for installs until a fresh one comes). {@code null} when it doesn't verify.
   */
  private Genuine genuine(JsonNode v) {
    try {
      byte[] index = Base64.getDecoder().decode(v.path("index").asString(""));
      byte[] sig = Base64.getDecoder().decode(v.path("sig").asString(""));
      CatalogVerifier.Signature signature = CatalogVerifier.signature(sig);
      return new Genuine(signature, verifier.verify(index, signature, -1, true));
    } catch (CatalogVerifier.RejectedException | RuntimeException e) {
      log.warn("The stored catalog couldn't be used: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The last good index, marked offline with why; {@code signatureOk} {@code false} when the new index was refused
   * (the UI then says nothing of the new one is offered), {@code null} to keep the signature as it was.
   */
  private State markOffline(String source, String problem, Boolean signatureOk) {
    State s = state.get();
    if (s != null && s.verified() != null) {
      SignatureInfo signature = s.signature();
      if (signatureOk != null && signature != null) {
        signature = new SignatureInfo(signature.keyId(), signature.sequence(), signature.expiresAt(), signatureOk);
      }
      State offline = new State(s.checkedAt(), s.source(), true, signature, s.verified(),
          trusted ? problem : UNTRUSTED + " " + problem);
      state.set(offline);
      return state();
    }
    State empty = new State(null, source, true, signatureOk == null ? null : new SignatureInfo(null, 0, null,
        signatureOk), null, problem);
    state.set(empty);
    return empty;
  }

  /** The index and the sequence it reached, in one transaction: a stored index never outruns its sequence. */
  private void persist(byte[] indexJson, byte[] sigJson, String source, Instant checkedAt, long sequence) {
    ObjectNode value = JSON.createObjectNode();
    value.put("index", Base64.getEncoder().encodeToString(indexJson));
    value.put("sig", Base64.getEncoder().encodeToString(sigJson));
    value.put("source", source);
    value.put("checkedAt", checkedAt.toString());
    ObjectNode reached = JSON.createObjectNode().put("sequence", sequence);
    writeTx.executeWithoutResult(s -> {
      settings.put(SEQUENCE, reached, null);
      settings.put(SETTING, value, null);
    });
  }

  private String indexUrl() {
    if (!configured()) {
      return "none";
    }
    return baseUrl.endsWith("index.json") ? baseUrl : baseUrl.endsWith("/") ? baseUrl + "index.json"
        : baseUrl + "/index.json";
  }

  private boolean expired(CatalogVerifier.Verified v) {
    return v.expiresAt() != null && v.expiresAt().isBefore(clock.instant());
  }

  private static String expiredText(CatalogVerifier.Verified v) {
    return "The catalog signature expired on " + v.expiresAt() + ".";
  }

  public static String entryVersion(JsonNode entry) {
    String v = entry.path("version").asString(null);
    return v != null ? v : entry.path("displayVersion").asString(null);
  }

  private static Instant instant(String text) {
    try {
      return text == null ? null : Instant.parse(text);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
