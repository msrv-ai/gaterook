package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Check step 3 (report 03 §5): GateRook runs <em>exactly the command written in the client's config</em>, the way
 * the client runs it, and asks the endpoint for its tools through it. Codex's helper goes through {@code sh -c}
 * ({@code cmd /Q /D /C} on Windows) with an environment cleared like Codex clears it; a stdio entry is started with its
 * own argv and spoken to over stdin and stdout. "Helper OK · 120 ms · 17 tools", or what failed and its exit code.
 * The caller passes only a command GateRook wrote itself (an entry changed outside the app is never run). The token
 * the helper prints is used for the two requests to this core's own listener and forgotten; it never leaves this
 * class.
 */
public final class BridgeSelfTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration LIMIT = Duration.ofSeconds(10);

  /** {@code ok}; {@code text} for the person; {@code tools} when the endpoint answered. */
  public record Outcome(boolean ok, long ms, Integer tools, String text) {}

  private BridgeSelfTest() {}

  /** Runs Codex's {@code http_headers_helper} and lists the endpoint's tools with its header. */
  public static Outcome helper(ClientEnvironment env, String helper, String url) {
    long start = System.nanoTime();
    List<String> argv = env.os() == Os.WINDOWS
        ? List.of(env.variable("COMSPEC").orElse("cmd.exe"), "/Q", "/D", "/C", helper)
        : List.of("/bin/sh", "-c", helper);
    ProcessBuilder pb = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
    // Codex keeps only these (report 03 §1): the helper must work with them alone.
    Map<String, String> keep = pb.environment();
    Map<String, String> base = new HashMap<>(keep);
    keep.clear();
    for (String name : List.of("HOME", "LOGNAME", "PATH", "SHELL", "USER", "LANG", "LC_ALL", "TERM", "TMPDIR", "TZ",
        "USERPROFILE", "SystemRoot", "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP")) {
      if (base.containsKey(name)) {
        keep.put(name, base.get(name));
      }
    }
    if (env.sandboxed()) {
      keep.put("HOME", env.userHome().toString());
    }
    String stdout;
    int exit;
    try {
      Process p = pb.start();
      p.getOutputStream().close();
      CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
        try {
          return p.getInputStream().readNBytes(64 * 1024);
        } catch (IOException e) {
          return new byte[0];
        }
      }, r -> Thread.ofVirtual().start(r));
      if (!p.waitFor(LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        return new Outcome(false, ms(start), null, "The helper didn't answer within 10 s (Codex gives up then).");
      }
      exit = p.exitValue();
      stdout = new String(out.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    } catch (java.util.concurrent.TimeoutException e) {
      return new Outcome(false, ms(start), null, "The helper ended, but a program it started keeps its output open.");
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new Outcome(false, ms(start), null, "The helper can't be started: " + e.getMessage());
    }
    if (exit != 0) {
      return new Outcome(false, ms(start), null, "The helper failed with exit code " + exit + ": "
          + helperExit(exit));
    }
    String authorization;
    try {
      JsonNode headers = JSON.readTree(stdout);
      authorization = headers.path("Authorization").asString(null);
    } catch (RuntimeException e) {
      return new Outcome(false, ms(start), null, "The helper printed something that isn't a JSON object of headers.");
    }
    if (authorization == null) {
      return new Outcome(false, ms(start), null, "The helper printed no Authorization header.");
    }
    return listTools(url, authorization, start, "Helper");
  }

  /** What {@code gw-bridge headers} exit codes mean (ARCHITECTURE.md §7.2). */
  static String helperExit(int exit) {
    return switch (exit) {
      case 1 -> "GateRook isn't running (or its broker isn't).";
      case 2 -> "the access was revoked or doesn't exist.";
      case 3 -> "the command line of the helper is wrong.";
      default -> "see ~/.gaterook/logs/bridge.log.";
    };
  }

  /** Starts a stdio entry ({@code command} + {@code args}) and lists the endpoint's tools through it. */
  public static Outcome stdio(ClientEnvironment env, List<String> argv) {
    return stdio(env, argv, LIMIT);
  }

  /**
   * As {@link #stdio(ClientEnvironment, List)}, waiting at most {@code limit}. Only the reading thread touches the
   * bridge's stdout: closing a reader another thread is blocked in waits for that read, so on time-out the process
   * is killed first and the reader ends with it (review K1-2: Check hung on a bridge that said nothing).
   */
  static Outcome stdio(ClientEnvironment env, List<String> argv, Duration limit) {
    long start = System.nanoTime();
    ProcessBuilder pb = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
    if (env.sandboxed()) {
      pb.environment().put("HOME", env.userHome().toString());
    }
    Process p;
    try {
      p = pb.start();
    } catch (IOException e) {
      return new Outcome(false, ms(start), null, "The bridge can't be started: " + e.getMessage());
    }
    CompletableFuture<Outcome> read = CompletableFuture.supplyAsync(() -> answer(p, start),
        r -> Thread.ofVirtual().start(r));
    // Its stdin stays open while the bridge works, as a client keeps it.
    OutputStream in = p.getOutputStream();
    try {
      try {
        in.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"gaterook-check\",\"version\":\"1\"}}}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n")
            .getBytes(StandardCharsets.UTF_8));
        in.flush();
      } catch (IOException e) {
        // The bridge is gone already (it exited, or closed its stdin): what it printed tells why.
      }
      return read.get(limit.toMillis(), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      return new Outcome(false, ms(start), null, "The bridge didn't answer within " + limit.toSeconds() + " s.");
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new Outcome(false, ms(start), null, "The bridge failed: " + e.getMessage());
    } finally {
      p.descendants().forEach(ProcessHandle::destroyForcibly);
      p.destroyForcibly();
      try {
        in.close();
      } catch (IOException ignored) {
        // The process is gone: so is the other end.
      }
    }
  }

  /** Reads the bridge's lines until the answer to {@code tools/list}; runs on its own thread, closes what it reads. */
  private static Outcome answer(Process p, long start) {
    try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        JsonNode m = JSON.readTree(line);
        if (m.path("id").asInt(0) == 2) {
          if (m.has("error")) {
            return new Outcome(false, ms(start), null, "The bridge answered: " + m.path("error").path("message")
                .asString("an error"));
          }
          int n = m.path("result").path("tools").size();
          return new Outcome(true, ms(start), n, "Bridge OK · " + ms(start) + " ms · " + n + " tools");
        }
        if (m.path("id").asInt(0) == 1 && m.has("error")) {
          return new Outcome(false, ms(start), null, "The bridge answered: " + m.path("error").path("message")
              .asString("an error"));
        }
      }
      return new Outcome(false, ms(start), null, "The bridge ended without an answer" + exitCode(p) + ".");
    } catch (IOException | RuntimeException e) {
      return new Outcome(false, ms(start), null, "The bridge's answer can't be read.");
    }
  }

  private static String exitCode(Process p) {
    try {
      return p.waitFor(1, TimeUnit.SECONDS) ? " (exit code " + p.exitValue() + ")" : "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  /**
   * {@code initialize} and {@code tools/list} on the endpoint with {@code authorization}. The token goes to this core's
   * own listener only ({@code http://127.0.0.1:<port>/mcp/<slug>}), never to a URL a config file names.
   */
  static Outcome listTools(String url, String authorization, long start, String what) {
    URI target;
    try {
      target = URI.create(url);
    } catch (IllegalArgumentException e) {
      target = null;
    }
    if (target == null || !"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
        || target.getPort() <= 0 || target.getRawPath() == null || !target.getRawPath().startsWith("/mcp/")
        || target.getRawQuery() != null || target.getRawUserInfo() != null) {
      return new Outcome(false, ms(start), null, what + " was not asked: " + url + " isn't this gateway's endpoint.");
    }
    try (HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()) {
      HttpResponse<String> init = post(http, url, authorization, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":"
          + "\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":"
          + "{\"name\":\"gaterook-check\",\"version\":\"1\"}}}");
      if (init.statusCode() != 200) {
        return new Outcome(false, ms(start), null, what + " gave a token the endpoint refused (HTTP "
            + init.statusCode() + ").");
      }
      HttpResponse<String> list = post(http, url, authorization, "2025-11-25",
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
      int n = JSON.readTree(list.body()).path("result").path("tools").size();
      return new Outcome(true, ms(start), n, what + " OK · " + ms(start) + " ms · " + n + " tools");
    } catch (IOException | RuntimeException e) {
      return new Outcome(false, ms(start), null, "The endpoint didn't answer: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome(false, ms(start), null, "Interrupted.");
    }
  }

  private static HttpResponse<String> post(HttpClient http, String url, String authorization, String version,
      String body) throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("Authorization", authorization).POST(HttpRequest.BodyPublishers.ofString(body));
    if (version != null) {
      b.header("MCP-Protocol-Version", version);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /** Splits nothing: argv of a stdio entry as the config holds it ({@code command} then {@code args}). */
  public static List<String> argv(JsonNode entry) {
    List<String> out = new ArrayList<>();
    out.add(entry.path("command").asString(""));
    entry.path("args").forEach(a -> out.add(a.asString()));
    return out;
  }
}
