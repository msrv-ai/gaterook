package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.common.config.keys.KeyUtils;

/**
 * Help for the SSH and SSH log files forms ({@link ai.msrv.gaterook.connector.api.Connector#suggest}), from this
 * computer only: the hosts of {@code ~/.ssh/config} (an alias offered with the address it stands for, since the
 * connector connects to addresses and never reads that file) and of {@code known_hosts}, an SSH server on loopback,
 * the private key files in {@code ~/.ssh} and this computer's user name. For a host {@code ~/.ssh/config} knows it
 * fills in the user, port, key file and jump host it sets, as {@code ssh <host>} would use them. A key file is offered
 * because it exists, never read; a host key is never trusted for a suggestion: a note names the fingerprint
 * {@code known_hosts} has for the host, to compare when Test connection asks.
 */
final class SshSuggestions {

  /** Which form: SSH (runbooks, a bastion, Vault SSH CA) or SSH log files (password or key file, no bastion). */
  enum Form { RUNBOOKS, LOGS }

  static final String CONFIG = "~/.ssh/config";
  static final String KEYS = "~/.ssh";
  static final String COMPUTER = "this computer";
  static final String TYPED = "what you typed";

  private static final int MAX_KEYS = 20;
  private static final long MAX_KEY_BYTES = 20_000;
  /** The keys OpenSSH tries when no {@code IdentityFile} is set, that GateRook can use (no FIDO, no DSA). */
  private static final List<String> DEFAULT_KEYS = List.of("id_ed25519", "id_ecdsa", "id_rsa");
  /** Files of {@code ~/.ssh} that are never private keys, whatever lies next to them. */
  private static final Set<String> NOT_KEYS = Set.of("config", "known_hosts", "known_hosts.old", "authorized_keys",
      "authorized_keys2", "environment", "rc");
  /** Code hosting services in {@code known_hosts}: Git over SSH, never a server for runbooks or logs. */
  private static final Set<String> CODE_HOSTS = Set.of("github.com", "ssh.github.com", "gitlab.com",
      "altssh.gitlab.com", "bitbucket.org", "altssh.bitbucket.org", "ssh.dev.azure.com", "vs-ssh.visualstudio.com",
      "source.developers.google.com", "codeberg.org", "gitee.com", "git.sr.ht", "heroku.com");
  private static final Pattern USER = Pattern.compile("^[a-z_][a-z0-9_.-]{0,31}$");
  /** {@code deploy@10.0.0.5}: the user typed into Host, as {@code ssh} takes it. */
  private static final Pattern USER_AT = Pattern.compile("^([a-z_][a-z0-9_.-]{0,31})@([^@\\s]+)$");
  /** {@code 10.0.0.5:2222} or {@code host:2222} (one colon, so not an IPv6 address). */
  private static final Pattern HOST_PORT = Pattern.compile("^([^:\\s]+):(\\d{1,5})$");
  private static final int BANNER_CONNECT_MS = 150;
  private static final int BANNER_READ_MS = 250;

  private SshSuggestions() {}

