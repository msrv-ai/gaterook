package ai.msrv.gaterook.connectors.mcpserver;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a person's command says about the code it runs (report 11 §6.10): {@code npx some-server} downloads whatever
 * version is newest on every start, so the review says "Not pinned" unless the package names an exact version.
 */
final class Commands {

  /** Runners that fetch a package by name: npx, pnpm dlx, bunx, uvx, pipx run. */
  private static final Set<String> RUNNERS = Set.of("npx", "pnpx", "bunx", "uvx");
  private static final Pattern NPM_EXACT = Pattern.compile("^(@[^/@]+/)?[^@/]+@\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.+-]+)?$");
  private static final Pattern PY_EXACT = Pattern.compile("^[A-Za-z0-9._-]+(\\[[^]]*])?==\\d+(\\.\\d+)*([a-z0-9.+-]*)$");

  private Commands() {}

  /** The executable's base name in lower case, without {@code .cmd}/{@code .exe}. */
  static String base(String command) {
    String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
    return name.replaceAll("\\.(cmd|exe|bat)$", "");
  }

  /** Whether {@code argv} fetches a package without an exact version. */
  static boolean notPinned(List<String> argv) {
    if (argv.isEmpty()) {
      return false;
    }
    String runner = base(argv.getFirst());
    int from;
    boolean python;
    if (RUNNERS.contains(runner)) {
      from = 1;
      python = runner.equals("uvx");
    } else if ((runner.equals("pnpm") || runner.equals("yarn")) && argv.size() > 1 && argv.get(1).equals("dlx")) {
      from = 2;
      python = false;
    } else if (runner.equals("npm") && argv.size() > 1 && argv.get(1).equals("exec")) {
      from = 2;
      python = false;
    } else if (runner.equals("pipx") && argv.size() > 1 && argv.get(1).equals("run")) {
      from = 2;
      python = true;
    } else {
      return false;
    }
    String spec = null;
    for (int i = from; i < argv.size(); i++) {
      String a = argv.get(i);
      if (a.equals("--")) {
        spec = i + 1 < argv.size() ? argv.get(i + 1) : null;
        break;
      }
      if (a.equals("-p") || a.equals("--package") || a.equals("--from")) {
        spec = i + 1 < argv.size() ? argv.get(i + 1) : null;
        break;
      }
      if (a.startsWith("--package=") || a.startsWith("--from=")) {
        spec = a.substring(a.indexOf('=') + 1);
        break;
      }
      if (!a.startsWith("-")) {
        spec = a;
        break;
      }
    }
    if (spec == null) {
      return true;
    }
    return !(python ? PY_EXACT : NPM_EXACT).matcher(spec).matches();
  }
}
