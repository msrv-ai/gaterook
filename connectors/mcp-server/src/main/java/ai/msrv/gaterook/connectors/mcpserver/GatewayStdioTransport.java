package ai.msrv.gaterook.connectors.mcpserver;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

/**
 * The stdio transport of an MCP server's process (ARCHITECTURE.md §7.5), in place of the SDK's
 * {@code StdioClientTransport}, which inherits the core's whole environment, hides the process (no pid, so no tree to
 * stop), logs every line it can't parse at ERROR and keeps platform threads of its own.
 *
 * <p>Here the process gets exactly the environment it is given and the work folder; stdout is read line by line on a
 * virtual thread (a line over {@code maxLine} characters or one that isn't JSON-RPC ends the session, with the reason
 * kept for the test: a server that writes logs to stdout corrupts the protocol), stderr goes to a sink that masks and
 * keeps it, and messages to the server are written by one writer thread so that answering a server's request never
 * waits for the server to read.
 */
final class GatewayStdioTransport implements McpClientTransport {

  private final List<String> argv;
  private final Map<String, String> env;
  private final Path workDir;
  private final McpJsonMapper mapper;
  private final int maxLine;
  private final Consumer<String> stderr;
  private final UnaryOperator<String> mask;
  private final BlockingQueue<McpSchema.JSONRPCMessage> outbound = new LinkedBlockingQueue<>();
  private volatile Process process;
  private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
  private volatile Consumer<Throwable> exceptions = t -> {};
  private volatile boolean closing;
  private volatile String failure;

  /**
   * @param stderr where the server's stderr lines go (its log, which masks them)
   * @param mask   masks the server's secrets in a line of stdout the transport quotes as the reason it stopped: that
   *               reason reaches the start's error, the process state the UI shows and the log
   */
  GatewayStdioTransport(List<String> argv, Map<String, String> env, Path workDir, McpJsonMapper mapper, int maxLine,
      Consumer<String> stderr, UnaryOperator<String> mask) {
    this.argv = List.copyOf(argv);
    this.env = Map.copyOf(env);
    this.workDir = workDir;
    this.mapper = mapper;
    this.maxLine = maxLine;
    this.stderr = stderr;
    this.mask = mask;
  }

  /**
   * Spawns the process; before {@link #connect}, so that a command that can't start says why in the caller's thread.
   *
   * @throws IOException as {@link ProcessBuilder#start} reports it ("No such file or directory")
   */
  Process start() throws IOException {
    ProcessBuilder pb = new ProcessBuilder(argv);
    // The SDK's default inherits every variable of the core; the process gets only what the person reviewed.
    pb.environment().clear();
    pb.environment().putAll(env);
    pb.directory(workDir.toFile());
    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
    pb.redirectError(ProcessBuilder.Redirect.PIPE);
    Process p = pb.start();
    process = p;
    Thread.ofVirtual().name("mcp-stderr-" + p.pid()).start(() -> readErrors(p.getErrorStream()));
    Thread.ofVirtual().name("mcp-stdin-" + p.pid()).start(() -> write(p));
    return p;
  }

  Process process() {
    return process;
  }

  /** Why the session ended on the gateway's side ("Wrote non-JSON to stdout: …"), or {@code null}. */
  String failure() {
    return failure;
  }