  /** What this computer knows for the form: no network but loopback, no command, no key read. */
  static Suggestions local(ConnectionConfig cfg, SecretResolver secrets, SshHome home, IntPredicate loopbackSsh,
      Form form) {
    Suggestions.Builder out = Suggestions.builder();
    OpenSshConfig config = OpenSshConfig.read(home);
    LocalKnownHosts known = LocalKnownHosts.read(home);
    List<Alias> aliases = new ArrayList<>();
    for (String a : config.aliases()) {
      aliases.add(new Alias(a, config.resolve(a)));
    }
    List<Integer> loopback = new ArrayList<>();
    for (int port : new int[] {22, 2222}) {
      if (loopbackSsh.test(port)) {
        loopback.add(port);
      }
    }
    Offered hosts = hosts(aliases, known, loopback);
    out.options("/connection/host", hosts.source(), hosts.options());

    String text = text(cfg, "/connection/host");
    Typed typed = text == null ? null : Typed.of(text, out);
    Found found = typed == null ? null : find(config, aliases, typed.host());

    // User: what was typed with the host, or what ~/.ssh/config sets; for a host it knows without a User, the user
    // ssh would take (this computer's).
    String configUser = found == null || !validUser(found.resolved().user()) ? null : found.resolved().user();
    String localUser = validUser(home.localUser()) ? home.localUser() : null;
    String user = typed != null && typed.user() != null ? typed.user() : configUser != null ? configUser
        : found != null && found.alias() != null ? localUser : null;
    List<Option> users = new ArrayList<>();
    if (typed != null && typed.user() != null) {
      users.add(Option.of(typed.user(), "typed under Host"));
    }
    if (configUser != null && users.stream().noneMatch(o -> o.value().equals(configUser))) {
      users.add(Option.of(configUser, found.what()));
    }
    if (localUser != null && users.stream().noneMatch(o -> o.value().equals(localUser))) {
      users.add(Option.of(localUser, "your user on this computer"));
    }
    String userSource = typed != null && typed.user() != null ? TYPED
        : user != null && user.equals(configUser) ? CONFIG : COMPUTER;
    out.value("/connection/username", userSource, user, users);

    Integer configPort = found == null ? null : found.resolved().port();
    if (typed != null && typed.port() != null) {
      out.value("/connection/port", TYPED, String.valueOf(typed.port()),
          List.of(Option.of(String.valueOf(typed.port()))));
    } else if (configPort != null) {
      out.value("/connection/port", CONFIG, String.valueOf(configPort),
          List.of(Option.of(String.valueOf(configPort), found.what())));
    } else if (typed != null && isLoopback(typed.host()) && !loopback.isEmpty() && !loopback.contains(22)) {
      out.value("/connection/port", COMPUTER, String.valueOf(loopback.getFirst()),
          List.of(Option.of(String.valueOf(loopback.getFirst()), "An SSH server answers here")));
    }

    // Key files: the host's IdentityFile; without one the only default key (ssh would try it); the rest offered.
    List<Path> keys = keyFiles(home);
    Path key = null;
    boolean keyFromConfig = false;
    if (found != null && !found.resolved().identityFiles().isEmpty()) {
      key = firstKey(found.resolved().identityFiles());
      keyFromConfig = key != null;
      if (key == null) {
        out.note(CONFIG + " names no private key file on this computer for " + found.name() + ": choose one under "
            + "Private key file.");
      }
    } else {
      List<Path> defaults = keys.stream().filter(k -> DEFAULT_KEYS.contains(k.getFileName().toString())).toList();
      key = defaults.size() == 1 ? defaults.getFirst() : null;
    }
    List<Option> keyOptions = new ArrayList<>();
    if (key != null) {
      keyOptions.add(Option.of(home.shown(key), keyFromConfig ? found.what() : null));
    }
    for (Path k : keys) {
      if (!k.equals(key)) {
        keyOptions.add(Option.of(home.shown(k)));
      }
    }
    out.value("/credentials/privateKeyPath", keyFromConfig ? CONFIG : KEYS, key == null ? null : home.shown(key),
        keyOptions);
    method(out, cfg, secrets, form, !keyOptions.isEmpty());

    String jump = null;
    if (form == Form.RUNBOOKS) {
      Option bastion = found == null ? null : jump(out, config, found, user, key, home);
      // The bastion ~/.ssh/config names for the host, then the same hosts as under Host (not loopback's).
      Offered jumps = hosts(aliases, known, List.of());
      List<Option> options = new ArrayList<>();
      if (bastion != null) {
        options.add(bastion);
        jump = bastion.value();
      }
      jumps.options().stream().filter(o -> bastion == null || !o.value().equals(bastion.value()))
          .forEach(options::add);
      out.value("/jump/host", bastion != null ? CONFIG : jumps.source(), jump, options);
    } else if (found != null && (found.resolved().proxyJump() != null || found.resolved().proxyCommand() != null)) {
      out.note(found.name() + " goes through a jump host in " + CONFIG + ": SSH log files connects to the host "
          + "directly, so it has to be reachable from this computer.");
    }
    if (found != null) {
      summary(out, found, user != null && user.equals(configUser) ? user : null, configPort,
          keyFromConfig ? key : null, jump, home);
    }
    if (typed != null) {
      String target = found != null ? found.resolved().host() : typed.host();
      int port = typed.port() != null ? typed.port()
          : cfg.integer("/connection/port", configPort == null ? 22 : configPort);
      fingerprints(out, known, target, port, cfg);
    }
    return out.build();
  }

