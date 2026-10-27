package ai.msrv.gaterook.core.platform;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OpenTelemetry spans of MCP calls (ARCHITECTURE.md A17, §5 step 12) through Micrometer Tracing: a SERVER span per
 * request whose parent is the client's {@code traceparent} (W3C format only; anything else is ignored), and a child
 * span of the same trace for the execution of an approved call (its context is kept on the request). The span's ids go
 * into the audit ({@code tool_call.trace_id}, {@code span_id}). Exporting is off until Settings names an OTLP address.
 * Without a tracer (some tests) ids are made up locally, so the audit still groups by trace.
 */
public class CallTracing {

  /** The only format accepted from clients ({@code version-traceid-spanid-flags}). */
  public static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Propagator.Getter<Map<String, String>> GET = Map::get;
  private static final Propagator.Setter<Map<String, String>> SET = Map::put;

  /**
   * A started span.
   *
   * @param fromClient the trace was the client's (its {@code traceparent} was valid)
   */
  public record Started(String traceId, String spanId, String traceparent, boolean fromClient, Span span)
      implements AutoCloseable {

    public void tag(String key, String value) {
      if (span != null && value != null) {
        span.tag(key, value);
      }
    }

    public void error(String type) {
      tag("error.type", type);
    }

    @Override
    public void close() {
      if (span != null) {
        span.end();
      }
    }
  }

  private final Tracer tracer;
  private final Propagator propagator;

  public CallTracing(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /** Whether a client's value is a W3C {@code traceparent} the gateway takes as parent. */
  public static boolean valid(String traceparent) {
    return traceparent != null && TRACEPARENT.matcher(traceparent).matches()
        && !traceparent.substring(3, 35).equals("0".repeat(32)) && !traceparent.substring(36, 52).equals("0".repeat(16));
  }

  /** A SERVER span {@code name}, child of {@code clientTraceparent} when it is valid. */
  public Started server(String name, String clientTraceparent, Map<String, String> tags) {
    return start(name, clientTraceparent, Span.Kind.SERVER, tags, valid(clientTraceparent));
  }

  /** A span of an approved call's execution, child of the request's stored context. */
  public Started child(String name, String storedTraceparent, Map<String, String> tags) {
    // Micrometer has no INTERNAL kind: no kind is what OpenTelemetry reads as internal.
    return start(name, storedTraceparent, null, tags, false);
  }

  private Started start(String name, String parent, Span.Kind kind, Map<String, String> tags, boolean fromClient) {
    boolean hasParent = valid(parent);
    if (tracer == null || propagator == null) {
      String traceId = hasParent ? parent.substring(3, 35) : hex(16);
      String spanId = hex(8);
      return new Started(traceId, spanId, "00-" + traceId + "-" + spanId + "-01", fromClient, null);
    }
    Span.Builder builder;
    if (hasParent) {
      Map<String, String> carrier = new LinkedHashMap<>();
      carrier.put("traceparent", parent);
      builder = propagator.extract(carrier, GET);
    } else {
      builder = tracer.spanBuilder().setNoParent();
    }
    builder.name(name);
    if (kind != null) {
      builder.kind(kind);
    }
    tags.forEach((k, v) -> {
      if (v != null) {
        builder.tag(k, v);
      }
    });
    Span span = builder.start();
    Map<String, String> out = new LinkedHashMap<>();
    propagator.inject(span.context(), out, SET);
    String traceparent = out.getOrDefault("traceparent",
        "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01");
    return new Started(span.context().traceId(), span.context().spanId(), traceparent, fromClient, span);
  }

  private static String hex(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