  @Override
  public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.fromRunnable(() -> {
      Process p = process;
      if (p == null) {
        throw new IllegalStateException("The process wasn't started");
      }
      this.handler = handler;
      Thread.ofVirtual().name("mcp-stdout-" + p.pid()).start(() -> read(p));
    });
  }

  @Override
  public void setExceptionHandler(Consumer<Throwable> handler) {
    this.exceptions = handler;
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    Process p = process;
    if (closing || p == null || !p.isAlive()) {
      return Mono.error(new McpTransportException(failure != null ? failure : "The server's process isn't running"));
    }
    outbound.add(message);
    return Mono.empty();
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return mapper.convertValue(data, typeRef);
  }

  /**
   * Ends the session: stdin closes (a well-behaved server exits on EOF) and the process gets a moment to do so. The
   * supervisor then stops what is left of the process tree.
   */
  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(() -> {
      closing = true;
      Process p = process;
      if (p == null) {
        return;
      }
      try {
        p.getOutputStream().close();
      } catch (IOException e) {
        // Already closed by a dead process.
      }
      try {
        p.waitFor(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  // ── Threads ─────────────────────────────────────────────────────────────────────────────────────────────────

  private void read(Process p) {
    try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
      LineReader lines = new LineReader(r, maxLine);
      String line;
      while ((line = lines.next()) != null) {
        if (line.isBlank()) {
          continue;
        }
        McpSchema.JSONRPCMessage message;
        try {
          message = McpSchema.deserializeJsonRpcMessage(mapper, line);
        } catch (IOException | RuntimeException e) {
          // Masked before the cut (a cut secret would show its start) and after the cleaning (which may join one).
          fail("Wrote non-JSON to stdout: " + mask.apply(Texts.clean(mask.apply(line), 200, false)));
          return;
        }
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> h = handler;
        if (h != null) {
          h.apply(Mono.just(message)).subscribe(ignored -> {}, error -> {});
        }
      }
    } catch (LineReader.TooLongException e) {
      fail("Sent a message over " + (maxLine / (1024 * 1024)) + " MB on stdout");
    } catch (IOException e) {
      // The process ended; the supervisor hears it from its exit.
    }
  }

  private void readErrors(InputStream in) {
    try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      LineReader lines = new LineReader(r, 8 * 1024);
      while (true) {
        String line;
        try {
          line = lines.next();
        } catch (LineReader.TooLongException e) {
          stderr.accept(e.head() + "…");
          continue;
        }
        if (line == null) {
          return;
        }
        stderr.accept(line);
      }
    } catch (IOException e) {
      // stderr closed with the process.
    }
  }

  private void write(Process p) {
    OutputStream out = p.getOutputStream();
    try {
      while (!closing && p.isAlive()) {
        McpSchema.JSONRPCMessage m = outbound.poll(200, TimeUnit.MILLISECONDS);
        if (m == null) {
          continue;
        }
        // One message per line (MCP stdio): a line break inside a JSON string is already escaped by the writer.
        byte[] json = mapper.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
        out.write(json);
        out.write('\n');
        out.flush();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      if (!closing) {
        exceptions.accept(new McpTransportException("The server stopped reading its input", e));
      }
    }
  }

  private void fail(String reason) {
    failure = reason;
    Process p = process;
    if (p != null) {
      // A protocol the gateway can't trust any more: the supervisor sees the exit and reports the reason.
      p.destroyForcibly();
    }
  }

  /** Reads lines (LF or CRLF) of at most {@code max} characters. */
  static final class LineReader {

    static final class TooLongException extends IOException {
      private static final long serialVersionUID = 1L;
      private final String head;

      TooLongException(String head) {
        super("line too long");
        this.head = head;
      }

      String head() {
        return head;
      }
    }

    private final Reader in;
    private final int max;
    private final char[] buffer = new char[8192];
    private int pos;
    private int end;

    LineReader(Reader in, int max) {
      this.in = in;
      this.max = max;
    }

    /** The next line without its terminator, or {@code null} at the end. */
    String next() throws IOException {
      StringBuilder line = new StringBuilder();
      boolean any = false;
      while (true) {
        if (pos == end) {
          end = in.read(buffer, 0, buffer.length);
          pos = 0;
          if (end <= 0) {
            end = 0;
            return any ? line.toString() : null;
          }
        }
        any = true;
        char c = buffer[pos++];
        if (c == '\n') {
          int len = line.length();
          if (len > 0 && line.charAt(len - 1) == '\r') {
            line.setLength(len - 1);
          }
          return line.toString();
        }
        if (line.length() >= max) {
          String head = line.substring(0, Math.min(200, line.length()));
          skipLine();
          throw new TooLongException(head);
        }
        line.append(c);
      }
    }

    private void skipLine() throws IOException {
      while (true) {
        if (pos == end) {
          end = in.read(buffer, 0, buffer.length);
          pos = 0;
          if (end <= 0) {
            end = 0;
            return;
          }
        }
        if (buffer[pos++] == '\n') {
          return;
        }
      }
    }
  }

  /** How long a process may take to exit after stdin closed. */
  static final Duration EXIT_GRACE = Duration.ofSeconds(2);
}
