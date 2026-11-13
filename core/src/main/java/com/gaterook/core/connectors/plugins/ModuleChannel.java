package com.gaterook.core.connectors.plugins;

import com.gaterook.connector.api.ApiVersion;
import com.gaterook.core.platform.CoreBuild;
import com.gaterook.plugin.protocol.Semver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The channel GateRook's own connector modules come from (docs/connector-delivery.md): a signed index that lists
 * each module's versions with the connector types it adds and pins each package by size and SHA-256. It is the
 * plugin catalog's machinery ({@link PluginCatalog}: an Ed25519 signature over the raw index, a sequence that never
 * goes back, an expiry, revocations) under keys and settings of its own, because what it vouches for is different:
 * a module's connectors are trusted as the compiled-in ones are, and install without a person's review.
 *
 * <p>An entry of the index:
 * <pre>
 * {"kind": "module", "id": "cassandra", "version": "1.0.0", "name": "Apache Cassandra",
 *  "requires": {"gateway": ">=0.10.0 &amp; &lt;1.0.0", "connectorApi": ">=2.0.0 &amp; &lt;3.0.0"},
 *  "artifact": {"url": "cassandra-1.0.0.jar", "sha256": "…", "size": 9871234},
 *  "types": [{"id": "cassandra", "name": "Cassandra", "icon": "cassandra", "group": "Databases",
 *             "description": "…", "tools": ["cassandra_query"]}]}
 * </pre>
 * Several versions of a module may be listed, each for the application versions it runs with: this build takes the
 * highest one whose {@code requires} it satisfies, so an application that isn't updated keeps a module it can run.
 */
public final class ModuleChannel {

  /** A module's package may take this long to arrive (150 MB at 2 Mbit/s). */
  static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

  /** A connector type a listed module adds: what a card shows before the module is installed. */
  public record TypeInfo(String id, String name, String icon, String group, String description,
      List<String> tools) {}

  /** One version of a module as the index lists it. */
  public record Entry(String id, String version, String name, String description, long size, String sha256,
      List<TypeInfo> types, JsonNode raw) {

    String ref() {
      return id + "@" + version;
    }
  }

  private final PluginCatalog index;
  private final PluginArtifacts artifacts;
  /** Told when a check accepted another list than the one held before. */
  private volatile Runnable onChange = () -> {};
  /** The sequence of the list the listener last heard of; {@link #UNSET} until the first check. */
  private final java.util.concurrent.atomic.AtomicLong told = new java.util.concurrent.atomic.AtomicLong(UNSET);
  private static final long UNSET = Long.MIN_VALUE;

  public ModuleChannel(PluginCatalog index, PluginArtifacts artifacts) {
    this.index = index;
    this.artifacts = artifacts;
  }

  /**
   * {@code listener} runs whenever a check brings another list than the one held before, the first one included:
   * what the channel offers changed. It runs on the thread that checked, after the list was accepted.
   */
  void onChange(Runnable listener) {
    this.onChange = listener;
  }

  /** Whether this build has a module channel at all. */
  public boolean configured() {
    return index.configured();
  }

  /** The index's state, checked again first when the last check is old. */
  public PluginCatalog.State state() {
    return noting(index::stateRefreshingIfStale);
  }

  /** The index's state as it is, without a check. */
  public PluginCatalog.State cached() {
    return index.state();
  }

  public PluginCatalog.State refresh() {
    return noting(index::refresh);
  }

  /**
   * Runs a check and tells the listener when it left another list than the one the listener heard of. Checks wait
   * for one another, so two callers of one download both come back with the new list; the listener is told once.
   */
  private PluginCatalog.State noting(java.util.function.Supplier<PluginCatalog.State> check) {
    // Before the first check, what is held from the last run is what the listener knows.
    told.compareAndSet(UNSET, sequence(index.state()));
    PluginCatalog.State after = check.get();
    long now = sequence(after);
    if (told.getAndSet(now) != now) {
      onChange.run();
    }
    return after;
  }

  /** The sequence of the list a state holds, or -1 when it holds none. */
  private static long sequence(PluginCatalog.State state) {
    return state == null || state.verified() == null ? -1 : state.verified().sequence();
  }

  /** Why nothing installs from the channel now, or empty. */
  public Optional<String> installProblem() {
    return index.installProblem();
  }

  public boolean revoked(String sha256) {
    return index.isRevoked(sha256);
  }

  public Optional<String> revocationReason(String sha256) {
    return index.revocationReason(sha256);
  }

  /** The index itself, for tests and for accepting one from bytes. */
  PluginCatalog index() {
    return index;
  }

