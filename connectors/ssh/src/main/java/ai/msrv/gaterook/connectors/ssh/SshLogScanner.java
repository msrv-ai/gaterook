package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.apache.sshd.client.session.ClientSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Bounded server-command windows with authoritative local masking, parsing and signed continuation. */
final class SshLogScanner {
  private static final String PURPOSE = "ssh-logs.scan";
  private static final String PREFIX = "lc1.";
  private static final int CHUNK_BYTES = 1024 * 1024;
  private static final int MAX_LINE_BYTES = 256 * 1024;
  private static final Duration FINISH_MARGIN = Duration.ofMillis(500);
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private SshLogScanner() {}

  static ToolResult scan(ClientSession session, SshLogSettings settings, ConnectionConfig cfg, ToolCall call, ExecContext ctx, byte[] fingerprintKey)
      throws IOException, ConnectorException {
    JsonNode saved = openCursor(call, cfg, ctx);
    String epoch = digest(fingerprintKey, "ssh-logs.cursor-key".getBytes(StandardCharsets.US_ASCII));
    if (saved != null && !epoch.equals(saved.path("epoch").asString())) {
      throw ConnectorException.badInput("The log cursor belongs to a previous gateway session. Start again without cursor.");
    }
    Instant anchor;
    try {
      anchor = saved == null ? ctx.clock().instant() : Instant.parse(saved.path("anchor").asString());
    } catch (RuntimeException e) {
      throw ConnectorException.badInput("The log cursor is invalid. Start the query again without cursor.");
    }
    SshLogQuery query = SshLogQuery.parse(call, settings, ctx.limits(), anchor);
    if (saved != null && !query.queryHash().equals(saved.path("query").asString())) {
      throw ConnectorException.badInput("This log cursor belongs to another query. Repeat its filters or start again without cursor.");
    }
    String nonce = saved == null ? java.util.UUID.randomUUID().toString() : saved.path("nonce").asString("");
    if (!nonce.matches("[A-Za-z0-9-]{16,80}")) {
      throw ConnectorException.badInput("The log cursor has an invalid scan identity. Start again without cursor.");
    }
    String prefilter = query.tail() ? null : SshLogPrefilter.grepExpression(query, ctx).orElse(null);
    State state = new State(query, cfg, ctx, anchor, fingerprintKey, epoch, nonce);
    state.fileIndex = saved == null ? (query.tail() ? query.files().size() - 1 : 0) : saved.path("index").asInt(-1);
    state.position = saved == null ? (query.tail() ? -1 : query.offset()) : saved.path("position").asLong(-1);
    state.discard = saved != null && saved.path("discard").asBoolean();
    state.snapshot = saved == null || saved.path("snapshot").isNull() ? null : Snapshot.from(saved.path("snapshot"));
    if (state.fileIndex < 0 || state.fileIndex >= query.files().size() || (saved != null && state.position < 0 && !(query.tail() && state.position == -1 && state.snapshot == null))) {
      throw ConnectorException.badInput("The log cursor has an invalid file position. Start again without cursor.");
    }
    while (state.fileIndex >= 0 && state.fileIndex < query.files().size() && state.reason == null) {
      if (state.shouldStop()) {
        break;
      }
      String file = query.files().get(state.fileIndex);
      // Scope hidden-content fingerprints to this scan and exact file, preventing cross-file comparison oracles.
      String domain = JSON.objectNode().put("connection", cfg.id()).put("version", cfg.version())
          .put("root", settings.root()).put("file", file).put("scan", nonce).toString();
      state.activeFileKey = HexFormat.of().parseHex(digest(fingerprintKey, domain.getBytes(StandardCharsets.UTF_8)));
      Source source = new Source(session, settings, file, ctx);
      long initial = state.position;
      if (state.snapshot == null) {
        SshLogCommands.Result first;
        if (query.startLine() > 0) {
          var range = SshLogCommands.bounds(session, settings, file, query.startLine(), query.endLine(), ctx);
          state.position = range.start();
          first = prefilter == null
              ? source.load(state.position, (int) Math.min(CHUNK_BYTES, range.end() - range.start()))
              : source.load(0, 256);
          new Snapshot(range.snapshot().identity(), range.snapshot().size(), range.snapshot().modified(),
              range.start(), range.end()).verify(first.before());
          state.snapshot = source.snapshot(first, range.start(), range.end(), state.activeFileKey);
        } else {
          first = query.tail()
              ? source.tail(query.maxLines(), Math.min(query.scanBytes(), CHUNK_BYTES))
              : prefilter != null ? source.load(0, 256)
                  : state.position == 0 && query.needle() == null && !query.timeFiltered() && query.levels().isEmpty()
                      ? source.head(query.maxLines(), Math.min(state.scanLeft(), CHUNK_BYTES))
                      : source.load(state.position, (int) Math.min(CHUNK_BYTES, state.scanLeft()));
          state.snapshot = source.snapshot(first, 0, first.before().size(), state.activeFileKey);
        }
      } else {
        source.expected = state.snapshot;
        state.snapshot.verifyProof(source, state.activeFileKey);
      }
      source.expected = state.snapshot;
      if (state.position == -1) state.position = state.snapshot.end;
      if (state.position < state.snapshot.start || state.position > state.snapshot.end) {
        throw ConnectorException.badInput("The byte offset is past the end of this log file. It may have rotated; start again at offset 0.");
      }
      if (!query.tail() && !state.discard && state.position > state.snapshot.start && state.position < state.snapshot.end) {
        byte[] previous = source.read(state.position - 1, 1);
        if (previous.length != 1 || previous[0] != '\n') {
          throw ConnectorException.badInput("The byte offset must start at a complete line boundary. Use nextCursor or nextOffset from a previous result.");
        }
      }
      if (query.tail()) reverse(source, state);
      else if (prefilter != null && !state.discard) grepForward(source, state, prefilter);
      else forward(source, state);
      if (state.reason == null && ((query.tail() && state.position == state.snapshot.start)
          || (!query.tail() && state.position == state.snapshot.end))) {
        state.fileIndex += query.tail() ? -1 : 1;
        if (state.fileIndex >= 0 && state.fileIndex < query.files().size()) state.position = query.tail() ? -1 : 0;
        state.snapshot = null;
        state.discard = false;
      } else if (state.reason == null && state.position == initial) {
        throw ConnectorException.resourceError("The remote log did not advance during scanning.", null);
      }
    }
    return state.result();
  }

