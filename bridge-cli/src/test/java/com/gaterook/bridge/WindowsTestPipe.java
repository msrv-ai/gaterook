package com.gaterook.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;

/** JDK-only named-pipe test server for the bridge's actual Windows transport. */
final class WindowsTestPipe implements AutoCloseable {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
  private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
  private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;
  private static final MethodHandle CREATE = function("CreateNamedPipeW", PTR, false,
      PTR, INT, INT, INT, INT, INT, INT, PTR);
  private static final MethodHandle CONNECT = function("ConnectNamedPipe", INT, true, PTR, PTR);
  private static final MethodHandle READ = function("ReadFile", INT, true, PTR, PTR, INT, PTR, PTR);
  private static final MethodHandle WRITE = function("WriteFile", INT, true, PTR, PTR, INT, PTR, PTR);
  private static final MethodHandle FLUSH = function("FlushFileBuffers", INT, false, PTR);
  private static final MethodHandle DISCONNECT = function("DisconnectNamedPipe", INT, false, PTR);
  private static final MethodHandle CLOSE = function("CloseHandle", INT, false, PTR);
  private static final long ERROR_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));

  private final MemorySegment handle;
  private final Thread thread;
  private volatile boolean closed;
  private volatile Throwable failure;

  WindowsTestPipe(String address, Function<Object, String> answer) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment name = arena.allocateFrom(ValueLayout.JAVA_BYTE,
          (address + "\0").getBytes(StandardCharsets.UTF_16LE));
      // Duplex byte pipe, nonblocking server handle, local clients only, one active request at a time.
      handle = (MemorySegment) invoke(CREATE, name, 3, 1 | 8, 1, 65536, 65536, 0, MemorySegment.NULL);
      if (handle.address() == -1L) throw new IOException("CreateNamedPipeW failed");
    }
    thread = Thread.ofPlatform().daemon().name("test-broker-pipe").start(() -> serve(answer));
  }

  private void serve(Function<Object, String> answer) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
      MemorySegment buffer = arena.allocate(8192);
      MemorySegment count = arena.allocate(INT);
      while (!closed) {
        int connected = (int) invoke(CONNECT, state, handle, MemorySegment.NULL);
        int error = state.get(INT, ERROR_OFFSET);
        if (connected == 0 && error != 535) { // ERROR_PIPE_CONNECTED: client arrived before ConnectNamedPipe.
          if (error == 536) { pause(); continue; } // ERROR_PIPE_LISTENING
          if (error == 232) { invoke(DISCONNECT, handle); continue; } // Client closed before accept.
          throw new IOException("ConnectNamedPipe failed: " + error);
        }
        try {
          String request = readLine(state, buffer, count);
          if (request == null) continue;
          byte[] response = (answer.apply(Json.parse(request)) + "\n").getBytes(StandardCharsets.UTF_8);
          if (response.length > 65536) throw new IOException("Test broker response is too large");
          MemorySegment bytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, response);
          int written = (int) invoke(WRITE, state, handle, bytes, response.length, count, MemorySegment.NULL);
          if (written == 0 || count.get(INT, 0) != response.length) {
            throw new IOException("WriteFile failed: " + state.get(INT, ERROR_OFFSET));
          }
          // Disconnect discards unread data, so wait until the client has consumed the response.
          invoke(FLUSH, handle);
        } finally {
          invoke(DISCONNECT, handle);
        }
      }
    } catch (Throwable error) {
      if (!closed) failure = error;
    } finally {
      try { invoke(CLOSE, handle); } catch (IOException error) { failure = error; }
    }
  }

  private String readLine(MemorySegment state, MemorySegment buffer, MemorySegment count) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!closed && System.nanoTime() < deadline) {
      int read = (int) invoke(READ, state, handle, buffer, 8192, count, MemorySegment.NULL);
      if (read == 0) {
        int error = state.get(INT, ERROR_OFFSET);
        if (error == 232 || error == 536) { pause(); continue; } // Empty or still listening.
        if (error == 109 || error == 233) return null; // Broken or disconnected pipe.
        throw new IOException("ReadFile failed: " + error);
      }
      int length = count.get(INT, 0);
      for (int i = 0; i < length; i++) {
        byte value = buffer.get(ValueLayout.JAVA_BYTE, i);
        if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
        bytes.write(value);
        if (bytes.size() > 65536) throw new IOException("Test broker request is too large");
      }
      if (length == 0) pause();
    }
    return null;
  }

  private static void pause() throws IOException {
    try { Thread.sleep(5); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
  }

  private static MethodHandle function(String name, java.lang.foreign.MemoryLayout result, boolean capture,
      java.lang.foreign.MemoryLayout... arguments) {
    return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), FunctionDescriptor.of(result, arguments),
        capture ? new Linker.Option[] {Linker.Option.captureCallState("GetLastError")} : new Linker.Option[0]);
  }

  private static Object invoke(MethodHandle function, Object... arguments) throws IOException {
    try { return function.invokeWithArguments(arguments); }
    catch (Throwable error) { throw new IOException("Windows test pipe call failed", error); }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    try {
      if (!thread.join(Duration.ofSeconds(10))) throw new IOException("Test broker pipe did not stop");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(error);
    }
    if (failure != null) throw new IOException("Test broker pipe failed", failure);
  }
}
