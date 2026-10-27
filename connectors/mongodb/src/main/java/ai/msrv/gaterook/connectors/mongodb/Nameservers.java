package ai.msrv.gaterook.connectors.mongodb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which DNS servers answer the SRV and TXT questions of {@code mongodb+srv://}: on Linux and macOS the ones of
 * {@code /etc/resolv.conf} (read on every lookup: a VPN rewrites it), at most three as the C resolver uses, its
 * {@code timeout:} and {@code attempts:} capped so that a dead server can't hold a test for long; without a
 * {@code nameserver} line the local one, as resolv.conf(5) says. On Windows there is no such file: the JDK's own DNS
 * provider (JNDI) asks the system ({@link #jndi()}). Tests name one server ({@link #fixed}).
 *
 * <p>Only SRV and TXT go through here; host names go through the system resolver as for any other connection, so
 * {@code /etc/hosts} and split DNS keep working for them. macOS scoped resolvers (per-domain VPN DNS that isn't in
 * {@code /etc/resolv.conf}) are not consulted.
 */
record Nameservers(List<InetSocketAddress> servers, Duration timeout, int attempts, boolean jndi, String source) {

  static final Path RESOLV_CONF = Path.of("/etc/resolv.conf");
  static final int MAX_SERVERS = 3;
  static final Duration MAX_TIMEOUT = Duration.ofSeconds(2);

  private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+(%[0-9A-Za-z_.-]+)?$");

  /** The system's servers now. */
  static Nameservers system() {
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      return new Nameservers(List.of(), MAX_TIMEOUT, 2, true, "Windows DNS settings");
    }
    String text;
    try {
      text = Files.readString(RESOLV_CONF, StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      text = "";
    }
    return parse(text, RESOLV_CONF.toString());
  }

  /** One server, for tests and the core's test override. */
  static Nameservers fixed(InetSocketAddress server) {
    return new Nameservers(List.of(server), MAX_TIMEOUT, 2, false, server.getHostString() + ":" + server.getPort());
  }

  /** The servers of a resolv.conf text. */
  static Nameservers parse(String text, String source) {
    List<InetSocketAddress> servers = new ArrayList<>();
    Duration timeout = MAX_TIMEOUT;
    int attempts = 2;
    for (String raw : text.split("\n")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      String[] words = line.split("\\s+");
      if (words[0].equals("nameserver") && words.length >= 2 && servers.size() < MAX_SERVERS) {
        InetAddress address = literal(words[1]);
        if (address != null) {
          servers.add(new InetSocketAddress(address, 53));
        }
      } else if (words[0].equals("options")) {
        for (int i = 1; i < words.length; i++) {
          if (words[i].startsWith("timeout:")) {
            timeout = Duration.ofSeconds(Math.clamp(number(words[i].substring(8), 2), 1, MAX_TIMEOUT.toSeconds()));
          } else if (words[i].startsWith("attempts:")) {
            attempts = Math.clamp(number(words[i].substring(9), 2), 1, 2);
          }
        }
      }
    }
    if (servers.isEmpty()) {
      servers.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), 53));
    }
    return new Nameservers(List.copyOf(servers), timeout, attempts, false, source);
  }

  /** An IP literal, never a name: a name here would need DNS to find DNS. */
  static InetAddress literal(String text) {
    var v4 = IPV4.matcher(text);
    if (v4.matches()) {
      for (int g = 1; g <= 4; g++) {
        if (Integer.parseInt(v4.group(g)) > 255) {
          return null;
        }
      }
    } else if (!text.contains(":") || !IPV6.matcher(text).matches()) {
      return null;
    }
    try {
      // A literal: getByName parses it without a lookup.
      return InetAddress.getByName(text);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static int number(String text, int dflt) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      return dflt;
    }
  }
}
