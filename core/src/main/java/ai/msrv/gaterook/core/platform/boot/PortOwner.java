package ai.msrv.gaterook.core.platform.boot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Who listens on a TCP port, for the "Port 7465 is already in use" screen (pid and user). Linux reads {@code /proc};
 * other systems report nothing and the screen shows the {@code lsof}/{@code netstat} command instead (UNVERIFIED on
 * macOS and Windows). Best effort and bounded: processes of other users can't be inspected without privileges.
 */
final class PortOwner {

  /** The process holding the port. */
  record Owner(long pid, String user) {}

  private static final Pattern PID = Pattern.compile("[0-9]+");
  private static final String LISTEN = "0A";
  private static final int MAX_PROCESSES = 4096;

  private PortOwner() {}

  static Optional<Owner> of(int port) {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
      return Optional.empty();
    }
    try {
      Set<String> inodes = listeningInodes(port);
      return inodes.isEmpty() ? Optional.empty() : ownerOf(inodes);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Socket inodes in LISTEN state on {@code port}, any local address, IPv4 and IPv6. */
  private static Set<String> listeningInodes(int port) throws IOException {
    String hexPort = String.format(Locale.ROOT, ":%04X", port);
    Set<String> inodes = new java.util.HashSet<>();
    for (String table : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
      Path path = Path.of(table);
      if (!Files.isReadable(path)) {
        continue;
      }
      inodes.addAll(Files.readAllLines(path).stream().skip(1).map(String::trim).map(l -> l.split("\\s+"))
          .filter(f -> f.length > 9 && f[1].endsWith(hexPort) && LISTEN.equals(f[3])).map(f -> f[9])
          .collect(Collectors.toSet()));
    }
    return inodes;
  }

  private static Optional<Owner> ownerOf(Set<String> inodes) throws IOException {
    int seen = 0;
    try (DirectoryStream<Path> processes = Files.newDirectoryStream(Path.of("/proc"),
        p -> PID.matcher(p.getFileName().toString()).matches())) {
      for (Path process : processes) {
        if (++seen > MAX_PROCESSES) {
          break;
        }
        Path fds = process.resolve("fd");
        if (!Files.isReadable(fds)) {
          continue;
        }
        try (DirectoryStream<Path> links = Files.newDirectoryStream(fds)) {
          for (Path link : links) {
            String target = Files.readSymbolicLink(link).toString();
            if (target.startsWith("socket:[") && inodes.contains(target.substring(8, target.length() - 1))) {
              return Optional.of(new Owner(Long.parseLong(process.getFileName().toString()),
                  Files.getOwner(process).getName()));
            }
          }
        } catch (IOException | SecurityException e) {
          // Another user's process, or it exited meanwhile: keep looking.
        }
      }
    }
    return Optional.empty();
  }
}