  /**
   * The typed Host and what it says beyond an address: {@code deploy@host} (the user goes under User) or
   * {@code host:2222} (the port under Port), as {@code ssh} takes them.
   */
  private record Typed(String host, String user, Integer port) {
    static Typed of(String text, Suggestions.Builder out) {
      Matcher at = USER_AT.matcher(text);
      if (at.matches() && OpenSshConfig.validHost(at.group(2))) {
        out.note("Host takes only the address: " + at.group(1) + " goes under User, " + at.group(2)
            + " under Host.");
        return new Typed(at.group(2), at.group(1), null);
      }
      Matcher hp = HOST_PORT.matcher(text);
      if (hp.matches() && OpenSshConfig.validHost(hp.group(1))) {
        int port = Integer.parseInt(hp.group(2));
        if (port >= 1 && port <= 65535) {
          out.note("Host takes only the address: " + hp.group(1) + " goes under Host, " + port + " under Port.");
          return new Typed(hp.group(1), null, port);
        }
      }
      return new Typed(text, null, null);
    }
  }

  /** A concrete {@code Host} alias and what it resolves to. */
  private record Alias(String name, OpenSshConfig.Resolved resolved) {}

  /**
   * The settings {@code ~/.ssh/config} has for the typed host.
   *
   * @param alias      the {@code Host} alias the host is, or whose {@code HostName} it is; {@code null} when only
   *                   patterns ({@code Host 10.0.*}, {@code Host *}) matched the address itself
   * @param typedAlias the person typed the alias, which names another address
   */
  private record Found(String typed, String alias, OpenSshConfig.Resolved resolved, boolean typedAlias) {
    String name() {
      return alias != null ? alias : typed;
    }

    String what() {
      return alias != null ? CONFIG + ", Host " + alias : CONFIG;
    }
  }

  private static Found find(OpenSshConfig config, List<Alias> aliases, String typed) {
    for (Alias a : aliases) {
      if (a.name().equalsIgnoreCase(typed)) {
        return new Found(typed, a.name(), a.resolved(), !a.resolved().host().equalsIgnoreCase(a.name()));
      }
    }
    // An alias picked under Host: its address is what the field holds.
    for (Alias a : aliases) {
      if (a.resolved().host().equalsIgnoreCase(typed)) {
        return new Found(typed, a.name(), a.resolved(), false);
      }
    }
    OpenSshConfig.Resolved r = config.resolve(typed);
    return r.any() ? new Found(typed, null, r, false) : null;
  }

  /** Hosts to offer and where they come from ("~/.ssh/config and known_hosts"). */
  private record Offered(List<Option> options, String source) {}

  /** The hosts for Host: the aliases first (with the address they stand for), then known_hosts, then loopback. */
  private static Offered hosts(List<Alias> aliases, LocalKnownHosts known, List<Integer> loopback) {
    List<Option> options = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    List<String> sources = new ArrayList<>();
    for (Alias a : aliases) {
      String value = a.resolved().host();
      if (OpenSshConfig.validHost(value) && seen.add(value.toLowerCase(Locale.ROOT))) {
        options.add(new Option(value, value.equalsIgnoreCase(a.name()) ? null : a.name(), detail(a.resolved())));
      }
    }
    if (!options.isEmpty()) {
      sources.add(CONFIG);
    }
    int before = options.size();
    for (LocalKnownHosts.Known k : known.hosts()) {
      if (!CODE_HOSTS.contains(k.host()) && seen.add(k.host())) {
        options.add(new Option(k.host(), null, k.port() == 22 ? null : "port " + k.port()));
      }
    }
    if (options.size() > before) {
      sources.add(sources.isEmpty() ? "~/.ssh/known_hosts" : "known_hosts");
    }
    if (!loopback.isEmpty() && seen.add("localhost")) {
      options.add(new Option("localhost", null, "An SSH server answers on port " + loopback.getFirst()));
      sources.add(COMPUTER);
    }
    return new Offered(options, and(sources));
  }

