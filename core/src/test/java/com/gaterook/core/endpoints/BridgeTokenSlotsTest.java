package com.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

class BridgeTokenSlotsTest {

  private static final int MAX = ClientGrants.MAX_LIVE_TOKENS;
  private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

  /** {@code n} live tokens, the i-th issued i seconds after T0 to process {@code 100 + i}. */
  private static List<BridgeTokenSlots.Live> tokens(int n) {
    List<BridgeTokenSlots.Live> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(new BridgeTokenSlots.Live("tok_" + i, T0.plusSeconds(i), 100L + i));
    }
    return out;
  }

  private static LongPredicate runningExcept(Long... ended) {
    Set<Long> gone = Set.of(ended);
    return pid -> !gone.contains(pid);
  }

  @Test
  void aGrantServesAsManyRunningProgramsAsItsBoundWithoutTakingAnyonesToken() {
    // Five bridge processes of one grant used to make the fifth take the oldest one's token, and so on in turn.
    for (int n = 0; n < MAX; n++) {
      assertThat(BridgeTokenSlots.toRetire(tokens(n), MAX, 100L + n, runningExcept())).as("%d tokens", n)
          .hasValue(List.of());
    }
  }

  @Test
  void beyondTheBoundAProgramIsRefusedRatherThanTakingTheTokenOfAnotherRunningOne() {
    assertThat(BridgeTokenSlots.toRetire(tokens(MAX), MAX, 999L, runningExcept())).isEmpty();
    assertThat(BridgeTokenSlots.toRetire(tokens(MAX), MAX, null, runningExcept())).isEmpty();
  }

  @Test
  void aTokenNoRunningProgramCanUseGivesWayFirstOldestFirst() {
    List<BridgeTokenSlots.Live> live = new ArrayList<>(tokens(MAX));
    // Issued by an earlier run of the core: its bridges must ask the new run again.
    live.set(9, new BridgeTokenSlots.Live("tok_9", T0.plusSeconds(9), null));
    assertThat(BridgeTokenSlots.toRetire(live, MAX, 999L, runningExcept(107L))).hasValue(List.of("tok_7"));
    assertThat(BridgeTokenSlots.toRetire(live, MAX, 999L, runningExcept())).hasValue(List.of("tok_9"));
  }

  @Test
  void theAskingProgramReplacesItsOwnTokenWhenNoneIsUnused() {
    assertThat(BridgeTokenSlots.toRetire(tokens(MAX), MAX, 104L, runningExcept())).hasValue(List.of("tok_4"));
    // An unused token goes before the asking program's own.
    assertThat(BridgeTokenSlots.toRetire(tokens(MAX), MAX, 104L, runningExcept(112L))).hasValue(List.of("tok_12"));
  }

  @Test
  void tooManyLiveTokensAreBroughtBackUnderTheBound() {
    List<BridgeTokenSlots.Live> live = new ArrayList<>(tokens(MAX));
    live.add(new BridgeTokenSlots.Live("tok_x", T0.minusSeconds(5), 7L));
    live.add(new BridgeTokenSlots.Live("tok_y", T0.minusSeconds(4), 7L));
    // Three must go for one more to fit: the unused ones first, then the asking program's own.
    assertThat(BridgeTokenSlots.toRetire(live, MAX, 7L, runningExcept(100L))).hasValue(
        List.of("tok_0", "tok_x", "tok_y"));
    assertThat(BridgeTokenSlots.toRetire(live, MAX, 7L, runningExcept())).as("two of three").isEmpty();
    assertThat(BridgeTokenSlots.toRetire(live, MAX, 8L, runningExcept(7L, 100L))).hasValue(
        List.of("tok_x", "tok_y", "tok_0"));
    assertThat(BridgeTokenSlots.toRetire(live, MAX, 8L, runningExcept(7L))).as("one short").isEmpty();
  }

  @Test
  void thisProcessRunsAndAnEndedOneDoesNot() throws Exception {
    assertThat(BridgeTokenSlots.running(ProcessHandle.current().pid())).isTrue();
    Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version")
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    long pid = child.pid();
    child.waitFor();
    assertThat(BridgeTokenSlots.running(pid)).isFalse();
  }
}
