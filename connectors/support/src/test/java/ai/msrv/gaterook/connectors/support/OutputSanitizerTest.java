package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OutputSanitizerTest {

  @Test
  void keepsPlainTextNewlinesAndTabs() {
    String text = "NAME\tIMAGE\napi\tghcr.io/acme/api:1.4\nПривет, 世界 ✓";
    assertThat(OutputSanitizer.sanitize(text)).isSameAs(text);
    assertThat(OutputSanitizer.isClean(text)).isTrue();
  }

  @Test
  void escapesAnsiSequencesAndControlCharacters() {
    assertThat(OutputSanitizer.sanitize("\u001b[31mERROR\u001b[0m done\u0007"))
        .isEqualTo("\\e[31mERROR\\e[0m done\\x07");
    assertThat(OutputSanitizer.sanitize("\u001b]0;title\u0007x")).isEqualTo("\\e]0;title\\x07x");
    assertThat(OutputSanitizer.sanitize("a\u0000b\u007fc\u009bd")).isEqualTo("a\\x00b\\x7fc\\u009Bd");
  }

  @Test
  void normalizesLineEndingsAndShowsBareCarriageReturns() {
    assertThat(OutputSanitizer.sanitize("a\r\nb\r\n")).isEqualTo("a\nb\n");
    assertThat(OutputSanitizer.sanitize("10%\r50%\r100%")).isEqualTo("10%\\r50%\\r100%");
  }

  @Test
  void makesBidiAndZeroWidthCharactersVisible() {
    assertThat(OutputSanitizer.sanitize("rm -rf /‮;tpircs")).isEqualTo("rm -rf /\\u202E;tpircs");
    assertThat(OutputSanitizer.sanitize("ad​min﻿")).isEqualTo("ad\\u200Bmin\\uFEFF");
    assertThat(OutputSanitizer.isClean("a⁦b")).isFalse();
  }

  @Test
  void decodesInvalidUtf8WithReplacement() {
    byte[] bytes = {'o', 'k', (byte) 0xC3, (byte) 0x28, '\n', 0x1b};
    assertThat(OutputSanitizer.decode(bytes)).isEqualTo("ok�(\n\\e");
    assertThat(OutputSanitizer.decode("héllo".getBytes(StandardCharsets.UTF_8), 0, 2)).isEqualTo("h�");
  }
}