  private static JsonNode openCursor(ToolCall call, ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    String cursor = call.string("cursor");
    if (cursor == null) {
      return null;
    }
    if (!cursor.startsWith(PREFIX) || cursor.length() > 8192) {
      throw ConnectorException.badInput("Pass nextCursor unchanged, or start the log query again without cursor.");
    }
    JsonNode saved = ctx.handles().open(PURPOSE, cursor.substring(PREFIX.length())).orElseThrow(() ->
        ConnectorException.badInput("The log cursor is invalid or expired. Start again without cursor."));
    if (saved.path("v").asInt() != 2 || !cfg.id().equals(saved.path("connection").asString())
        || cfg.version() != saved.path("version").asLong(-1)) {
      throw ConnectorException.badInput("The log cursor belongs to another connection or its settings have changed. Start again without cursor.");
    }
    return saved;
  }

  /** Candidate filtering changes transfer volume, never the source window or hidden-match scan progress. */
  private static void grepForward(Source source, State state, String expression)
      throws IOException, ConnectorException {
    while (state.position < state.snapshot.end && !state.shouldStop()) {
      int amount = (int) Math.min(1024 * 1024, Math.min(state.scanLeft(), state.snapshot.end - state.position));
      var window = SshLogGrep.read(source.session, source.settings, source.file, state.position, amount,
          expression, source.ctx);
      state.snapshot.verify(window.before());
      if (!window.startsAtLineBoundary() || window.lastCompleteEnd() <= window.start()) {
        // A physical line crossing the whole window needs the bounded oversized-line state machine.
        forward(source, state);
        return;
      }
      state.scannedBytes += window.end() - window.start();
      // A fixed, query-independent proof never exposes raw grep match locations in the cursor.
      state.snapshot.proofOffset = Math.max(window.start(), window.end() - 256);
      byte[] proof = source.read(state.snapshot.proofOffset, (int) (window.end() - state.snapshot.proofOffset));
      state.snapshot.proofLength = proof.length;
      state.snapshot.proof = digest(state.activeFileKey, proof);
      for (var line : window.lines()) {
        if (line.textBytes().length > MAX_LINE_BYTES) {
          state.skippedLines++;
        } else if (!state.accept(line.textBytes(), line.offset(), line.endOffset())) {
          state.position = line.offset();
          return;
        }
        state.position = line.endOffset();
        if (state.reason != null) return;
      }
      state.position = window.lastCompleteEnd();
    }
  }

