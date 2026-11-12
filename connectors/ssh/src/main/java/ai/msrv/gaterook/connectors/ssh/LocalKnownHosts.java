package ai.msrv.gaterook.connectors.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.config.hosts.KnownHostHashValue;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

/**
 * {@code ~/.ssh/known_hosts} of this computer, for the setup form only: the hosts OpenSSH connected to (written in
 * plain text: a hashed line names no host), and the keys it trusts for one host, so a note can name the fingerprint
 * to compare when Test connection asks to trust a key. Nothing here trusts a key for a connection: that stays the
 * person's step in Test connection. Bounded: 2 MiB, {@value #MAX_LINES} lines.
 */
final class LocalKnownHosts {

  static final long MAX_BYTES = 2L << 20;
  static final int MAX_LINES = 20_000;
  private static final int MAX_KEYS = 8;

  /** A host written in plain text, with its port (22 unless written {@code [host]:port}). */
  record Known(String host, int port) {}

  private final List<String> lines;

  private LocalKnownHosts(List<String> lines) {
    this.lines = lines;
  }

  static LocalKnownHosts read(SshHome home) {
    Path path = home.ssh().resolve("known_hosts");
    List<String> lines = new ArrayList<>();
    try {
      if (Files.isRegularFile(path)) {
        try (InputStream in = Files.newInputStream(path)) {
          String text = new String(in.readNBytes((int) MAX_BYTES), StandardCharsets.UTF_8);
          for (String line : text.split("\\R")) {
            String l = line.strip();
            if (!l.isEmpty() && !l.startsWith("#")) {
              lines.add(l);
              if (lines.size() >= MAX_LINES) {
                break;
              }
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // No known_hosts to offer from.
    }
    return new LocalKnownHosts(List.copyOf(lines));
  }

  /**
   * The hosts written in plain text, the most recently added first, each once: the first name of a line, as the one
   * ssh was given ({@code CheckHostIP} adds the address after it).
   */
  List<Known> hosts() {
    Set<String> seen = new LinkedHashSet<>();
    List<Known> out = new ArrayList<>();
    for (int i = lines.size() - 1; i >= 0; i--) {
      String field = hostField(lines.get(i));
      if (field == null || field.startsWith("|")) {
        continue;
      }
      int comma = field.indexOf(',');
      Known k = known(comma < 0 ? field : field.substring(0, comma));
      if (k != null && seen.add(k.host() + ":" + k.port())) {
        out.add(k);
      }
    }
    return out;
  }

  /** The keys trusted for {@code host:port}, hashed lines included; {@code @revoked} and CA lines aside. */
  List<PublicKey> keys(String host, int port) {
    String h = host.toLowerCase(Locale.ROOT);
    List<PublicKey> out = new ArrayList<>();
    for (String line : lines) {
      if (out.size() >= MAX_KEYS) {
        break;
      }
      String field = hostField(line);
      if (field == null || !matches(field, h, port)) {
        continue;
      }
      try {
        KnownHostEntry entry = KnownHostEntry.parseKnownHostEntry(line);
        if (entry != null && entry.getKeyEntry() != null) {
          out.add(entry.getKeyEntry().resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.FAILING));
        }
      } catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
        // A line MINA can't read: not offered.
      }
    }
    return out;
  }

  /** The host field of a line without a marker ({@code @cert-authority}, {@code @revoked}), or {@code null}. */
  private static String hostField(String line) {
    if (line.startsWith("@")) {
      return null;
    }
    int space = 0;
    while (space < line.length() && !Character.isWhitespace(line.charAt(space))) {
      space++;
    }
    return space == 0 || space == line.length() ? null : line.substring(0, space);
  }

  private static boolean matches(String field, String host, int port) {
    if (field.startsWith("|")) {
      try {
        KnownHostHashValue hash = KnownHostHashValue.parse(field);
        return hash != null && hash.isHostMatch(host, port);
      } catch (RuntimeException e) {
        return false;
      }
    }
    boolean matched = false;
    for (String part : field.split(",")) {
      boolean negated = part.startsWith("!");
      String pattern = negated ? part.substring(1) : part;
      String name = pattern;
      int p = 22;
      if (pattern.startsWith("[")) {
        int at = pattern.lastIndexOf("]:");
        if (at <= 1) {
          continue;
        }
        name = pattern.substring(1, at);
        p = parsePort(pattern.substring(at + 2));
      }
      if (p == port && OpenSshConfig.glob(host, name.toLowerCase(Locale.ROOT))) {
        if (negated) {
          return false;
        }
        matched = true;
      }
    }
    return matched;
  }

  /** {@code host} or {@code [host]:port} without patterns, or {@code null}. */
  private static Known known(String part) {
    if (part.isEmpty() || part.chars().anyMatch(c -> c == '*' || c == '?' || c == '!')) {
      return null;
    }
    String host = part;
    int port = 22;
    if (part.startsWith("[")) {
      int at = part.lastIndexOf("]:");
      if (at <= 1) {
        return null;
      }
      host = part.substring(1, at);
      port = parsePort(part.substring(at + 2));
      if (port < 0) {
        return null;
      }
    }
    return OpenSshConfig.validHost(host) ? new Known(host.toLowerCase(Locale.ROOT), port) : null;
  }

  private static int parsePort(String text) {
    try {
      int p = Integer.parseInt(text);
      return p >= 1 && p <= 65535 ? p : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
