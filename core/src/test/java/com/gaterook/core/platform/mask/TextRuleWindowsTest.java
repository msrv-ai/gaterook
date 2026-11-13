package com.gaterook.core.platform.mask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Where a rule looks: a match is read no further than its own keyword's window, credentials close to each other all
 * get found, and a keyword inside an earlier match keeps its own secret.
 */
class TextRuleWindowsTest {

  private static final String MASK = "‹masked›";

  private static String masked(String text) {
    return SecretRules.apply(text, MASK, new MaskCounter());
  }

  @Test
  void credentialsCloserThanTheirWindowsAreAllFound() {
    String text = "password=first-pw-1 password=second-pw-2 PGPASSWORD=third-pw-3 "
        + "token=abcdefghijklmnopqrstuvwxyz012345 postgres://app:fourth-pw-4@db/app";

    MaskCounter counter = new MaskCounter();
    String out = SecretRules.apply(text, MASK, counter);

    assertThat(out).isEqualTo("password=" + MASK + " password=" + MASK + " PGPASSWORD=" + MASK + " token=" + MASK
        + " postgres://app:" + MASK + "@db/app");
    assertThat(counter.byRule()).containsEntry("kv-password", 3).containsEntry("bearer-token", 1)
        .containsEntry("url-credentials", 1);
  }

  @Test
  void aKeywordInsideAnEarlierMatchKeepsItsOwnSecret() {
    // The first mysql reaches across the lines to the -p of the third command, which covers the --password of the
    // second without covering its secret: that keyword is tried again from where the earlier match began.
    String script = "mysql -h db -u app app_db\nmongosh --username app --password pw-mongo-1\n"
        + "mysql -h db2 -papp-pw-2\n";

    String out = masked(script.repeat(50));

    assertThat(out).isEqualTo(("mysql -h db -u app app_db\nmongosh --username app --password " + MASK
        + "\nmysql -h db2 -p" + MASK + "\n").repeat(50));
  }

  @Test
  void aMatchEndsWithinTheWindowOfItsKeyword() {
    // 'after' is how far past the keyword a rule reads: bearer-token reads 8192 characters from its keyword.
    String fits = "bearer " + "A".repeat(8192 - 7);
    assertThat(masked(fits)).isEqualTo("bearer " + MASK);
    assertThat(masked(fits + "A")).isEqualTo("bearer " + MASK + "A");
  }

  @Test
  void aMatchStartsWithinTheReachBeforeItsKeyword() {
    // The password of a long variable name starts 'before' characters ahead of 'pass'.
    String name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_PASSWORD";
    assertThat(masked("x " + name + "=hunter2-prod-db y")).isEqualTo("x " + name + "=" + MASK + " y");
  }
}
