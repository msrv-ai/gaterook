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
import org.apache.sshd.sftp.client.SftpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Streaming physical-line scanner. No remote shell, regex engine, index, or unbounded line buffer. */
final class SshLogScanner {
  private static final String PURPOSE = "ssh-logs.scan";
  private static final String PREFIX = "lc1.";
  private static final int CHUNK_BYTES = 32768;
  private static final int MAX_LINE_BYTES = 256 * 1024;
  private static final Duration FINISH_MARGIN = Duration.ofMillis(500);
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private SshLogScanner() {}

  static ToolResult scan(SftpClient sftp, SshLogSettings settings, ConnectionConfig cfg, ToolCall call, ExecContext ctx, byte[] fingerprintKey)
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
      var attrs = SshLogFiles.file(sftp, settings, file, ctx);
      String path = settings.allowed(file);
      try (var handle = sftp.open(path, SftpClient.OpenMode.Read)) {
        SshLogFiles.file(sftp, settings, file, ctx);
        SshLogFiles.budget(sftp, ctx);
        var opened = sftp.stat(handle);
        if (!opened.isRegularFile() || !sameAttributes(attrs, opened)) {
          throw changed();
        }
        Snapshot now = snapshot(sftp, handle, opened, ctx, state.activeFileKey);
        if (state.snapshot != null) {
          state.snapshot.verify(now);
          state.snapshot.verifyProof(sftp, handle, ctx, state.activeFileKey);
        }
        state.snapshot = now;
        if (state.position == -1) {
          state.position = now.size;
        }
        if (state.position > now.size) {
          throw ConnectorException.badInput("The byte offset is past the end of this log file. It may have rotated; start again at offset 0.");
        }
        if (!query.tail() && !state.discard && state.position > 0 && state.position < now.size) {
          byte[] previous = new byte[1];
          SshLogFiles.budget(sftp, ctx);
          if (sftp.read(handle, state.position - 1, previous) != 1 || previous[0] != '\n') {
            throw ConnectorException.badInput("The byte offset must start at a complete line boundary. Use nextCursor or nextOffset from a previous result.");
          }
        }
        long initial = state.position;
        if (query.tail()) {
          reverse(sftp, handle, state);
        } else {
          forward(sftp, handle, state);
        }
        // Check both the open handle and path after scanning. Do not return bytes from a changed file silently.
        var after = SshLogFiles.file(sftp, settings, file, ctx);
        SshLogFiles.budget(sftp, ctx);
        var afterHandle = sftp.stat(handle);
        if (!sameAttributes(opened, after) || !sameAttributes(opened, afterHandle)) {
          throw changed();
        }
        state.snapshot.verify(snapshot(sftp, handle, afterHandle, ctx, state.activeFileKey));
        state.snapshot.verifyProof(sftp, handle, ctx, state.activeFileKey);
        if (state.reason == null && ((query.tail() && state.position == 0) || (!query.tail() && state.position == now.size))) {
          state.fileIndex += query.tail() ? -1 : 1;
          if (state.fileIndex >= 0 && state.fileIndex < query.files().size()) {
            state.position = query.tail() ? -1 : 0;
          }
          state.snapshot = null;
          state.discard = false;
        } else if (state.reason == null && state.position == initial) {
          throw ConnectorException.resourceError("The remote log did not advance during scanning.", null);
        }
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
    if (saved.path("v").asInt() != 1 || !cfg.id().equals(saved.path("connection").asString())
        || cfg.version() != saved.path("version").asLong(-1)) {
      throw ConnectorException.badInput("The log cursor belongs to another connection or its settings have changed. Start again without cursor.");
    }
    return saved;
  }

