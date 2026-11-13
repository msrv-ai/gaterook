package com.gaterook.connectors.mcpserver;

import com.gaterook.connector.api.ConnectorException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.node.ObjectNode;

/**
 * How the {@link UpstreamClient} reaches one server: a process's stdin and stdout ({@link GatewayStdioTransport}),
 * Streamable HTTP ({@link GatewayHttpTransport}) or the HTTP+SSE transport of 2024-11-05 ({@link GatewaySseTransport}).
 * A channel moves JSON-RPC objects and knows nothing of what they mean: the client numbers the requests, matches the
 * answers and decides which headers a message needs.
 */
interface Channel {

  /** Where a channel hands what the server sent. */
  interface Sink {

    /**
     * One message of the server.
     *
     * @param stream the id of the client's request whose HTTP answer carried it ({@code notifications/progress} of a
     *               call, its log messages), or {@code null} when it came on the process's stdout or a stream of its
     *               own
     */
    void message(ObjectNode message, Long stream);

    /** Nothing more can arrive: the process ended, the event stream closed. Every request still waiting fails. */
    void closed(String reason);
  }

  /** One message on its way: for HTTP, the POST and the reading of its answer. */
  interface Exchange {

    /**
     * Completes once the message went out and, for HTTP, its answer was read to the end; fails with what went wrong
     * ({@link Failure}, {@link SessionGone}, a {@code SignInRequired} in the cause chain).
     */
    CompletableFuture<Void> done();

    /** Stops reading the answer and lets the connection go: over HTTP the server sees its stream close. */
    void abort();

    /** An exchange that is over already. */
    static Exchange completed() {
      return of(CompletableFuture.completedFuture(null));
    }

    /** An exchange that failed before anything went out. */
    static Exchange failed(Throwable why) {
      return of(CompletableFuture.failedFuture(why));
    }

    private static Exchange of(CompletableFuture<Void> done) {
      return new Exchange() {
        @Override
        public CompletableFuture<Void> done() {
          return done;
        }

        @Override
        public void abort() {}
      };
    }
  }

  /** Starts reading: the process's stdout, the event stream of an SSE server. */
  void open(Sink sink) throws ConnectorException;

  /**
   * Sends one message.
   *
   * @param headers what the protocol puts in HTTP headers for this message ({@code MCP-Protocol-Version},
   *                {@code Mcp-Method}, …); a process gets none of them
   */
  Exchange send(ObjectNode message, Map<String, String> headers);

  /** Whether the answer to a request comes within its own exchange (HTTP): when the exchange ends, it can't come. */
  boolean answersInExchange();

  /**
   * Opens the stream on which a Streamable HTTP server of the 2025 revisions sends what isn't an answer
   * ({@code notifications/tools/list_changed}, {@code notifications/resources/updated}); reconnects while the channel
   * lives. Nothing for the other channels: their one stream carries everything already.
   *
   * @param headers the protocol headers of the session ({@code MCP-Protocol-Version})
   */
  default void listen(Map<String, String> headers) {}

  boolean alive();

  void close();

  /**
   * Ends the channel because the client gave up the session ({@code reason}: what the server did). A process is
   * stopped and its exit carries the reason, so its supervisor records it as the crash; an HTTP channel just closes.
   */
  default void abandon(String reason) {
    close();
  }

  /**
   * A message didn't reach the server, or its answer couldn't be read.
   *
   * @param status the HTTP status the server answered with, or {@code 0} when none came
   */
  final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    Failure(String message, int status, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    Failure(String message) {
      this(message, 0, null);
    }

    int status() {
      return status;
    }

    /** The first failure of a channel in a cause chain, or {@code null}. */
    static Failure in(Throwable t) {
      for (Throwable x = t; x != null; x = x.getCause()) {
        if (x instanceof Failure f) {
          return f;
        }
        if (x.getCause() == x) {
          break;
        }
      }
      return null;
    }
  }

  /** A Streamable HTTP server forgot the session (HTTP 404 with its id): the request never reached it. */
  final class SessionGone extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SessionGone(String message) {
      super(message, null, false, false);
    }

    static boolean in(Throwable t) {
      for (Throwable x = t; x != null; x = x.getCause()) {
        if (x instanceof SessionGone) {
          return true;
        }
        if (x.getCause() == x) {
          break;
        }
      }
      return false;
    }
  }
}
