package ai.msrv.gaterook.core.admin.core;

import ai.msrv.gaterook.core.platform.EventBus;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/events}: the event stream of the desktop app (ARCHITECTURE.md §6), read by Electron's main process
 * and by the UI through it. Server-sent events with {@code id} = sequence number, so a reconnect with
 * {@code Last-Event-ID} resumes; a comment every {@code gateway.events.heartbeat} (15 s) keeps proxies and the client's
 * dead-connection detection happy.
 *
 * <p>The handler writes the stream itself on its request thread: Tomcat runs requests on virtual threads, so a parked
 * stream costs a few kilobytes, and no async dispatch is involved. The stream ends when the client goes away (a write
 * fails) or the core shuts down (the bus closes every subscription before graceful shutdown waits).
 */
@RestController
class EventStreamController {

  private final EventBus events;
  private final JsonMapper json;
  private final Duration heartbeat;

  EventStreamController(EventBus events, JsonMapper json,
      @Value("${gateway.events.heartbeat:15s}") Duration heartbeat) {
    this.events = events;
    this.json = json;
    this.heartbeat = heartbeat;
  }

  @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  void stream(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
      HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("X-Accel-Buffering", "no");
    ServletOutputStream out = response.getOutputStream();
    try (EventBus.Subscription subscription = events.subscribe(parse(lastEventId))) {
      // Commit the headers at once: the client knows the stream is open before the first event.
      write(out, ": stream open\nretry: 2000\n\n");
      while (!subscription.isClosed()) {
        Optional<EventBus.GatewayEvent> next = subscription.next(heartbeat);
        if (next.isPresent()) {
          EventBus.GatewayEvent event = next.get();
          write(out, "id: " + event.id() + "\nevent: " + event.type() + "\ndata: "
              + json.writeValueAsString(event.data()) + "\n\n");
        } else if (!subscription.isClosed()) {
          write(out, ": heartbeat\n\n");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // The client went away; nothing to answer.
    }
  }

  private static void write(ServletOutputStream out, String frame) throws IOException {
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static OptionalLong parse(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(lastEventId.strip()));
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }
}