  private static void forward(SftpClient sftp, SftpClient.Handle handle, State state)
      throws IOException, ConnectorException {
    long lineStart = state.position;
    long readPosition = state.position;
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    boolean oversized = state.discard;
    while (readPosition < state.snapshot.size && !state.shouldStop()) {
      byte[] block = readBlock(sftp, handle, readPosition,
          (int) Math.min(CHUNK_BYTES, Math.min(state.snapshot.size - readPosition, state.scanLeft())), state);
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
    if (readPosition == state.snapshot.size) {
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

  private static void reverse(SftpClient sftp, SftpClient.Handle handle, State state)
      throws IOException, ConnectorException {
    long readPosition = state.position;
    long lineEnd = state.position;
    ByteArrayOutputStream reversed = new ByteArrayOutputStream();
    boolean oversized = state.discard;
    boolean first = !oversized;
    while (readPosition > 0 && !state.shouldStop()) {
      int amount = (int) Math.min(CHUNK_BYTES, Math.min(readPosition, state.scanLeft()));
      long blockStart = readPosition - amount;
      byte[] block = readBlock(sftp, handle, blockStart, amount, state);
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
      if (oversized) {
        state.position = readPosition;
        state.discard = true;
      }
    }
    if (readPosition == 0) {
      if (oversized) {
        state.skippedLines++;
      } else if (lineEnd > 0 && !state.accept(reverseBytes(reversed.toByteArray()), 0, lineEnd)) {
        state.position = lineEnd;
        return;
      }
      state.position = 0;
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

  private static byte[] readBlock(SftpClient sftp, SftpClient.Handle handle, long offset, int amount, State state)
      throws IOException, ConnectorException {
    SshLogFiles.budget(sftp, state.ctx);
    byte[] data = new byte[amount];
    int total = 0;
    while (total < amount) {
      SshLogFiles.budget(sftp, state.ctx);
      int n = sftp.read(handle, offset + total, data, total, amount - total);
      if (n <= 0) {
        break;
      }
      total += n;
    }
    state.scannedBytes += total;
    if (total != amount) {
      data = Arrays.copyOf(data, total);
    }
    state.snapshot.proofOffset = offset;
    state.snapshot.proofLength = total;
    state.snapshot.proof = digest(state.activeFileKey, data);
    return data;
  }

  private static Snapshot snapshot(SftpClient sftp, SftpClient.Handle handle, SftpClient.Attributes attrs, ExecContext ctx, byte[] fingerprintKey)
      throws IOException, ConnectorException {
    long size = attrs.getSize();
    if (size < 0 || attrs.getModifyTime() == null) {
      throw ConnectorException.resourceError("The SFTP server did not provide valid log size and modification time.", null);
    }
    int bytes = (int) Math.min(256, size);
    return new Snapshot(size, attrs.getModifyTime().toString(), fingerprint(sftp, handle, 0, bytes, ctx, fingerprintKey),
        fingerprint(sftp, handle, size - bytes, bytes, ctx, fingerprintKey));
  }

  private static boolean sameAttributes(SftpClient.Attributes first, SftpClient.Attributes second) {
    return second.isRegularFile() && first.getSize() == second.getSize()
        && java.util.Objects.equals(first.getModifyTime(), second.getModifyTime());
  }

  private static String fingerprint(SftpClient sftp, SftpClient.Handle handle, long offset, int length, ExecContext ctx, byte[] fingerprintKey)
      throws IOException, ConnectorException {
    byte[] bytes = new byte[length];
    int read = 0;
    while (read < length) {
      SshLogFiles.budget(sftp, ctx);
      int n = sftp.read(handle, offset + read, bytes, read, length - read);
      if (n <= 0) {
        throw changed();
      }
      read += n;
    }
    if (offset == 0 && bytes.length >= 4) {
      boolean gzip = (bytes[0] & 255) == 0x1f && (bytes[1] & 255) == 0x8b;
      boolean zip = bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
      boolean zstd = (bytes[0] & 255) == 0x28 && (bytes[1] & 255) == 0xb5
          && (bytes[2] & 255) == 0x2f && (bytes[3] & 255) == 0xfd;
      boolean bzip = bytes[0] == 'B' && bytes[1] == 'Z' && bytes[2] == 'h';
      boolean xz = (bytes[0] & 255) == 0xfd && bytes[1] == '7' && bytes[2] == 'z' && bytes[3] == 'X';
      if (gzip || zip || zstd || bzip || xz) {
        throw ConnectorException.badInput("Compressed log files are not supported. Select an uncompressed UTF-8 log file.");
      }
    }
    return digest(fingerprintKey, bytes);
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
    final long size;
    final String modified;
    final String head;
    final String tail;
    long proofOffset;
    int proofLength;
    String proof;

    Snapshot(long size, String modified, String head, String tail) {
      this.size = size;
      this.modified = modified;
      this.head = head;
      this.tail = tail;
    }

    static Snapshot from(JsonNode json) throws ConnectorException {
      long size = json.path("size").asLong(-1);
      Snapshot out = new Snapshot(size, json.path("modified").asString(), json.path("head").asString(),
          json.path("tail").asString());
      out.proofOffset = json.path("proofOffset").asLong(0);
      out.proofLength = json.path("proofLength").asInt(0);
      out.proof = json.path("proof").asString(null);
      if (size < 0 || out.proofOffset < 0 || out.proofLength < 0 || out.proofLength > CHUNK_BYTES
          || out.proofOffset > size - out.proofLength) {
        throw ConnectorException.badInput("The log cursor has an invalid snapshot. Start again without cursor.");
      }
      return out;
    }

    void verify(Snapshot current) throws ConnectorException {
      if (size != current.size || !modified.equals(current.modified) || !head.equals(current.head) || !tail.equals(current.tail)) {
        throw changed();
      }
    }

    void verifyProof(SftpClient sftp, SftpClient.Handle handle, ExecContext ctx, byte[] fingerprintKey) throws IOException, ConnectorException {
      if (proof != null && !proof.equals(fingerprint(sftp, handle, proofOffset, proofLength, ctx, fingerprintKey))) {
        throw changed();
      }
    }

    ObjectNode json() {
      ObjectNode out = JSON.objectNode().put("size", size).put("modified", modified).put("head", head).put("tail", tail)
          .put("proofOffset", proofOffset).put("proofLength", proofLength);
      if (proof != null) {
        out.put("proof", proof);
      }
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
          && ((!query.tail() && position == snapshot.size && fileIndex == query.files().size() - 1)
              || (query.tail() && position == 0 && fileIndex == 0))) {
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
        ObjectNode cursor = JSON.objectNode().put("v", 1).put("connection", cfg.id()).put("version", cfg.version())
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
