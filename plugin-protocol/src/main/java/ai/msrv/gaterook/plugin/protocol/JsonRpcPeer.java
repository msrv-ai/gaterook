package ai.msrv.gaterook.plugin.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One end of a GPP/1 conversation (report 16 §4.2, §9): JSON-RPC 2.0 requests, responses and notifications in both
 * directions over one input and one output stream, one JSON object per line. Both processes use this class: the core
 * numbers its requests ({@code 1, 2, …}), the host prefixes its own ({@code "h1", "h2", …}), so the two id spaces
 * never meet.
 *
 * <ul>
 *   <li>A platform thread reads frames; responses complete their caller's future on it, notifications are handled on
 *       it in order (a {@code taint/add} sent before a response is applied before that response is seen), and each
 *       incoming request runs on its own virtual thread.</li>
 *   <li>An incoming request is counted in {@link #inFlight()} <em>before</em> its thread starts: a host that sees the
 *       end of its input waits for that count, so an answer is never lost to the race between EOF and a thread not
 *       yet scheduled (report 16 §1, pitfall 2).</li>
 *   <li>{@code $/cancel} interrupts the request it names and runs its {@link Incoming#onCancel} callbacks.</li>
 *   <li>A line that isn't a JSON object (a plugin's native code printing to stdout) is handed to
 *       {@link Options#strayLine} and skipped; the conversation goes on. A frame over the size limit ends it.</li>
 *   <li>Writes are whole frames under one lock, built in a buffer that is zeroed after the write; a
 *       {@link RawResult} writes a value (a secret) straight from its {@code char[]} and a {@link RawDecoder} reads
 *       one straight into a {@code char[]}, so neither becomes a {@code String} on the way.</li>
 * </ul>
 */
public final class JsonRpcPeer implements AutoCloseable {

  /** What this side answers. */
  public interface Handler {

    /**
     * Handles one request on its own virtual thread.
     *
     * @return a {@link JsonNode}, a {@link RawResult} or {@code null} (JSON {@code null})
     * @throws RpcException the error to answer with
     */
    Object handle(String method, JsonNode params, Incoming call) throws RpcException;

    /** A notification, on the reader thread: keep it short and never block. */
    default void notification(String method, JsonNode params) {}
  }

  /** The request being handled. */
  public interface Incoming {

    Object id();

    String method();

    /** Whether the other side sent {@code $/cancel} for it. */
    boolean cancelled();

    /** Runs {@code action} when the request is cancelled (at once, if it already was). */
    void onCancel(Runnable action);
  }

  /** A result written by hand, e.g. a secret from its {@code char[]}; {@link #dispose()} runs after the write. */
  public interface RawResult {

    void write(JsonGenerator generator);

    default void dispose() {}
  }

  /** Reads a result value from the parser positioned at its first token, instead of building a tree. */
  @FunctionalInterface
  public interface RawDecoder<T> {
    T decode(JsonParser parser);
  }

  /** The other side is gone (end of stream, broken pipe, protocol violation). */
  public static final class PeerClosedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PeerClosedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A request this side sent. */
  public static final class Call<T> {
    private final Object id;
    private final String method;
    private final CompletableFuture<T> future;

    Call(Object id, String method, CompletableFuture<T> future) {
      this.id = id;
      this.method = method;
      this.future = future;
    }

    public Object id() {
      return id;
    }

    public String method() {
      return method;
    }

    public CompletableFuture<T> future() {
      return future;
    }

    /**
     * Waits for the answer.
     *
     * @throws RpcException        the error the other side answered with
     * @throws TimeoutException    no answer in time (the request stays pending: cancel it)
     * @throws PeerClosedException the other side went away first
     */
    public T get(Duration timeout) throws RpcException, TimeoutException, InterruptedException {
      try {
        return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RpcException r) {
          throw r;
        }
        if (cause instanceof RuntimeException r) {
          throw r;
        }
        throw new IllegalStateException(cause);
      }
    }
  }

  /** How a peer behaves. */
  public static final class Options {
    private String name = "gpp";
    private boolean hostIds;
    private int maxFrameBytes = Gpp.MAX_FRAME_BYTES;
    private Consumer<String> strayLine = line -> {};
    private ThreadFactory requestThreads;
    private int maxIncoming = Integer.MAX_VALUE;

    public static Options defaults() {
      return new Options();
    }

    /**
     * At most this many incoming requests run at once; one more is answered {@code -32012} at once, without a thread.
     * The core bounds what a host (which runs a plugin's code) can make it do.
     */
    public Options maxIncoming(int value) {
      if (value < 1) {
        throw new IllegalArgumentException("maxIncoming is at least 1");
      }
      maxIncoming = value;
      return this;
    }

    /** Names the reader and request threads. */
    public Options name(String value) {
      name = Objects.requireNonNull(value, "value");
      return this;
    }

    /** {@code true} on the host: its request ids are {@code "h<n>"}. */
    public Options hostIds(boolean value) {
      hostIds = value;
      return this;
    }

    public Options maxFrameBytes(int value) {
      maxFrameBytes = value;
      return this;
    }

    /** Lines that aren't JSON objects, cut to 200 characters (they may hold anything: mask before logging). */
    public Options strayLine(Consumer<String> value) {
      strayLine = Objects.requireNonNull(value, "value");
      return this;
    }

    public Options requestThreads(ThreadFactory value) {
      requestThreads = value;
      return this;
    }
  }

  private record Pending(String method, RawDecoder<?> decoder, CompletableFuture<Object> future) {}

  private static final class Running implements Incoming {
    private final Object id;
    private final String method;
    private final List<Runnable> onCancel = new ArrayList<>();
    private volatile Thread thread;
    private volatile boolean cancelled;

    Running(Object id, String method) {
      this.id = id;
      this.method = method;
    }

    @Override
    public Object id() {
      return id;
    }

    @Override
    public String method() {
      return method;
    }

    @Override
    public boolean cancelled() {
      return cancelled;
    }

    @Override
    public void onCancel(Runnable action) {
      boolean now;
      synchronized (onCancel) {
        now = cancelled;
        if (!now) {
          onCancel.add(action);
        }
      }
      if (now) {
        quietly(action);
      }
    }

    void cancel() {
      List<Runnable> actions;
      synchronized (onCancel) {
        if (cancelled) {
          return;
        }
        cancelled = true;
        actions = List.copyOf(onCancel);
        onCancel.clear();
      }
      actions.forEach(JsonRpcPeer::quietly);
      Thread t = thread;
      if (t != null) {
        t.interrupt();
      }
    }
  }

  private final FrameReader reader;
  private final InputStream in;
  private final OutputStream out;
  private final Handler handler;
  private final Options options;
  private final ThreadFactory requestThreads;
  private final Object writeLock = new Object();
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Map<String, Running> running = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Object idle = new Object();
  private final CompletableFuture<Throwable> closed = new CompletableFuture<>();
  private volatile boolean outputClosed;

  public JsonRpcPeer(InputStream in, OutputStream out, Handler handler, Options options) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.options = Objects.requireNonNull(options, "options");
    this.reader = new FrameReader(in, options.maxFrameBytes);
    this.requestThreads = options.requestThreads != null ? options.requestThreads
        : Thread.ofVirtual().name(options.name + "-call-", 0).factory();
  }

  /** Starts reading on a daemon platform thread. */
  public JsonRpcPeer start() {
    Thread.ofPlatform().daemon().name(options.name + "-reader").start(this::readLoop);
    return this;
  }

  // ── Sending ─────────────────────────────────────────────────────────────────────────────────────────────────

  public Call<JsonNode> request(String method, JsonNode params) {
    return request(method, params, null);
  }

  /** Sends a request whose result {@code decoder} reads from the frame (or a tree when {@code decoder} is null). */
  @SuppressWarnings("unchecked")
  public <T> Call<T> request(String method, JsonNode params, RawDecoder<T> decoder) {
    long n = nextId.incrementAndGet();
    Object id = options.hostIds ? "h" + n : (Object) n;
    CompletableFuture<T> future = new CompletableFuture<>();
    String key = key(id);
    pending.put(key, new Pending(method, decoder, (CompletableFuture<Object>) future));
    if (closed.isDone()) {
      pending.remove(key);
      future.completeExceptionally(new PeerClosedException("The other side is gone", closed.getNow(null)));
      return new Call<>(id, method, future);
    }
    try {
      send(g -> {
        g.writeStartObject();
        g.writeName("jsonrpc");
        g.writeString("2.0");
        writeId(g, id);
        g.writeName("method");
        g.writeString(method);
        if (params != null) {
          g.writeName("params");
          g.writeTree(params);
        }
        g.writeEndObject();
      });
    } catch (PeerClosedException e) {
      pending.remove(key);
      future.completeExceptionally(e);
    }
    return new Call<>(id, method, future);
  }

  /** A notification; quietly dropped when the other side is gone. */
  public void notify(String method, JsonNode params) {
    try {
      send(g -> {
        g.writeStartObject();
        g.writeName("jsonrpc");
        g.writeString("2.0");
        g.writeName("method");
        g.writeString(method);
        if (params != null) {
          g.writeName("params");
          g.writeTree(params);
        }
        g.writeEndObject();
      });
    } catch (PeerClosedException e) {
      // Nobody is listening any more.
    }
  }

  /** Gives up on {@code call}: tells the other side ({@code $/cancel}) and fails the caller's future. */
  public void cancel(Call<?> call, String reason) {
    Pending p = pending.remove(key(call.id()));
    ObjectNode params = GppJson.object();
    if (call.id() instanceof Long l) {
      params.put("id", l);
    } else {
      params.put("id", String.valueOf(call.id()));
    }
    params.put("reason", reason);
    notify(Gpp.CANCEL, params);
    if (p != null) {
      p.future().cancel(false);
    }
  }

  // ── State ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** Incoming requests being handled. */
  public int inFlight() {
    return inFlight.get();
  }

  /** Requests this side sent that wait for an answer. */
  public int pending() {
    return pending.size();
  }

  /** Waits until no incoming request is being handled; false when {@code timeout} passed first. */
  public boolean awaitIdle(Duration timeout) throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    synchronized (idle) {
      while (inFlight.get() > 0) {
        long left = end - System.nanoTime();
        if (left <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(idle, left);
      }
    }
    return true;
  }

  /** Completes when the input ended: with {@code null} at a clean end, else with the cause. */
  public CompletableFuture<Throwable> closed() {
    return closed;
  }

  /** Closes the output (the other side sees the end of its input) and the input. */
  @Override
  public void close() {
    closeOutput();
    try {
      in.close();
    } catch (IOException e) {
      // Already gone.
    }
  }

  /** Closes only the output: the other side sees EOF and may still answer what it is busy with. */
  public void closeOutput() {
    synchronized (writeLock) {
      if (outputClosed) {
        return;
      }
      outputClosed = true;
      try {
        out.close();
      } catch (IOException e) {
        // Already gone.
      }
    }
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  private void readLoop() {
    Throwable cause = null;
    try {
      while (true) {
        int n = reader.next();
        if (n < 0) {
          break;
        }
        try {
          dispatch(reader.buffer(), n);
        } finally {
          reader.wipe(n);
        }
      }
    } catch (IOException | RuntimeException e) {
      cause = e;
    }
    finish(cause);
  }

  private void finish(Throwable cause) {
    PeerClosedException gone = new PeerClosedException(cause == null ? "The other side closed the stream"
        : "The conversation ended: " + cause.getMessage(), cause);
    for (String key : List.copyOf(pending.keySet())) {
      Pending p = pending.remove(key);
      if (p != null) {
        p.future().completeExceptionally(gone);
      }
    }
    closed.complete(cause);
  }

  private void dispatch(byte[] buffer, int length) {
    Object id = null;
    String method = null;
    JsonNode params = null;
    boolean hasResult = false;
    Object result = null;
    JsonNode error = null;
    try (JsonParser p = GppJson.MAPPER.createParser(buffer, 0, length)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        stray(buffer, length);
        return;
      }
      while (p.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = p.currentName();
        JsonToken token = p.nextToken();
        switch (name) {
          case "id" -> {
            if (token == JsonToken.VALUE_NUMBER_INT) {
              id = p.getLongValue();
            } else if (token == JsonToken.VALUE_STRING) {
              id = p.getString();
            }
          }
          case "method" -> method = token == JsonToken.VALUE_STRING ? p.getString() : null;
          case "params" -> params = GppJson.MAPPER.readTree(p);
          case "result" -> {
            hasResult = true;
            Pending waiting = id == null ? null : pending.get(key(id));
            result = waiting != null && waiting.decoder() != null ? waiting.decoder().decode(p)
                : GppJson.MAPPER.readTree(p);
          }
          case "error" -> error = GppJson.MAPPER.readTree(p);
          default -> p.skipChildren();
        }
      }
    } catch (JacksonException | IllegalArgumentException e) {
      stray(buffer, length);
      return;
    }
    if (method != null) {
      if (id == null) {
        onNotification(method, params);
      } else {
        startRequest(id, method, params);
      }
      return;
    }
    if (id != null && (hasResult || error != null)) {
      Pending p = pending.remove(key(id));
      if (p == null) {
        // A late answer to a request this side gave up on.
        return;
      }
      if (error != null) {
        p.future().completeExceptionally(new RpcException(error.path("code").asInt(Gpp.INTERNAL_ERROR),
            error.path("message").asString("Error"), error.get("data")));
      } else {
        p.future().complete(result instanceof NullNode ? null : result);
      }
      return;
    }
    stray(buffer, length);
  }

  private void onNotification(String method, JsonNode params) {
    if (Gpp.CANCEL.equals(method)) {
      JsonNode id = params == null ? null : params.get("id");
      if (id != null) {
        Running r = running.get(id.isNumber() ? key(id.asLong()) : key(id.asString()));
        if (r != null) {
          r.cancel();
        }
      }
      return;
    }
    try {
      handler.notification(method, params);
    } catch (RuntimeException e) {
      // A notification that fails never stops the conversation.
    }
  }

  private void startRequest(Object id, String method, JsonNode params) {
    if (inFlight.get() >= options.maxIncoming) {
      respondError(id, Gpp.HOST_BUSY, "Too many requests at once (" + options.maxIncoming + ")", null);
      return;
    }
    Running r = new Running(id, method);
    String key = key(id);
    running.put(key, r);
    inFlight.incrementAndGet();
    try {
      requestThreads.newThread(() -> run(r, key, params)).start();
    } catch (RuntimeException | OutOfMemoryError e) {
      done(key);
      respondError(id, Gpp.HOST_BUSY, "Can't start the call: " + e, null);
    }
  }

  private void run(Running r, String key, JsonNode params) {
    r.thread = Thread.currentThread();
    if (r.cancelled) {
      Thread.currentThread().interrupt();
    }
    try {
      Object result = handler.handle(r.method, params, r);
      if (r.cancelled && result == null) {
        respondError(r.id, Gpp.CANCELLED, "Cancelled", null);
      } else {
        respond(r.id, result);
      }
    } catch (RpcException e) {
      respondError(r.id, e.code(), e.getMessage(), e.data());
    } catch (RuntimeException e) {
      if (r.cancelled) {
        respondError(r.id, Gpp.CANCELLED, "Cancelled", null);
      } else {
        respondError(r.id, Gpp.INTERNAL_ERROR, e.getClass().getName() + ": " + e.getMessage(), errorData(e));
      }
    } finally {
      done(key);
    }
  }

  private void done(String key) {
    running.remove(key);
    if (inFlight.decrementAndGet() <= 0) {
      synchronized (idle) {
        idle.notifyAll();
      }
    }
  }

  /** {@code data} of an internal error: the exception's type, message and first frames (report 16 §4.3). */
  public static ObjectNode errorData(Throwable t) {
    ObjectNode data = GppJson.object();
    data.put("type", t.getClass().getName());
    data.put("message", t.getMessage());
    ArrayNode frames = data.putArray("frames");
    StackTraceElement[] trace = t.getStackTrace();
    for (int i = 0; i < Math.min(5, trace.length); i++) {
      frames.add(trace[i].toString());
    }
    return data;
  }

  private void respond(Object id, Object result) {
    if (result instanceof RawResult raw) {
      try {
        sendQuietly(g -> {
          header(g, id);
          g.writeName("result");
          raw.write(g);
          g.writeEndObject();
        });
      } finally {
        raw.dispose();
      }
      return;
    }
    JsonNode node = result == null ? NullNode.getInstance() : (JsonNode) result;
    sendQuietly(g -> {
      header(g, id);
      g.writeName("result");
      g.writeTree(node);
      g.writeEndObject();
    });
  }

  private void respondError(Object id, int code, String message, JsonNode data) {
    sendQuietly(g -> {
      header(g, id);
      g.writeName("error");
      g.writeStartObject();
      g.writeName("code");
      g.writeNumber(code);
      g.writeName("message");
      g.writeString(message == null ? "Error" : message);
      if (data != null) {
        g.writeName("data");
        g.writeTree(data);
      }
      g.writeEndObject();
      g.writeEndObject();
    });
  }

  private static void header(JsonGenerator g, Object id) {
    g.writeStartObject();
    g.writeName("jsonrpc");
    g.writeString("2.0");
    writeId(g, id);
  }

  private static void writeId(JsonGenerator g, Object id) {
    g.writeName("id");
    if (id instanceof Long l) {
      g.writeNumber(l);
    } else {
      g.writeString(String.valueOf(id));
    }
  }

  private void sendQuietly(Consumer<JsonGenerator> body) {
    try {
      send(body);
    } catch (PeerClosedException e) {
      // The answer has nobody to go to.
    }
  }

  private void send(Consumer<JsonGenerator> body) {
    WipedBuffer buffer = new WipedBuffer();
    try {
      try (JsonGenerator g = GppJson.MAPPER.createGenerator(buffer)) {
        body.accept(g);
      }
      buffer.write('\n');
      synchronized (writeLock) {
        if (outputClosed) {
          throw new PeerClosedException("The output is closed", null);
        }
        try {
          out.write(buffer.bytes(), 0, buffer.size());
          out.flush();
        } catch (IOException e) {
          outputClosed = true;
          throw new PeerClosedException("Can't write to the other side: " + e.getMessage(), e);
        }
      }
    } finally {
      buffer.wipe();
    }
  }

  private void stray(byte[] buffer, int length) {
    int n = Math.min(length, 800);
    String text = new String(buffer, 0, n, StandardCharsets.UTF_8);
    if (text.length() > 200) {
      text = text.substring(0, 200);
    }
    try {
      options.strayLine.accept(text);
    } catch (RuntimeException e) {
      // Logging a stray line must not stop the reader.
    }
  }

  private static String key(Object id) {
    return id instanceof Long l ? "#" + l : "s:" + id;
  }

  private static void quietly(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      // A cancel callback that fails must not hide the cancellation.
    }
  }

  /** A growable byte buffer that zeroes every array it leaves behind. */
  private static final class WipedBuffer extends OutputStream {
    private byte[] bytes = new byte[512];
    private int size;

    @Override
    public void write(int b) {
      ensure(size + 1);
      bytes[size++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
      ensure(size + len);
      System.arraycopy(b, off, bytes, size, len);
      size += len;
    }

    private void ensure(int capacity) {
      if (capacity > bytes.length) {
        byte[] bigger = Arrays.copyOf(bytes, Math.max(capacity, bytes.length * 2));
        Arrays.fill(bytes, (byte) 0);
        bytes = bigger;
      }
    }

    byte[] bytes() {
      return bytes;
    }

    int size() {
      return size;
    }

    void wipe() {
      Arrays.fill(bytes, (byte) 0);
      size = 0;
    }
  }
}
