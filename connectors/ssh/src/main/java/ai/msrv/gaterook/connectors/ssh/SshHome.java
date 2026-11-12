package ai.msrv.gaterook.connectors.ssh;

import java.nio.file.Path;

/**
 * The home folder and user of this computer, as the setup form's local suggestions read them ({@code ~/.ssh/config},
 * {@code ~/.ssh/known_hosts}, the keys in {@code ~/.ssh}). Tests pass a folder of their own; the connector itself never
 * reads {@code ~/.ssh} when it connects ({@link SshClients}).
 *
 * @param home      the home folder
 * @param localUser the user name of this computer, as OpenSSH uses it when nothing names another
 */
record SshHome(Path home, String localUser) {

  static SshHome current() {
    return new SshHome(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
        System.getProperty("user.name"));
  }

  Path ssh() {
    return home.resolve(".ssh");
  }

  /** {@code ~/…} for {@code ~}, {@code ~/…} and {@code ~\…}; an absolute path as it is; {@code null} otherwise. */
  Path expand(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String p = path.strip();
    try {
      if (p.equals("~")) {
        return home;
      }
      if (p.startsWith("~/") || p.startsWith("~\\")) {
        return home.resolve(p.substring(2)).normalize();
      }
      Path out = Path.of(p);
      return out.isAbsolute() ? out.normalize() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** {@code ~/.ssh/id_ed25519} rather than the home folder's full path, as people type it. */
  String shown(Path path) {
    Path p = path.toAbsolutePath().normalize();
    return p.startsWith(home) && !p.equals(home) ? "~/" + home.relativize(p).toString().replace('\\', '/')
        : p.toString();
  }
}
