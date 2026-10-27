package ai.msrv.gaterook.connectors.logs;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loki's {@code query_range} as Loki 3.7.9 answers it: {@code start} inclusive, {@code end} exclusive, the newest (or
 * oldest) {@code limit} entries of all streams, grouped by stream. Entries that share a nanosecond come back in a
 * random order on every request, so a cursor that relies on the order of ties fails here. An entry is visible only
 * once its arrival time has passed: logs reach Loki after their own timestamp.
 */
final class FakeLoki extends Dispatcher {

  record Line(String stream, long ns, String text, long arrivesAtNs) {}

  final List<Line> lines = new CopyOnWriteArrayList<>();
  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Random random;
  private final LongSupplier nowNs;

  FakeLoki(long seed, LongSupplier nowNs) {
    this.random = new Random(seed);
    this.nowNs = nowNs;
  }

  void add(String stream, long ns, String text) {
    lines.add(new Line(stream, ns, text, Long.MIN_VALUE));
  }

  void add(String stream, long ns, String text, long arrivesAtNs) {
    lines.add(new Line(stream, ns, text, arrivesAtNs));
  }

  @Override
  public MockResponse dispatch(RecordedRequest request) {
    requests.add(request);
    String path = request.getUrl().encodedPath();
    if (!path.endsWith("/loki/api/v1/query_range")) {
      return new MockResponse.Builder().code(404).body("404 page not found\n").build();
    }
    Map<String, String> q = query(request.getUrl().encodedQuery());
    long start = Long.parseLong(q.get("start"));
    long end = Long.parseLong(q.get("end"));
    int limit = Integer.parseInt(q.get("limit"));
    boolean backward = !"forward".equals(q.get("direction"));
    long now = nowNs.getAsLong();
    List<Line> matching = new ArrayList<>();
    for (Line l : lines) {
      if (l.ns() >= start && l.ns() < end && l.arrivesAtNs() <= now) {
        matching.add(l);
      }
    }
    Collections.shuffle(matching, random);
    Comparator<Line> byTime = Comparator.comparingLong(Line::ns);
    matching.sort(backward ? byTime.reversed() : byTime);
    List<Line> page = matching.subList(0, Math.min(limit, matching.size()));
    Map<String, List<Line>> byStream = new LinkedHashMap<>();
    for (Line l : page) {
      byStream.computeIfAbsent(l.stream(), k -> new ArrayList<>()).add(l);
    }
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("status", "success");
    ObjectNode data = root.putObject("data");
    data.put("resultType", "streams");
    ArrayNode result = data.putArray("result");
    List<String> streams = new ArrayList<>(byStream.keySet());
    Collections.shuffle(streams, random);
    for (String s : streams) {
      ObjectNode st = result.addObject();
      st.putObject("stream").put("app", s).put("service_name", s);
      ArrayNode values = st.putArray("values");
      for (Line l : byStream.get(s)) {
        ArrayNode v = values.addArray();
        v.add(Long.toString(l.ns()));
        v.add(l.text());
        v.addObject().putObject("structuredMetadata").put("detected_level", l.text().contains("ERROR") ? "error"
            : "info");
      }
    }
    data.putObject("stats").putObject("summary").put("totalBytesProcessed", 1234).put("totalLinesProcessed", 10);
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(root.toString())
        .build();
  }

  static Map<String, String> query(String encoded) {
    Map<String, String> out = new LinkedHashMap<>();
    if (encoded == null) {
      return out;
    }
    for (String pair : encoded.split("&")) {
      int eq = pair.indexOf('=');
      out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }
}