  private static void forward(Source source, State state)
      throws IOException, ConnectorException {
    long lineStart = state.position;
    long readPosition = state.position;
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    boolean oversized = state.discard;
    while (readPosition < state.snapshot.end && !state.shouldStop()) {
      byte[] block = readBlock(source, readPosition,
          (int) Math.min(source.forwardAvailable(readPosition), Math.min(state.snapshot.end - readPosition, state.scanLeft())), state);
      if (block.length == 0) {
        throw changed();
      }
      for (int i = 0; i < block.length; i++) {
        long after = readPosition + i + 1;
        if (block[i] == '\n') {
          if (oversized) {
            state.skippedLines++;
          } else if (!state.accept(line.toByteArray(), lineStart, after)) {
            state.position = lineStart;
            return;
          }
          line.reset();
          oversized = false;
          state.discard = false;
          state.position = after;
          lineStart = after;
          if (state.reason != null) {
            return;
          }
        } else if (!oversized) {
          if (line.size() == MAX_LINE_BYTES) {
            oversized = true;
            line.reset();
          } else {
            line.write(block[i]);
          }
        }
      }
      readPosition += block.length;
      if (oversized) {
        state.position = readPosition;
        state.discard = true;
      }
    }
    if (readPosition == state.snapshot.end) {
      if (oversized) {
        state.skippedLines++;
      } else if (line.size() > 0 && !state.accept(line.toByteArray(), lineStart, readPosition)) {
        state.position = lineStart;
        return;
      }
      state.position = readPosition;
      state.discard = false;
      // The read completed exactly at its scan budget: there is nothing to continue in this file.
      if ("scan_limit".equals(state.reason)) {
        state.reason = null;
      }
    } else if (!oversized) {
      // Do not carry log text in cursors. A partial ordinary line is reread from its boundary on the next page.
      state.position = lineStart;
    }
  }

  private static void reverse(Source source, State state)
      throws IOException, ConnectorException {
    long readPosition = state.position;
    long lineEnd = state.position;
    ByteArrayOutputStream reversed = new ByteArrayOutputStream();
    boolean oversized = state.discard;
    boolean first = !oversized;
    while (readPosition > state.snapshot.start && !state.shouldStop()) {
      int amount = (int) Math.min(source.reverseAvailable(readPosition), Math.min(readPosition - state.snapshot.start, state.scanLeft()));
      long blockStart = readPosition - amount;
      byte[] block = readBlock(source, blockStart, amount, state);
      if (block.length != amount) {
        throw changed();
      }
      for (int i = block.length - 1; i >= 0; i--) {
        long at = blockStart + i;
        if (first) {
          first = false;
          if (block[i] == '\n') {
            continue;
          }
        }
        if (block[i] == '\n') {
          if (oversized) {
            state.skippedLines++;
          } else if (!state.accept(reverseBytes(reversed.toByteArray()), at + 1, lineEnd)) {
            state.position = lineEnd;
            return;
          }
          reversed.reset();
          oversized = false;
          state.discard = false;
          lineEnd = at + 1;
          state.position = lineEnd;
          if (state.reason != null) {
            return;
          }
        } else if (!oversized) {
          if (reversed.size() == MAX_LINE_BYTES) {
            oversized = true;
            reversed.reset();
          } else {
            reversed.write(block[i]);
          }
        }
      }
      readPosition = blockStart;
      if (source.boundaryAt(readPosition) && lineEnd > readPosition) {
        if (oversized) state.skippedLines++;
        else if (!state.accept(reverseBytes(reversed.toByteArray()), readPosition, lineEnd)) {
          state.position = lineEnd;
          return;
        }
        reversed.reset();
        oversized = false;
        state.discard = false;
        lineEnd = readPosition;
        state.position = readPosition;
        first = true;
        if (state.reason != null) return;
      }
      if (oversized) {
        state.position = readPosition;
        state.discard = true;
      }
    }
    if (readPosition == state.snapshot.start) {
      if (oversized) {
        state.skippedLines++;
      } else if (lineEnd > 0 && !state.accept(reverseBytes(reversed.toByteArray()), 0, lineEnd)) {
        state.position = lineEnd;
        return;
      }
      state.position = state.snapshot.start;
      state.discard = false;
      if ("scan_limit".equals(state.reason)) {
        state.reason = null;
      }
    } else if (!oversized) {
      state.position = lineEnd;
    }
  }

