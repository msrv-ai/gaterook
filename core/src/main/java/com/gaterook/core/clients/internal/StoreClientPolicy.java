package com.gaterook.core.clients.internal;

import com.gaterook.core.clients.internal.persistence.ClientKind;
import com.gaterook.core.clients.internal.persistence.LinkMethod;
import com.gaterook.core.platform.ProblemException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Store integrations write shared user files without disabling Windows AppData virtualization. */
public final class StoreClientPolicy {
  private final ClientEnvironment env;

  public StoreClientPolicy(ClientEnvironment env) {
    this.env = env;
  }

  public void method(ClientKind client, LinkMethod method) {
    if (env.windowsStore() && client == ClientKind.CLAUDE_DESKTOP && method != LinkMethod.MCPB) {
      throw denied("Install the Claude Desktop extension instead. This Store edition does not edit Claude's "
          + "AppData configuration.");
    }
  }

  /** Runtime and backup files must be shared with clients outside this package too. */
  public void runtime() {
    if (!env.windowsStore()) return;
    path(env.gatewayHome());
    path(env.backups());
    path(env.brokerInfo());
    path(env.shim());
    path(env.bridgeExecutable());
    path(env.mcpbDirectory());
  }

  public void snapshot(ConfigFileWriter.Snapshot snapshot) {
    if (snapshot != null) {
      path(snapshot.path());
      path(snapshot.realPath());
    }
  }

  /** Checks both the requested name and its existing filesystem ancestry, including junctions. */
  public void path(Path candidate) {
    if (!env.windowsStore()) return;
    try {
      Path absolute = candidate.toAbsolutePath().normalize();
      Path actual = resolve(absolute);
      for (Path root : roots()) {
        Path lexicalRoot = root.toAbsolutePath().normalize();
        Path actualRoot = resolve(lexicalRoot);
        if (inside(absolute, lexicalRoot) || inside(actual, lexicalRoot)
            || inside(absolute, actualRoot) || inside(actual, actualRoot)) {
          throw denied("Windows keeps new AppData files private to each package. Choose a client configuration "
              + "outside AppData, or use the client's installation flow. Blocked path: " + env.display(candidate));
        }
      }
    } catch (IOException | SecurityException ex) {
      throw denied("The client path could not be verified safely. Check its location and permissions: "
          + env.display(candidate));
    }
  }

  private List<Path> roots() {
    List<Path> roots = new ArrayList<>();
    roots.add(env.userHome().resolve("AppData"));
    roots.add(env.appData());
    roots.add(env.localAppData());
    // USERPROFILE can differ from Java's user.home, especially with redirected profiles.
    env.variable("USERPROFILE").map(Path::of).map(p -> p.resolve("AppData")).ifPresent(roots::add);
    return roots;
  }

  /** Missing target components are appended to the nearest existing real ancestor. */
  private static Path resolve(Path path) throws IOException {
    Path existing = path;
    List<Path> missing = new ArrayList<>();
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unreadable path");
      missing.add(existing.getFileName());
      existing = existing.getParent();
      if (existing == null) throw new IOException("No existing ancestor");
    }
    Path result = existing.toRealPath();
    for (int i = missing.size() - 1; i >= 0; i--) result = result.resolve(missing.get(i));
    return result.normalize();
  }

  private static boolean inside(Path path, Path root) {
    String value = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    String prefix = root.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    return value.equals(prefix) || value.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
  }

  private static ProblemException denied(String detail) {
    return ProblemException.unprocessable("Client location is not shared", detail, "/method");
  }
}
