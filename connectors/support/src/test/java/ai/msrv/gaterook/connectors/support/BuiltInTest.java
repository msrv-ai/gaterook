package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuiltInTest {

  @Test
  void versionComesFromTheBuild() {
    // A semantic version, optionally -SNAPSHOT; an unfiltered "@project.version@" would fail class init instead.
    assertThat(BuiltIn.VERSION).matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?");
  }
}