  private static byte[] reverseBytes(byte[] data) {
    for (int left = 0, right = data.length - 1; left < right; left++, right--) {
      byte saved = data[left];
      data[left] = data[right];
      data[right] = saved;
    }
    return data;
  }

  private static byte[] readBlock(Source source, long offset, int amount, State state)
      throws ConnectorException {
    byte[] data = source.read(offset, amount);
    state.scannedBytes += data.length;
    state.snapshot.proofOffset = offset;
    state.snapshot.proofLength = data.length;
    state.snapshot.proof = digest(state.activeFileKey, data);
    return data;
  }

  private static void checkCompression(byte[] bytes) throws ConnectorException {
    if (bytes.length < 4) return;
    boolean compressed = ((bytes[0] & 255) == 0x1f && (bytes[1] & 255) == 0x8b)
        || (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4)
        || ((bytes[0] & 255) == 0x28 && (bytes[1] & 255) == 0xb5 && (bytes[2] & 255) == 0x2f && (bytes[3] & 255) == 0xfd)
        || (bytes[0] == 'B' && bytes[1] == 'Z' && bytes[2] == 'h')
        || ((bytes[0] & 255) == 0xfd && bytes[1] == '7' && bytes[2] == 'z' && bytes[3] == 'X');
    if (compressed) throw ConnectorException.badInput("Compressed log files are not supported. Select an uncompressed UTF-8 log file.");
  }

  /** Commands return a bounded window and validate the same opened file before and after producing it. */
  private static final class Source {
    final ClientSession session;
    final SshLogSettings settings;
    final String file;
    final ExecContext ctx;
    SshLogCommands.Result cache;
    Snapshot expected;

    Source(ClientSession session, SshLogSettings settings, String file, ExecContext ctx) {
      this.session = session;
      this.settings = settings;
      this.file = file;
      this.ctx = ctx;
    }

    SshLogCommands.Result load(long offset, int length) throws ConnectorException {
      var loaded = SshLogCommands.bytes(session, settings, file, offset, length, ctx);
      if (expected != null) expected.verify(loaded.before());
      cache = loaded;
      return loaded;
    }

    SshLogCommands.Result tail(int lines, long bytes) throws ConnectorException {
      cache = SshLogCommands.tail(session, settings, file, lines, bytes, ctx);
      return cache;
    }

    SshLogCommands.Result head(int lines, long bytes) throws ConnectorException {
      cache = SshLogCommands.head(session, settings, file, lines, bytes, ctx);
      return cache;
    }

    int forwardAvailable(long position) {
      return cache != null && position >= cache.offset() && position < cache.offset() + cache.data().length
          ? (int) Math.min(CHUNK_BYTES, cache.offset() + cache.data().length - position) : CHUNK_BYTES;
    }

    boolean boundaryAt(long position) {
      return cache != null && position == cache.offset() && cache.startsAtLineBoundary();
    }

    int reverseAvailable(long position) {
      return cache != null && position > cache.offset() && position <= cache.offset() + cache.data().length
          ? (int) Math.min(CHUNK_BYTES, position - cache.offset()) : CHUNK_BYTES;
    }

    byte[] read(long offset, int length) throws ConnectorException {
      if (length == 0) return new byte[0];
      if (cache == null || offset < cache.offset() || offset + length > cache.offset() + cache.data().length) {
        load(offset, length);
      }
      int from = (int) (offset - cache.offset());
      int available = Math.min(length, cache.data().length - from);
      if (available < 0) throw changed();
      return Arrays.copyOfRange(cache.data(), from, from + available);
    }

    Snapshot snapshot(SshLogCommands.Result first, long start, long end, byte[] key) throws ConnectorException {
      Snapshot snapshot = new Snapshot(first.before().identity(), first.before().size(), first.before().modified(), start, end);
      expected = snapshot;
      // The head also rejects compressed input and detects same-size rewrites even when timestamp resolution is coarse.
      byte[] head = read(0, (int) Math.min(256, snapshot.size));
      checkCompression(head);
      snapshot.head = digest(key, head);
      // Retain the selected window after the small header check, avoiding another transfer for the actual read.
      cache = first;
      return snapshot;
    }
  }

  private static ConnectorException changed() {
    return ConnectorException.badInput("A selected log file changed, was truncated or rotated during this scan. Start again without cursor or offset.");
  }

