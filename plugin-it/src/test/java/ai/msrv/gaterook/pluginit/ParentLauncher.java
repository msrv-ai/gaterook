package ai.msrv.gaterook.pluginit;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for the core in the "{@code kill -9} of the parent" test (report 16 §3.4): it starts a plugin host as its
 * child with a piped stdin, so it alone holds the writing end, writes the host's pid to a marker file, and then waits.
 * When the test kills this process with {@code SIGKILL}, the kernel closes the pipe, the host sees EOF on its stdin and
 * exits — the test checks the host's pid is gone, with no orphan left behind.
 */
public final class ParentLauncher {

  private ParentLauncher() {}

  public static void main(String[] args) throws Exception {
    Path hostJar = Path.of(args[0]);
    Path marker = Path.of(args[1]);
    Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java");
    Process host = new ProcessBuilder(javaExe.toString(), "-cp", hostJar.toString(),
        "ai.msrv.gaterook.pluginhost.Main", "--protocol", "1")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    Files.writeString(marker, Long.toString(host.pid()));
    // Hold the host's stdin open until this process is killed.
    Thread.sleep(java.time.Duration.ofMinutes(5).toMillis());
    host.destroyForcibly();
  }
}