  /** The best version of every listed module that this build can run, by module id. */
  public List<Entry> modules() {
    Map<String, Entry> best = new LinkedHashMap<>();
    for (Entry e : entries()) {
      Entry other = best.get(e.id());
      if (other == null || compare(e.version(), other.version()) > 0) {
        best.put(e.id(), e);
      }
    }
    return List.copyOf(best.values());
  }

  /** The best version of module {@code id} this build can run. */
  public Optional<Entry> module(String id) {
    return modules().stream().filter(e -> e.id().equals(id)).findFirst();
  }

  /** Every listed version of module {@code id} that this build can run, the highest first. */
  public List<Entry> versions(String id) {
    return entries().stream().filter(e -> e.id().equals(id))
        .sorted((a, b) -> compare(b.version(), a.version())).toList();
  }

  /** Whether {@code a} is a higher version than {@code b}. */
  public static boolean newer(String a, String b) {
    return compare(a, b) > 0;
  }

  /** The listed module that adds the connector type {@code typeId}, at the best version this build can run. */
  public Optional<Entry> ofType(String typeId) {
    return modules().stream().filter(e -> e.types().stream().anyMatch(t -> t.id().equals(typeId))).findFirst();
  }

  /** Exactly {@code id@version}, if the index lists it and this build can run it. */
  public Optional<Entry> exact(String id, String version) {
    return entries().stream().filter(e -> e.id().equals(id) && e.version().equals(version)).findFirst();
  }

  /** Every tool name of every listed module: names only GateRook's own connectors may use. */
  public Set<String> toolNames() {
    Set<String> names = new TreeSet<>();
    index.current().ifPresent(v -> {
      for (JsonNode e : v.index().path("entries")) {
        if ("module".equals(e.path("kind").asString(""))) {
          e.path("types").forEach(t -> t.path("tools").forEach(n -> names.add(n.asString())));
        }
      }
    });
    return Set.copyOf(names);
  }

  /** The package of {@code entry}, verified against the size and SHA-256 the signed index pins. */
  public byte[] download(Entry entry) {
    return artifacts.fetchPinned(entry.raw().path("artifact"), PluginInstaller.MAX_MODULE_BYTES, entry.name(),
        "application/java-archive, */*;q=0.5", DOWNLOAD_TIMEOUT);
  }

  /** The file name of an entry's package. */
  static String fileName(Entry entry) {
    return PluginArtifacts.fileName(entry.raw(), entry.id() + "-" + entry.version() + ".jar");
  }

  /** The entries read from one index, kept until another index is accepted. */
  private record Read(JsonNode index, List<Entry> entries) {}

  private volatile Read read;

  /** The module entries of the current index that this build can run. */
  private List<Entry> entries() {
    JsonNode current = index.current().map(CatalogVerifier.Verified::index).orElse(null);
    Read known = read;
    if (known != null && known.index() == current) {
      return known.entries();
    }
    List<Entry> entries = current == null ? List.of() : List.copyOf(parse());
    read = new Read(current, entries);
    return entries;
  }

  private List<Entry> parse() {
    List<Entry> out = new ArrayList<>();
    index.current().ifPresent(v -> {
      for (JsonNode e : v.index().path("entries")) {
        if (!"module".equals(e.path("kind").asString("")) || !runs(e.path("requires"))) {
          continue;
        }
        String id = e.path("id").asString("");
        String version = e.path("version").asString("");
        String sha256 = e.path("artifact").path("sha256").asString("");
        if (id.isEmpty() || !Semver.valid(version) || sha256.isEmpty()) {
          continue;
        }
        List<TypeInfo> types = new ArrayList<>();
        for (JsonNode t : e.path("types")) {
          List<String> tools = new ArrayList<>();
          t.path("tools").forEach(n -> tools.add(n.asString()));
          types.add(new TypeInfo(t.path("id").asString(""), t.path("name").asString(t.path("id").asString("")),
              t.path("icon").asString(null), t.path("group").asString("Other"), t.path("description").asString(""),
              List.copyOf(tools)));
        }
        out.add(new Entry(id, version, e.path("name").asString(id), e.path("description").asString(""),
            e.path("artifact").path("size").asLong(-1), sha256, List.copyOf(types), e));
      }
    });
    return out;
  }

  /** Whether this build satisfies an entry's {@code requires}. */
  private static boolean runs(JsonNode requires) {
    String gateway = requires.path("gateway").asString(null);
    String api = requires.path("connectorApi").asString(null);
    try {
      return (gateway == null || Semver.satisfies(Semver.parse(CoreBuild.VERSION).release(), gateway))
          && (api == null || Semver.satisfies(Semver.parse(ApiVersion.CURRENT), api));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static int compare(String a, String b) {
    return Semver.parse(a).compareTo(Semver.parse(b));
  }
}