  /** Signed handles are readable by their bearer: fingerprints must not expose raw hashes of masked log text. */
  private static String digest(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class Snapshot {
    final String identity;
    final long size;
    final String modified;
    final long start;
    final long end;
    String head;
    long proofOffset;
    int proofLength;
    String proof;

    Snapshot(String identity, long size, String modified, long start, long end) {
      this.identity = identity;
      this.size = size;
      this.modified = modified;
      this.start = start;
      this.end = end;
    }

    static Snapshot from(JsonNode json) throws ConnectorException {
      Snapshot out = new Snapshot(json.path("identity").asString(), json.path("size").asLong(-1),
          json.path("modified").asString(), json.path("start").asLong(-1), json.path("end").asLong(-1));
      out.head = json.path("head").asString();
      out.proofOffset = json.path("proofOffset").asLong(0);
      out.proofLength = json.path("proofLength").asInt(0);
      out.proof = json.path("proof").asString(null);
      if (out.size < 0 || out.start < 0 || out.end < out.start || out.end > out.size || out.proofOffset < 0
          || out.proofLength < 0 || out.proofLength > CHUNK_BYTES || out.proofOffset > out.size - out.proofLength) {
        throw ConnectorException.badInput("The log cursor has an invalid snapshot. Start again without cursor.");
      }
      return out;
    }

    void verify(SshLogCommands.Snapshot current) throws ConnectorException {
      if (!identity.equals(current.identity()) || current.size() < size
          || (current.size() == size && !modified.equals(current.modified()))) throw changed();
    }

    void verifyProof(Source source, byte[] key) throws ConnectorException {
      if (!head.equals(digest(key, source.read(0, (int) Math.min(256, size))))) throw changed();
      if (proof != null && !proof.equals(digest(key, source.read(proofOffset, proofLength)))) throw changed();
    }

    ObjectNode json() {
      ObjectNode out = JSON.objectNode().put("identity", identity).put("size", size).put("modified", modified)
          .put("start", start).put("end", end).put("head", head)
          .put("proofOffset", proofOffset).put("proofLength", proofLength);
      if (proof != null) out.put("proof", proof);
      return out;
    }
  }

  private static final class State {
    final SshLogQuery query;
    final ConnectionConfig cfg;
    final ExecContext ctx;
    final Instant anchor;
    final byte[] fingerprintKey;
    final String epoch;
    final String nonce;
    byte[] activeFileKey;
    final List<ObjectNode> entries = new ArrayList<>();
    final long maxOutputBytes;
    long outputBytes;
    int fileIndex;
    long position;
    boolean discard;
    Snapshot snapshot;
    String reason;
    long scannedBytes;
    int skippedLines;
    int unparsedLines;

    State(SshLogQuery query, ConnectionConfig cfg, ExecContext ctx, Instant anchor, byte[] fingerprintKey, String epoch, String nonce) throws ConnectorException {
      this.query = query;
      this.cfg = cfg;
      this.ctx = ctx;
      this.anchor = anchor;
      this.fingerprintKey = fingerprintKey;
      this.epoch = epoch;
      this.nonce = nonce;
      this.maxOutputBytes = Math.min(ctx.limits().maxResponseBytes(), ctx.limits().maxOutputBytes());
      this.outputBytes = 2048 + query.files().stream().mapToLong(s -> s.length() * 6L + 4).sum();
      if (outputBytes + 256 > maxOutputBytes) {
        throw ConnectorException.badInput("The output limit is too small for log entries and a safe continuation cursor. Raise Max output and Max response bytes.");
      }
    }

    long scanLeft() {
      return Math.max(0, query.scanBytes() - scannedBytes);
    }

    boolean shouldStop() throws ConnectorException {
      if (ctx.cancelled() || Thread.currentThread().isInterrupted()) {
        throw ConnectorException.timeout("The SSH log scan was cancelled.");
      }
      if (reason != null) {
        return true;
      }
      if (scanLeft() == 0) {
        reason = "scan_limit";
      } else if (ctx.remaining().compareTo(FINISH_MARGIN) <= 0) {
        reason = "timeout";
      }
      return reason != null;
    }

    boolean accept(byte[] bytes, long start, long end) throws ConnectorException {
      int length = bytes.length;
      if (length > 0 && bytes[length - 1] == '\r') {
        length--;
      }
      String decoded = new String(bytes, 0, length, StandardCharsets.UTF_8);
      Arrays.fill(bytes, (byte) 0);
      String masked = query.maskRecord(decoded, ctx);
      SshLogParser.Parsed parsed = query.parseRecord(masked);
      if (query.timeFiltered() && parsed.timestamp() == null) {
        unparsedLines++;
      }
      if (!query.matches(parsed)) {
        return true;
      }
      ObjectNode entry = JSON.objectNode().put("file", query.files().get(fileIndex)).put("offset", start)
          .put("endOffset", end).put("text", masked);
      if (parsed.timestamp() == null) {
        entry.putNull("timestamp");
      } else {
        entry.put("timestamp", parsed.timestamp().toString());
      }
      if (parsed.level() == null) {
        entry.putNull("level");
      } else {
        entry.put("level", parsed.level());
      }
      long extra = entry.toString().getBytes(StandardCharsets.UTF_8).length
          + JSON.textNode(masked).toString().getBytes(StandardCharsets.UTF_8).length + 2L;
      if (outputBytes + extra > maxOutputBytes) {
        if (entries.isEmpty()) {
          throw ConnectorException.badInput("One matching log line exceeds the response limit. Raise Max output and Max response bytes.");
        }
        reason = "output_limit";
        return false;
      }
      entries.add(entry);
      outputBytes += extra;
      if (entries.size() == query.maxLines()) {
        reason = "line_limit";
      }
      return true;
    }

    ToolResult result() throws ConnectorException {
      boolean complete = fileIndex < 0 || fileIndex >= query.files().size();
      if (!complete && snapshot != null && !discard
          && ((!query.tail() && position == snapshot.end && fileIndex == query.files().size() - 1)
              || (query.tail() && position == snapshot.start && fileIndex == 0))) {
        complete = true;
      }
      if (query.tail()) {
        Collections.reverse(entries);
      }
      ObjectNode result = JSON.objectNode().put("file", query.files().size() == 1 ? query.files().getFirst() : "")
          .put("offset", query.offset()).put("nextOffset", Math.max(0, position)).put("bytesRead", scannedBytes)
          .put("tail", query.tail()).put("truncated", !complete).put("partialLine", false)
          .put("scannedBytes", scannedBytes).put("scanComplete", complete).put("skippedLines", skippedLines)
          .put("unparsedLines", unparsedLines).put("anchor", anchor.toString()).put("maxLines", query.maxLines());
      ArrayNode files = result.putArray("files");
      query.files().forEach(files::add);
      ArrayNode outputEntries = result.putArray("entries");
      ArrayNode lines = result.putArray("lines");
      entries.forEach(entry -> {
        outputEntries.add(entry);
        lines.add(entry.path("text").asString());
      });
      ArrayNode warnings = result.putArray("warnings");
      if (skippedLines > 0 || discard) {
        warnings.add("Physical lines larger than 256 KiB are skipped, never matched as complete records.");
      }
      if (unparsedLines > 0) {
        warnings.add("Lines without a valid parsed timestamp were excluded by the time filter.");
      }
      if (query.files().size() > 1) {
        warnings.add("Results follow configured file order and physical line order, not a global chronological sort.");
      }
      if (complete) {
        result.putNull("nextCursor").putNull("truncationReason");
      } else {
        ObjectNode cursor = JSON.objectNode().put("v", 2).put("connection", cfg.id()).put("version", cfg.version())
            .put("query", query.queryHash()).put("anchor", anchor.toString()).put("epoch", epoch).put("nonce", nonce).put("index", fileIndex)
            .put("position", position).put("discard", discard);
        if (snapshot == null) {
          cursor.putNull("snapshot");
        } else {
          cursor.set("snapshot", snapshot.json());
        }
        String sealed = PREFIX + ctx.handles().seal(PURPOSE, cursor, Duration.ofHours(1));
        if (sealed.length() > 8192) {
          throw ConnectorException.resourceError("The log continuation cursor exceeded its size limit.", null);
        }
        result.put("nextCursor", sealed).put("truncationReason", reason == null ? "scan_limit" : reason);
      }
      if (result.toString().getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
        throw ConnectorException.badInput("The output limit is too small for this log page and its cursor. Raise the response limit.");
      }
      ToolResult response = ToolResult.json(result, null);
      return complete ? response : response.truncated(switch (reason == null ? "scan_limit" : reason) {
        case "line_limit" -> "row_limit";
        case "timeout" -> "timeout";
        default -> "output_limit";
      });
    }
  }
}