  /** {@code deploy@10.0.0.5:2222 via bastion}: what an alias sets, as the line under Host shows it. */
  private static String detail(OpenSshConfig.Resolved r) {
    StringBuilder s = new StringBuilder();
    if (r.user() != null) {
      s.append(r.user()).append('@');
    }
    s.append(r.host());
    if (r.port() != null && r.port() != 22) {
      s.append(':').append(r.port());
    }
    if (r.proxyJump() != null) {
      s.append(" via ").append(r.proxyJump());
    }
    return s.toString();
  }

  /**
   * The sign-in method, filled only while it holds the form's default: a private key file when this computer has one
   * and nothing was set up for the default (a Vault store named for SSH, a password typed for SSH log files).
   */
  private static void method(Suggestions.Builder out, ConnectionConfig cfg, SecretResolver secrets, Form form,
      boolean anyKey) {
    if (!anyKey) {
      return;
    }
    String method = text(cfg, "/credentials/method");
    boolean atDefault = form == Form.RUNBOOKS
        ? (method == null || "vaultSshCa".equals(method)) && text(cfg, "/credentials/vault/store") == null
        : (method == null || "password".equals(method))
            && (secrets == null || !secrets.isSet("/credentials/password"));
    if (atDefault) {
      out.value("/credentials/method", KEYS, "privateKeyFile", List.of());
    }
  }

  /**
   * The bastion of {@code ProxyJump} (SSH only): its port and user, as {@code ssh -J} would use them, and its own key
   * file when it isn't the host's. One jump host only: GateRook connects through one bastion.
   *
   * @return the bastion to fill in under Jump host, or {@code null}
   */
  private static Option jump(Suggestions.Builder out, OpenSshConfig config, Found found, String user, Path key,
      SshHome home) {
    OpenSshConfig.Resolved r = found.resolved();
    if (r.proxyCommand() != null) {
      out.note(found.name() + " connects through a ProxyCommand in " + CONFIG + ", which GateRook doesn't run: if it "
          + "is a jump host, set it under Bastion.");
      return null;
    }
    if (r.proxyJump() == null) {
      return null;
    }
    List<String> hops = List.of(r.proxyJump().split(","));
    if (hops.size() > 1) {
      out.note(found.name() + " goes through " + hops.size() + " jump hosts (" + r.proxyJump() + "): GateRook "
          + "connects through one bastion, so set the one it can reach directly under Bastion.");
      return null;
    }
    Hop hop = Hop.parse(hops.getFirst().strip());
    if (hop == null) {
      return null;
    }
    OpenSshConfig.Resolved b = config.resolve(hop.host());
    if (b.proxyJump() != null || b.proxyCommand() != null) {
      out.note("The jump host " + hop.host() + " of " + found.name() + " has a jump host of its own in " + CONFIG
          + ": GateRook connects through one bastion.");
      return null;
    }
    String host = b.host();
    if (!OpenSshConfig.validHost(host)) {
      return null;
    }
    int port = hop.port() != null ? hop.port() : b.port() != null ? b.port() : 22;
    if (port != 22) {
      out.value("/jump/port", CONFIG, String.valueOf(port), List.of(Option.of(String.valueOf(port))));
    }
    // ssh -J signs in to the jump host as its own User, else as this computer's user, not as the host's user.
    String jumpUser = hop.user() != null ? hop.user() : b.user() != null ? b.user() : home.localUser();
    if (validUser(jumpUser) && !jumpUser.equals(user)) {
      out.value("/jump/username", CONFIG, jumpUser, List.of(Option.of(jumpUser)));
    }
    Path jumpKey = b.identityFiles().isEmpty() ? null : firstKey(b.identityFiles());
    if (jumpKey != null && !jumpKey.equals(key)) {
      out.value("/jump/credentials/method", CONFIG, "privateKeyFile", List.of());
      out.value("/jump/credentials/privateKeyPath", CONFIG, home.shown(jumpKey),
          List.of(Option.of(home.shown(jumpKey), CONFIG + ", Host " + hop.host())));
    }
    return new Option(host, host.equalsIgnoreCase(hop.host()) ? null : hop.host(), "ProxyJump of " + found.name());
  }

