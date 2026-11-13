package com.gaterook.connectors.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The OpenSSH client config of this computer ({@code ~/.ssh/config} and the files it includes), read only for the
 * setup form's suggestions: the connector never reads it when it connects ({@link SshClients}). As OpenSSH reads it:
 * a {@code Host} or {@code Match} line starts a block that applies when it matches the name, the first value of a
 * setting wins, {@code IdentityFile}s add up, and {@code Include} reads files (globs allowed, relative to
 * {@code ~/.ssh}) as part of the block it stands in. A {@code Match} that can't be evaluated without running something
 * or knowing more than the name ({@code exec}, {@code canonical}, {@code final}, {@code localnetwork}, {@code tagged})
 * applies to nothing. Bounded: {@value #MAX_DEPTH} levels of includes, {@value #MAX_FILES} files of at most 1 MiB,
 * {@value #MAX_LINES} lines in all.
 */
final class OpenSshConfig {

  static final int MAX_DEPTH = 16;
  static final int MAX_FILES = 64;
  static final long MAX_BYTES = 1 << 20;
  static final int MAX_LINES = 20_000;
  private static final int MAX_ALIASES = 200;
  private static final int MAX_IDENTITIES = 32;

  /** One line that matters, in file order; an {@code Include} holds the lines of one included file. */
  private sealed interface Item permits Block, Include, Setting {}

  /** {@code Host patterns…} or {@code Match criteria…}: the lines after it apply when it matches. */
  private record Block(boolean match, List<String> words) implements Item {}

  private record Include(List<Item> items) implements Item {}

  /** {@code key} in lower case, its arguments unquoted. */
  private record Setting(String key, List<String> args) implements Item {}

  /**
   * What {@code ssh name} would use, as far as the form needs it; tokens already expanded. A value that uses a token
   * the form can't expand is {@code null}, as if unknown.
   *
   * @param identityFiles the {@code IdentityFile}s in the order OpenSSH tries them, {@code ~} and tokens expanded
   * @param proxyJump     {@code ProxyJump} as written, or {@code null} (also for {@code none})
   * @param proxyCommand  {@code ProxyCommand} as written, or {@code null} (also for {@code none})
   */
  record Resolved(String name, String hostName, String user, Integer port, List<String> identityFiles,
      String proxyJump, String proxyCommand) {

    /** The address OpenSSH connects to: the {@code HostName}, else the name itself. */
    String host() {
      return hostName != null ? hostName : name;
    }

    /** Whether any block set something for this name. */
    boolean any() {
      return hostName != null || user != null || port != null || !identityFiles.isEmpty() || proxyJump != null
          || proxyCommand != null;
    }
  }

  private final SshHome home;
  private final List<Item> items;

  private OpenSshConfig(SshHome home, List<Item> items) {
    this.home = home;
    this.items = items;
  }

  /** {@code ~/.ssh/config} of {@code home}; a missing or unreadable file is an empty config. */
  static OpenSshConfig read(SshHome home) {
    Reader reader = new Reader(home);
    return new OpenSshConfig(home, reader.file(home.ssh().resolve("config"), 0));
  }

  boolean isEmpty() {
    return items.isEmpty();
  }

  /** The names of {@code Host} lines that name one host (no wildcard, no negation), in file order, each once. */
  List<String> aliases() {
    Set<String> seen = new LinkedHashSet<>();
    List<String> out = new ArrayList<>();
    aliases(items, seen, out);
    return out;
  }

  private static void aliases(List<Item> list, Set<String> seen, List<String> out) {
    for (Item item : list) {
      if (out.size() >= MAX_ALIASES) {
        return;
      }
      if (item instanceof Block b && !b.match()) {
        for (String word : b.words()) {
          if (validHost(word) && word.chars().noneMatch(c -> c == '*' || c == '?' || c == '!' || c == ',')
              && seen.add(word.toLowerCase(Locale.ROOT)) && out.size() < MAX_ALIASES) {
            out.add(word);
          }
        }
      } else if (item instanceof Include inc) {
        aliases(inc.items(), seen, out);
      }
    }
  }

  /** What {@code ssh name} would use. */
  Resolved resolve(String name) {
    State s = new State(name.strip().toLowerCase(Locale.ROOT));
    eval(items, s, true);
    return s.resolved();
  }

  private void eval(List<Item> list, State s, boolean initiallyActive) {
    boolean active = initiallyActive;
    for (Item item : list) {
      switch (item) {
        case Block b -> active = b.match() ? s.match(b.words()) : hostMatches(s.original, b.words());
        // An included file starts in the block the Include stands in; its own blocks end with it (OpenSSH restores
        // the outer state after each file), and a file included where nothing applies applies to nothing.
        case Include inc -> {
          if (active) {
            eval(inc.items(), s, true);
          }
        }
        case Setting set -> {
          if (active) {
            s.apply(set);
          }
        }
      }
    }
  }

  /** {@code Host p1 p2…}: a pattern matches and no negated one does. */
  private static boolean hostMatches(String host, List<String> patterns) {
    boolean matched = false;
    for (String p : patterns) {
      boolean negated = p.startsWith("!");
      if (glob(host, (negated ? p.substring(1) : p).toLowerCase(Locale.ROOT))) {
        if (negated) {
          return false;
        }
        matched = true;
      }
    }
    return matched;
  }

  /** A comma-separated pattern list ({@code Match host a,b,!c}): a negated match refuses, another match accepts. */
  private static boolean listMatches(String subject, String list, boolean lower) {
    if (subject == null) {
      return false;
    }
    String s = lower ? subject.toLowerCase(Locale.ROOT) : subject;
    boolean matched = false;
    for (String raw : list.split(",")) {
      String p = lower ? raw.strip().toLowerCase(Locale.ROOT) : raw.strip();
      boolean negated = p.startsWith("!");
      if (glob(s, negated ? p.substring(1) : p)) {
        if (negated) {
          return false;
        }
        matched = true;
      }
    }
    return matched;
  }

  /** OpenSSH's patterns: {@code *} any run of characters, {@code ?} one character. */
  static boolean glob(String s, String p) {
    int si = 0;
    int pi = 0;
    int star = -1;
    int mark = 0;
    while (si < s.length()) {
      if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
        si++;
        pi++;
      } else if (pi < p.length() && p.charAt(pi) == '*') {
        star = pi++;
        mark = si;
      } else if (star >= 0) {
        pi = star + 1;
        si = ++mark;
      } else {
        return false;
      }
    }
    while (pi < p.length() && p.charAt(pi) == '*') {
      pi++;
    }
    return pi == p.length();
  }

  /** A host name or address as the connection's Host field takes it. */
  static boolean validHost(String host) {
    // A pattern (HostName *.compute.amazonaws.com) or a token ssh expands (%h) names no address to connect to.
    return host != null && !host.isEmpty() && host.length() <= 253
        && host.chars().noneMatch(c -> c <= ' ' || c == '/' || c == '@' || c == 0x7f || c == '*' || c == '?'
            || c == '%' || c == '!');
  }

  /** The settings of one name as the blocks that match it give them, the first value of each. */
  private final class State {
    final String original;
    String hostName;
    String user;
    String port;
    final List<String> identityFiles = new ArrayList<>();
    String proxy;
    boolean proxyIsJump;

    State(String original) {
      this.original = original;
    }

    void apply(Setting set) {
      if (set.args().isEmpty()) {
        return;
      }
      String first = set.args().getFirst();
      switch (set.key()) {
        case "hostname" -> hostName = hostName == null ? first : hostName;
        case "user" -> user = user == null ? first : user;
        case "port" -> port = port == null ? first : port;
        case "identityfile" -> {
          if (identityFiles.size() < MAX_IDENTITIES) {
            identityFiles.add(first);
          }
        }
        // ProxyJump and ProxyCommand compete: whichever comes first prevents the other.
        case "proxyjump" -> {
          if (proxy == null) {
            proxy = first;
            proxyIsJump = true;
          }
        }
        case "proxycommand" -> {
          if (proxy == null) {
            proxy = String.join(" ", set.args());
            proxyIsJump = false;
          }
        }
        default -> {
          // Not one the form has a field for.
        }
      }
    }

    /** {@code Match all | host | originalhost | user | localuser}, each may be negated; anything else matches nothing. */
    boolean match(List<String> words) {
      if (words.isEmpty()) {
        return false;
      }
      boolean result = true;
      for (int i = 0; i < words.size(); i++) {
        String word = words.get(i).toLowerCase(Locale.ROOT);
        boolean negated = word.startsWith("!");
        String criterion = negated ? word.substring(1) : word;
        boolean ok;
        switch (criterion) {
          case "all" -> ok = true;
          case "host", "originalhost", "user", "localuser" -> {
            if (i + 1 >= words.size()) {
              return false;
            }
            String list = words.get(++i);
            String target = expandHost(hostName, original);
            ok = switch (criterion) {
              case "host" -> listMatches(target == null ? original : target, list, true);
              case "originalhost" -> listMatches(original, list, true);
              case "user" -> listMatches(user != null ? user : home.localUser(), list, false);
              default -> listMatches(home.localUser(), list, false);
            };
          }
          default -> {
            return false; // exec, canonical, final, localnetwork, tagged…: nothing runs for a suggestion
          }
        }
        result &= negated != ok;
      }
      return result;
    }

    Resolved resolved() {
      String host = expandHost(hostName, original);
      String login = user == null ? null : expandUser(user);
      Integer number = null;
      if (port != null) {
        try {
          int p = Integer.parseInt(port.strip());
          number = p >= 1 && p <= 65535 ? p : null;
        } catch (NumberFormatException e) {
          // Not a port: as if unset.
        }
      }
      List<String> identities = new ArrayList<>();
      for (String file : identityFiles) {
        String expanded = expandFile(file, host == null ? original : host, login, number);
        if (expanded != null && !identities.contains(expanded)) {
          identities.add(expanded);
        }
      }
      String jump = proxy != null && proxyIsJump && !"none".equalsIgnoreCase(proxy) ? proxy : null;
      String command = proxy != null && !proxyIsJump && !"none".equalsIgnoreCase(proxy) ? proxy : null;
      return new Resolved(original, host, login, number, List.copyOf(identities), jump, command);
    }

    /** {@code HostName} with {@code %h} and {@code %%}, the only tokens it takes; {@code null} when it isn't one. */
    private String expandHost(String value, String name) {
      if (value == null) {
        return null;
      }
      String out = tokens(value, name, null, null);
      return out == null || !validHost(out) ? null : out.toLowerCase(Locale.ROOT);
    }

    private String expandUser(String value) {
      String out = tokens(value, original, null, null);
      return out == null || out.isBlank() ? null : out;
    }

    /** {@code ~}, {@code ${HOME}} and the tokens a path of this computer can take here; {@code null} for others. */
    private String expandFile(String value, String host, String login, Integer number) {
      String v = value.replace("${HOME}", home.home().toString());
      if (v.contains("${")) {
        return null;
      }
      String out = tokens(v, host, login, number);
      // Relative paths are relative to wherever ssh runs: not a file the form can name.
      Path path = out == null ? null : home.expand(out);
      return path == null ? null : path.toString();
    }

    /**
     * The tokens of {@code ssh_config(5)} the form can expand: {@code %%}, {@code %h} the host, {@code %n} the name,
     * {@code %d} the home folder, {@code %u} the local user, {@code %r} the remote user, {@code %p} the port.
     */
    private String tokens(String value, String host, String login, Integer number) {
      if (value.indexOf('%') < 0) {
        return value;
      }
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c != '%') {
          out.append(c);
          continue;
        }
        if (i + 1 >= value.length()) {
          return null;
        }
        char t = value.charAt(++i);
        String part = switch (t) {
          case '%' -> "%";
          case 'h' -> host;
          case 'n' -> original;
          case 'd' -> home.home().toString();
          case 'u' -> home.localUser();
          case 'r' -> login != null ? login : home.localUser();
          case 'p' -> String.valueOf(number == null ? 22 : number);
          default -> null;
        };
        if (part == null) {
          return null;
        }
        out.append(part);
      }
      return out.toString();
    }
  }

  /** Reads the files, following {@code Include} within the bounds. */
  private static final class Reader {
    private final SshHome home;
    private int files;
    private int lines;

    Reader(SshHome home) {
      this.home = home;
    }

    List<Item> file(Path path, int depth) {
      if (depth > MAX_DEPTH || files >= MAX_FILES || lines >= MAX_LINES) {
        return List.of();
      }
      String text;
      try {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
          return List.of();
        }
        files++;
        try (InputStream in = Files.newInputStream(path)) {
          text = new String(in.readNBytes((int) MAX_BYTES), StandardCharsets.UTF_8);
        }
      } catch (IOException | RuntimeException e) {
        return List.of();
      }
      List<Item> out = new ArrayList<>();
      for (String raw : text.split("\\R")) {
        if (++lines > MAX_LINES) {
          break;
        }
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end)) && line.charAt(end) != '=') {
          end++;
        }
        String key = line.substring(0, end).toLowerCase(Locale.ROOT);
        int at = end;
        while (at < line.length() && Character.isWhitespace(line.charAt(at))) {
          at++;
        }
        if (at < line.length() && line.charAt(at) == '=') {
          at++;
        }
        List<String> args = words(line.substring(at));
        if (key.isEmpty() || args == null) {
          continue; // a line OpenSSH would refuse: nothing taken from it
        }
        switch (key) {
          case "host" -> out.add(new Block(false, args));
          case "match" -> out.add(new Block(true, args));
          case "include" -> {
            for (String pattern : args) {
              for (Path included : include(pattern)) {
                out.add(new Include(file(included, depth + 1)));
              }
            }
          }
          default -> out.add(new Setting(key, args));
        }
      }
      return List.copyOf(out);
    }

    /** The files an {@code Include} names: {@code ~/…}, absolute, or relative to {@code ~/.ssh}; globs sorted. */
    private List<Path> include(String pattern) {
      // Only the parts before the first glob become a Path: Windows refuses a Path with '*' or '?' in it.
      String[] parts = pattern.split("[/\\\\]", -1);
      int glob = 0;
      while (glob < parts.length && parts[glob].chars().noneMatch(c -> c == '*' || c == '?' || c == '[')) {
        glob++;
      }
      String prefix = String.join("/", java.util.Arrays.asList(parts).subList(0, glob));
      Path base;
      if (prefix.isEmpty()) {
        base = glob == 0 ? home.ssh() : null;
      } else {
        base = home.expand(prefix);
        if (base == null && !prefix.startsWith("~")) {
          base = safeResolve(home.ssh(), prefix);
        }
      }
      if (base == null || base.getRoot() == null) {
        return List.of();
      }
      List<String> rest = new ArrayList<>();
      for (int i = glob; i < parts.length; i++) {
        if (!parts[i].isEmpty() && !parts[i].equals(".")) {
          rest.add(parts[i]);
        }
      }
      List<Path> out = new ArrayList<>();
      expand(base.normalize(), rest, 0, out);
      return out;
    }

    private static Path safeResolve(Path dir, String relative) {
      try {
        return dir.resolve(relative).normalize();
      } catch (RuntimeException e) {
        return null;
      }
    }

    /** Matches {@code parts} from {@code index} on below {@code at}: a part with a glob lists its folder. */
    private void expand(Path at, List<String> parts, int index, List<Path> out) {
      if (out.size() >= MAX_FILES) {
        return;
      }
      if (index == parts.size()) {
        if (Files.isRegularFile(at)) {
          out.add(at);
        }
        return;
      }
      String part = parts.get(index);
      if (part.chars().noneMatch(c -> c == '*' || c == '?' || c == '[')) {
        expand(at.resolve(part).normalize(), parts, index + 1, out);
        return;
      }
      if (!Files.isDirectory(at)) {
        return;
      }
      List<Path> matches = new ArrayList<>();
      try (DirectoryStream<Path> dir = Files.newDirectoryStream(at, part)) {
        for (Path p : dir) {
          String name = p.getFileName().toString();
          if (!name.startsWith(".") || part.startsWith(".")) {
            matches.add(p);
          }
          if (matches.size() > MAX_FILES) {
            break;
          }
        }
      } catch (IOException | RuntimeException e) {
        return;
      }
      matches.sort(null);
      for (Path p : matches) {
        expand(p, parts, index + 1, out);
      }
    }
  }

  /**
   * The arguments of a line as OpenSSH splits them: whitespace between words, double or single quotes around a word
   * with spaces, a backslash before a quote, and a word starting with {@code #} ends the line. {@code null} for an
   * unterminated quote.
   */
  static List<String> words(String s) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      while (i < n && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
      if (i >= n || s.charAt(i) == '#') {
        break;
      }
      StringBuilder w = new StringBuilder();
      char quote = 0;
      while (i < n) {
        char c = s.charAt(i);
        if (quote != 0) {
          if (c == quote) {
            quote = 0;
          } else if (c == '\\' && quote == '"' && i + 1 < n && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\')) {
            w.append(s.charAt(++i));
          } else {
            w.append(c);
          }
          i++;
          continue;
        }
        if (Character.isWhitespace(c)) {
          break;
        }
        if (c == '"' || c == '\'') {
          quote = c;
        } else if (c == '\\' && i + 1 < n) {
          w.append(s.charAt(++i));
        } else {
          w.append(c);
        }
        i++;
      }
      if (quote != 0) {
        return null;
      }
      out.add(w.toString());
    }
    return out;
  }
}
