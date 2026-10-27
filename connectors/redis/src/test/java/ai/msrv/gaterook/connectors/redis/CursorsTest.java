package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Cursors an agent carries between calls: sealed by the core's codec, bound to their connection, key or pattern, and
 * readable in {@code classify} only to deny early — never trusted there.
 */
class CursorsTest {

  private static final byte[] KEY = "cache:list".getBytes(StandardCharsets.UTF_8);

  @Test
  void aGetCursorOpensForItsConnectionAndKeyOnly() throws Exception {
    TestCtx ctx = TestCtx.of();
    String c = Cursors.sealGet(ctx, "c-1", KEY, new Cursors.GetState("list", "40"));
    assertThat(c).startsWith("rgc.");
    assertThat(Cursors.openGet(ctx, c, "c-1", KEY)).isEqualTo(new Cursors.GetState("list", "40"));
    assertThatThrownBy(() -> Cursors.openGet(ctx, c, "c-2", KEY)).hasMessage(Cursors.OTHER_KEY);
    assertThatThrownBy(() -> Cursors.openGet(ctx, c, "c-1", "cache:other".getBytes(StandardCharsets.UTF_8)))
        .hasMessage(Cursors.OTHER_KEY);
  }

  @Test
  void aScanCursorIsBoundToItsPatternAndType() throws Exception {
    TestCtx ctx = TestCtx.of();
    String c = Cursors.sealScan(ctx, "c-1", "cache:*", "hash", new Cursors.ScanState("1234"));
    assertThat(c).startsWith("rsc.");
    assertThat(Cursors.openScan(ctx, c, "c-1", "cache:*", "hash").redisCursor()).isEqualTo("1234");
    assertThatThrownBy(() -> Cursors.openScan(ctx, c, "c-1", "cache:*", null)).hasMessage(Cursors.OTHER_PATTERN);
    assertThatThrownBy(() -> Cursors.openScan(ctx, c, "c-1", "*", "hash")).hasMessage(Cursors.OTHER_PATTERN);
    assertThatThrownBy(() -> Cursors.openScan(ctx, c, "c-9", "cache:*", "hash")).hasMessage(Cursors.OTHER_PATTERN);
  }

  @Test
  void anEditedCursorFailsItsMac() {
    TestCtx ctx = TestCtx.of();
    String c = Cursors.sealGet(ctx, "c-1", KEY, new Cursors.GetState("list", "40"));
    String body = c.substring(4, c.indexOf('.', 4));
    String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).replace("\"40\"", "\"0\"");
    String forged = "rgc." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(
        StandardCharsets.UTF_8)) + c.substring(c.indexOf('.', 4));
    // classify can't tell (it has no codec) — the payload still names the right key …
    assertThat(Cursors.peekGet(forged, "c-1", KEY)).isEmpty();
    // … execute can.
    assertThatThrownBy(() -> Cursors.openGet(ctx, forged, "c-1", KEY))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.BAD_INPUT))
        .hasMessage("The cursor is invalid or has expired (cursors last 24 h). Call redis_get again without cursor.");
  }

  @Test
  void aCursorOfTheOtherToolIsNotACursorHere() {
    TestCtx ctx = TestCtx.of();
    String scan = Cursors.sealScan(ctx, "c-1", "*", null, new Cursors.ScanState("0"));
    assertThat(Cursors.peekGet(scan, "c-1", KEY)).contains("This isn't a cursor of redis_get.");
    assertThatThrownBy(() -> Cursors.openGet(ctx, scan, "c-1", KEY))
        .hasMessage("This isn't a cursor of redis_get: pass nextCursor exactly as you got it, or call again without "
            + "cursor.");
    // The same body under the other prefix: the purpose inside the seal differs.
    String renamed = "rgc." + scan.substring(4);
    assertThatThrownBy(() -> Cursors.openGet(ctx, renamed, "c-1", KEY))
        .hasMessageStartingWith("The cursor is invalid or has expired");
  }

  @Test
  void peekDeniesAnotherConnectionWithoutTheCodec() {
    String c = Cursors.sealGet(TestCtx.of(), "c-1", KEY, new Cursors.GetState("hash", "0"));
    assertThat(Cursors.peekGet(c, "c-2", KEY)).contains(Cursors.OTHER_KEY);
    assertThat(Cursors.peekGet("rgc.garbage", "c-1", KEY)).contains("This isn't a cursor of redis_get.");
    assertThat(Cursors.peekGet(null, "c-1", KEY)).isPresent();
  }

  @Test
  void aCursorOfTheLongestPatternStillFitsTheToolsLimit() throws Exception {
    // 256 characters of three-byte UTF-8, the longest pattern redis_scan takes, and the largest Redis cursor.
    String pattern = "€".repeat(RedisSettings.MAX_PATTERN);
    TestCtx ctx = TestCtx.of();
    String c = Cursors.sealScan(ctx, "c-0123456789abcdef0123456789abcdef", pattern, "stream",
        new Cursors.ScanState("18446744073709551615"));
    assertThat(c.length()).isLessThanOrEqualTo(512);
    assertThat(Cursors.openScan(ctx, c, "c-0123456789abcdef0123456789abcdef", pattern, "stream").redisCursor())
        .isEqualTo("18446744073709551615");
    assertThat(Cursors.peekScan(c, "c-0123456789abcdef0123456789abcdef", pattern, "stream")).isEmpty();
  }

  @Test
  void theKeyIsOnlyAHashInsideTheCursor() {
    String c = Cursors.sealGet(TestCtx.of(), "c-1", "session:secret-name".getBytes(StandardCharsets.UTF_8),
        new Cursors.GetState("string", "0"));
    String body = new String(Base64.getUrlDecoder().decode(c.substring(4, c.indexOf('.', 4))),
        StandardCharsets.UTF_8);
    assertThat(body).doesNotContain("secret-name").contains(Cursors.keyHash("session:secret-name"));
    assertThat(Cursors.keyHash("a")).hasSize(16).isNotEqualTo(Cursors.keyHash("b"));
  }
}