  /** One hop of {@code ProxyJump}: {@code [user@]host[:port]} or {@code ssh://[user@]host[:port]}. */
  private record Hop(String user, String host, Integer port) {
    static Hop parse(String text) {
      String t = text.startsWith("ssh://") ? text.substring(6) : text;
      if (t.endsWith("/")) {
        t = t.substring(0, t.length() - 1);
      }
      String user = null;
      int at = t.lastIndexOf('@');
      if (at >= 0) {
        user = t.substring(0, at);
        t = t.substring(at + 1);
      }
      Integer port = null;
      if (t.startsWith("[")) {
        int end = t.indexOf(']');
        if (end < 0) {
          return null;
        }
        String rest = t.substring(end + 1);
        t = t.substring(1, end);
        if (rest.startsWith(":")) {
          port = port(rest.substring(1));
        }
      } else if (t.indexOf(':') > 0 && t.indexOf(':') == t.lastIndexOf(':')) {
        port = port(t.substring(t.indexOf(':') + 1));
        t = t.substring(0, t.indexOf(':'));
      }
      return OpenSshConfig.validHost(t) ? new Hop(user, t.toLowerCase(Locale.ROOT), port) : null;
    }

    private static Integer port(String text) {
      try {
        int p = Integer.parseInt(text);
        return p >= 1 && p <= 65535 ? p : null;
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  /** "From ~/.ssh/config for prod-web: user deploy, port 2222, key ~/.ssh/id_work, through bastion.example.com." */
  private static void summary(Suggestions.Builder out, Found found, String user, Integer port, Path key, String jump,
      SshHome home) {
    List<String> parts = new ArrayList<>();
    if (user != null) {
      parts.add("user " + user);
    }
    if (port != null) {
      parts.add("port " + port);
    }
    if (key != null) {
      parts.add("key " + home.shown(key));
    }
    if (jump != null) {
      parts.add("through " + jump);
    }
    if (!parts.isEmpty()) {
      out.note("From " + CONFIG + " for " + found.name() + ": " + String.join(", ", parts) + ".");
    }
    if (found.typedAlias()) {
      out.note(found.alias() + " is an alias in " + CONFIG + ", which GateRook doesn't read when it connects: choose "
          + found.alias() + " under Host to use its address " + found.resolved().host() + ".");
    }
  }

  /**
   * The fingerprints {@code known_hosts} has for the host while the connection trusts no key for it: what to compare
   * when Test connection shows the host's key. Nothing is trusted here.
   */
  private static void fingerprints(Suggestions.Builder out, LocalKnownHosts known, String host, int port,
      ConnectionConfig cfg) {
    if (cfg.config().path("hostKeys").path("pinned").size() > 0
        || !cfg.config().path("hostKeys").path("knownHosts").asString("").isBlank()) {
      return;
    }
    List<PublicKey> keys = known.keys(host, port);
    if (keys.isEmpty()) {
      return;
    }
    List<String> named = new ArrayList<>();
    for (PublicKey k : keys.subList(0, Math.min(2, keys.size()))) {
      named.add(KeyUtils.getKeyType(k) + " " + HostKeys.fingerprint(k));
    }
    String address = port == 22 ? host : "[" + host + "]:" + port;
    out.note("This computer's ~/.ssh/known_hosts has " + String.join(" and ", named) + " for " + address + ". Test "
        + "connection shows the key the host presents: trust it only if it matches.");
  }

  /**
   * SSH: what the service step can say without signing in. A host key nobody trusted yet stops sign-in, so it says to
   * test first; nothing is trusted, nothing is sent.
   */
  static Suggestions hostKeyNotes(ConnectionConfig cfg) {
    Suggestions.Builder out = Suggestions.builder();
    SshSettings s;
    try {
      s = SshSettings.of(cfg);
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    try {
      if (s.jump() != null && !trusted(HostKeys.check(s.jump().target(), s.jump().hostKeys()))) {
        out.note(untrusted(s.jump().target(), true));
      }
      if (!trusted(HostKeys.check(s.target(), s.hostKeys()))) {
        out.note(untrusted(s.target(), false));
      }
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    }
    return out.build();
  }

  /** Whether the connection trusts a key for the host: one pinned, or a {@code known_hosts} line that names it. */
  static boolean trusted(HostKeys.Check check) {
    if (!check.pinned.isEmpty()) {
      return true;
    }
    for (KnownHostEntry e : check.known) {
      if (e.getMarker() == null && e.isHostMatch(check.host, check.port)) {
        return true;
      }
    }
    return false;
  }

  static String untrusted(SshSettings.Target target, boolean bastion) {
    return "The host key of " + (bastion ? "the bastion " : "") + target.address() + " isn't trusted yet: Test "
        + "connection shows its fingerprint to compare before you trust it.";
  }

  /**
   * The private key files of {@code ~/.ssh}, without reading them: the default names ({@code id_ed25519},
   * {@code id_ecdsa}, {@code id_rsa}) first, then any file with its {@code .pub} next to it. FIDO keys ({@code _sk})
   * and DSA keys aren't offered: GateRook can't sign in with them.
   */
  static List<Path> keyFiles(SshHome home) {
    Path dir = home.ssh();
    List<Path> out = new ArrayList<>();
    for (String name : DEFAULT_KEYS) {
      Path p = dir.resolve(name);
      if (usableKey(p)) {
        out.add(p);
      }
    }
    List<Path> others = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
      int seen = 0;
      for (Path p : files) {
        if (++seen > 500) {
          break;
        }
        String name = p.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (DEFAULT_KEYS.contains(name) || NOT_KEYS.contains(lower) || lower.endsWith(".pub") || lower.endsWith("_sk")
            || lower.equals("id_dsa") || lower.startsWith(".")) {
          continue;
        }
        if ((name.startsWith("id_") || Files.isRegularFile(dir.resolve(name + ".pub"))) && usableKey(p)) {
          others.add(p);
        }
      }
    } catch (IOException | RuntimeException e) {
      // No ~/.ssh folder: only what the defaults found.
    }
    others.sort(null);
    for (Path p : others) {
      if (out.size() >= MAX_KEYS) {
        break;
      }
      out.add(p);
    }
    return out;
  }

  /** The first of {@code files} that is a usable private key file of this computer. */
  private static Path firstKey(List<String> files) {
    for (String f : files) {
      try {
        Path p = Path.of(f);
        if (usableKey(p)) {
          return p;
        }
      } catch (RuntimeException e) {
        // Not a path of this computer.
      }
    }
    return null;
  }

  /** A regular file no bigger than a key may be ({@link PrivateKeys#readFile}), not a public key. Not read. */
  private static boolean usableKey(Path p) {
    try {
      String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
      return !name.endsWith(".pub") && Files.isRegularFile(p) && Files.size(p) <= MAX_KEY_BYTES;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** Whether an SSH server answers on {@code 127.0.0.1:port}: its identification line, nothing sent. */
  static boolean sshOnLoopback(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), BANNER_CONNECT_MS);
      socket.setSoTimeout(BANNER_READ_MS);
      InputStream in = socket.getInputStream();
      byte[] banner = in.readNBytes(4);
      return "SSH-".equals(new String(banner, StandardCharsets.US_ASCII));
    } catch (IOException e) {
      return false;
    }
  }

  /** "a", "a and b", "a, b and c". */
  private static String and(List<String> parts) {
    return parts.size() <= 2 ? String.join(" and ", parts)
        : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
  }

  private static boolean isLoopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
  }

  static boolean validUser(String user) {
    return user != null && USER.matcher(user).matches();
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
