package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code redis_scan} (report 05 §3.5): {@code SCAN cursor MATCH pattern COUNT h [TYPE t]} with
 * {@code h = clamp(left, 10, 1000)}, again until {@code count} keys, the end of the keyspace, 50 iterations or the
 * scan budget ({@code min(2 s, the call's time left)}). A SCAN batch is never cut — Redis can't resume inside one —
 * so a page may hold up to one batch more than {@code count}.
 *
 * <p>Keys outside the connection's allowlist or in its denylist are dropped here, before anything else is sent about
 * them: ACL key patterns don't limit {@code SCAN}, so a user restricted to {@code ~cache:*} still sees every key name
 * (report 05 §3.5). {@code details} adds {@code TYPE} and {@code PTTL} of the kept keys in one round trip.
 */
final class ScanLoop {

  static final int MAX_ITERATIONS = 50;
  static final Duration SCAN_BUDGET = Duration.ofSeconds(2);
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private ScanLoop() {}

  static RedisReads.Reading scan(RedisSession s, RedisRequest.Scan r, Budget budget, ExecContext ctx,
      String connectionId) throws ConnectorException {
    long t0 = System.nanoTime();
    String cursor = r.cursor() == null ? "0"
        : Cursors.openScan(ctx, r.cursor(), connectionId, r.pattern(), r.type()).redisCursor();
    long budgetNanos = Math.min(SCAN_BUDGET.toNanos(), Math.max(0, ctx.remaining().toNanos() - 500_000_000L));
    List<byte[]> keys = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    int hidden = 0;
    int iterations = 0;
    long bytes = 0;
    boolean cutBySize = false;
    do {
      int want = Math.clamp(budget.elements() - keys.size(), 10, 1_000);
      List<Object> reply = Replies.list(s.call(Cmd.SCAN, scanArgs(cursor, r.pattern(), r.type(), want)));
      iterations++;
      String next = Replies.text(reply.isEmpty() ? null : reply.get(0));
      List<byte[]> batch = new ArrayList<>();
      int batchHidden = 0;
      for (Object o : Replies.list(reply.size() > 1 ? reply.get(1) : null)) {
        byte[] k = Replies.bytes(o);
        if (k == null) {
          continue;
        }
        if (s.settings().keyAllowed(k)) {
          batch.add(k);
        } else {
          batchHidden++;
        }
      }
      long batchBytes = 0;
      for (byte[] k : batch) {
        batchBytes += cost(k);
      }
      if (bytes + batchBytes > budget.bytes() && !keys.isEmpty()) {
        // This batch doesn't fit: the next page starts from the cursor that returned it.
        cutBySize = true;
        break;
      }
      if (bytes + batchBytes > budget.bytes()) {
        int kept = 0;
        for (byte[] k : batch) {
          if (bytes + cost(k) > budget.bytes()) {
            break;
          }
          bytes += cost(k);
          keys.add(k);
          kept++;
        }
        notes.add(String.format(Locale.US, "%,d keys of this page didn't fit the size limit; the cursor goes past "
            + "them.", batch.size() - kept));
        hidden += batchHidden;
        cursor = next == null ? "0" : next;
        cutBySize = true;
        break;
      }
      bytes += batchBytes;
      keys.addAll(batch);
      hidden += batchHidden;
      cursor = next == null ? "0" : next;
    } while (!cursor.equals("0") && keys.size() < budget.elements() && iterations < MAX_ITERATIONS
        && System.nanoTime() - t0 < budgetNanos && !ctx.cancelled());

    ObjectNode out = JSON.objectNode();
    out.put("pattern", r.pattern());
    if (r.type() == null) {
      out.putNull("type");
    } else {
      out.put("type", r.type());
    }
    ArrayNode list = out.putArray("keys");
    List<Object[]> details = r.details() && !keys.isEmpty() ? RedisReads.typesAndTtls(s, keys) : null;
    for (int i = 0; i < keys.size(); i++) {
      byte[] k = keys.get(i);
      ObjectNode e = list.addObject();
      Optional<String> text = RedisValues.utf8(k);
      if (text.isPresent()) {
        e.put("key", text.get());
      } else {
        e.put("key", Base64.getEncoder().encodeToString(k)).put("encoding", "base64");
      }
      if (details != null) {
        String type = (String) details.get(i)[0];
        Long ttl = (Long) details.get(i)[1];
        if (type != null && !type.equals("none")) {
          e.put("type", type);
        }
        if (ttl != null && ttl >= 0) {
          e.put("ttlMs", ttl);
        } else {
          e.putNull("ttlMs");
        }
      }
    }
    boolean complete = cursor.equals("0");
    out.put("returned", keys.size()).put("iterations", iterations);
    if (complete) {
      out.putNull("nextCursor");
    } else {
      out.put("nextCursor", Cursors.sealScan(ctx, connectionId, r.pattern(), r.type(),
          new Cursors.ScanState(cursor)));
    }
    out.put("complete", complete).put("hiddenByPolicy", hidden);
    if (cutBySize) {
      out.put("truncated", true);
    }
    if (!complete) {
      notes.add("Keys may repeat across pages (Redis SCAN guarantee).");
    }
    if (!notes.isEmpty()) {
      ArrayNode n = out.putArray("notes");
      notes.forEach(n::add);
    }
    out.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
    return new RedisReads.Reading(out, notes);
  }

  /** {@code SCAN cursor [MATCH pattern] COUNT h [TYPE t]}; a lone {@code *} matches everything without MATCH. */
  static Object[] scanArgs(String cursor, String pattern, String type, int count) {
    List<Object> a = new ArrayList<>();
    a.add(cursor);
    if (!pattern.equals("*")) {
      a.add("MATCH");
      a.add(RedisReads.utf8(pattern));
    }
    a.add("COUNT");
    a.add(count);
    if (type != null) {
      a.add("TYPE");
      a.add(type);
    }
    return a.toArray();
  }

  private static long cost(byte[] key) {
    return key.length + 48L;
  }
}
