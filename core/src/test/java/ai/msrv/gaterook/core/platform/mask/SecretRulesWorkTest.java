package ai.msrv.gaterook.core.platform.mask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the secret rules cost on repetitive text, counted in characters the regular-expression engine reads (not
 * milliseconds): linear in the length of the text, bounded by the budget of the value, and never a way past a rule.
 */
class SecretRulesWorkTest {

  private static final String MASK = "‹masked›";

  private static String repeat(String unit, int chars) {
    StringBuilder out = new StringBuilder(chars + unit.length());
    while (out.length() < chars) {
      out.append(unit);
    }
    return out.toString();
  }

  private static long used(String text) {
    Work work = Work.counting(text);
    SecretRules.apply(text, MASK, new MaskCounter(), work);
    return work.used();
  }

  /** A keyword with a separator and a value that never ends, repeated: each value runs into the next keyword. */
  static Stream<Arguments> chains() {
    return Stream.of(
        Arguments.of("kv-password", "password=abcdefg", 4200),
        Arguments.of("kv-password, name with a suffix", "x_secret:abcdefg", 4200),
        Arguments.of("bearer-token", "token=abcdefghijklmnopqrstuvwxyz-", 8192),
        Arguments.of("pem-private-key without an end", "-----BEGIN PRIVATE KEY-----", 16384));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("chains")
  void workGrowsLinearlyWithTheLengthOfARepetitiveText(String rule, String unit, int window) {
    long once = used(repeat(unit, 4 * window));
    long twice = used(repeat(unit, 8 * window));
    // Twice the text costs about twice the work; a rule that rescans its merged window costs four times.
    assertThat((double) twice / once).as("work for twice the text").isLessThan(2.6);
  }

  @Test
  void aSpentBudgetMasksWhatWasNotCheckedYet() {
    String chain = repeat("x_secret:abcdefg", 1 << 20);
    Work work = new Work(chain, 200_000);
    MaskCounter counter = new MaskCounter();

    String out = SecretRules.apply(chain, MASK, counter, work);

    assertThat(work.spent()).isTrue();
    assertThat(work.used()).isLessThanOrEqualTo(200_000);
    // The first keyword stays readable; from its value on everything is covered, as when the rule ran to the end.
    assertThat(out).isEqualTo("x_secret:" + MASK);
    assertThat(counter.byRule()).containsKey("work-limit");
  }

  @Test
  void rulesAfterASpentBudgetMaskTheWindowsOfTheirKeywords() {
    // bearer-token spends the budget; url-credentials and kv-password run after it and must not skip their keywords.
    // The DSN comes before the chain, outside every window of bearer-token: only those two can cover its password.
    String text = "dsn postgres://app:s3cr3t-pw@db.dev.internal/app "
        + repeat("token=abcdefghijklmnopqrstuvwxyz-", 1 << 20);
    Work work = new Work(text, 100_000);
    MaskCounter counter = new MaskCounter();

    String out = SecretRules.apply(text, MASK, counter, work);

    assertThat(work.spent()).isTrue();
    assertThat(out).doesNotContain("s3cr3t-pw").doesNotContain("abcdefghijklmnopqrstuvwxyz");
    assertThat(counter.byRule()).containsKey("work-limit");
  }

  /** Texts that only the last rules (named by their id) inspect: no window of another rule covers their secret. */
  static Stream<Arguments> textsOfTheLastRules() {
    return Stream.of(
        Arguments.of("sql-password", "GRANT ALL ON *.* TO app IDENTIFIED BY 'sup3r-s3cret-pw'", "sup3r-s3cret-pw"),
        Arguments.of("cli-password", "redis-cli -h cache -a hunter2-redis-pw ping", "hunter2-redis-pw"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("textsOfTheLastRules")
  void noBudgetLetsASecretThrough(String rule, String text, String secret) {
    long needed = used(text);
    // From nothing to exactly enough, so that the budget runs out before the match attempt of the keyword, inside it
    // and just before its end: the secret is never passed unchecked. These secrets are short; how far the withheld
    // window reaches is pinned by noBudgetLetsALongSecretThrough.
    for (long budget = 0; budget <= needed; budget++) {
      String out = SecretRules.apply(text, MASK, new MaskCounter(), new Work(text, budget));
      assertThat(out).as("budget %d of %d", budget, needed).doesNotContain(secret);
    }
  }

  private static int reach(String ruleId) {
    return SecretRules.ALL.stream().filter(rule -> rule.id().equals(ruleId)).findFirst().orElseThrow().after();
  }

  /** A secret of exactly {@code chars} characters that ends in a 'Z', which nothing else in these texts contains. */
  private static String secret(String unit, int chars) {
    return repeat(unit, chars - 1).substring(0, chars - 1) + "Z";
  }

  /**
   * Texts with one keyword and a secret that runs to the end of the keyword's window (a URL password: to the longest
   * the rule accepts, 256 characters), and the start of that secret. The rule named by the case is the only one with a
   * keyword in its text, so nothing else covers the secret, and the marker at its end shows a window that is even one
   * character short.
   */
  static Stream<Arguments> longSecrets() {
    String bearer = "Bearer ";
    String client = "secret=";
    // The token is as long as the window of its first 'eyJ'; the payload's own 'eyJ' reaches 37 characters further.
    String jwtHead = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.";
    // A user name and a password of the longest size the rule accepts: 3 + 256 + 1 + 256 + 1 characters from '://'.
    String user = repeat("Us1", 256).substring(0, 256);
    return Stream.of(
        Arguments.of("bearer-token",
            "Authorization: " + bearer + secret("Ab3", reach("bearer-token") - bearer.length()) + " done",
            "Ab3Ab3Ab3Ab3"),
        Arguments.of("kv-password",
            "login failed client_" + client + secret("Xy7", reach("kv-password") - client.length()) + " user=bob",
            "Xy7Xy7Xy7Xy7"),
        Arguments.of("url-credentials",
            "dsn postgres://" + user + ":" + secret("Qw9", 256) + "@db.dev.internal/app", "Qw9Qw9Qw9Qw9"),
        Arguments.of("jwt",
            "id " + jwtHead + secret("Lm5", reach("jwt") - jwtHead.length()) + " ok", "Lm5Lm5Lm5Lm5"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("longSecrets")
  void noBudgetLetsALongSecretThrough(String rule, String text, String probe) {
    assertThat(text.indexOf('Z')).as("the end marker is unique").isNotNegative().isEqualTo(text.lastIndexOf('Z'));
    String lower = SecretRules.asciiLower(text);
    assertThat(SecretRules.ALL.stream().filter(r -> r.literals().stream().anyMatch(lower::contains))
        .map(TextRule::id)).as("the rules with a keyword in the text").containsExactly(rule);
    long needed = used(text);
    // From nothing to exactly enough in about 300 steps, and the last two budgets one by one: the budget runs out
    // before the first attempt, inside the secret and at its very end. A spent budget withholds the keyword's whole
    // window, so none of the secret is left whatever the budget.
    long step = Math.max(1, needed / 300);
    for (long budget = 0; budget < needed; budget += step) {
      assertNothingOfTheSecretIsLeft(text, budget, needed, probe);
    }
    assertNothingOfTheSecretIsLeft(text, needed - 1, needed, probe);
    assertNothingOfTheSecretIsLeft(text, needed, needed, probe);
  }

  private static void assertNothingOfTheSecretIsLeft(String text, long budget, long needed, String probe) {
    String out = SecretRules.apply(text, MASK, new MaskCounter(), new Work(text, budget));
    assertThat(out).as("budget %d of %d", budget, needed).doesNotContain(probe).doesNotContain("Z");
  }

  @Test
  void aSecretBeforeThePathologicalPartIsMaskedAsUsual() {
    String text = "key AKIAIOSFODNN7EXAMPLE and " + repeat("password=abcdefg", 1 << 18);
    MaskCounter counter = new MaskCounter();

    String out = SecretRules.apply(text, MASK, counter, new Work(text, 100_000));

    assertThat(out).startsWith("key " + MASK + " and ").doesNotContain("AKIA").doesNotContain("abcdefg");
    assertThat(counter.byRule()).containsKeys("aws-access-key-id", "work-limit");
  }

  @Test
  void ordinaryTextFullOfKeywordsStaysFarInsideItsBudget() {
    String dense = repeat("compass bypass token_count password_changed_at secret_name \"token\":\"abc123\" "
        + "PGPASSWORD=hunter2-prod-db user@host:22\n", 1 << 20);
    Work work = Work.forValue(dense);

    String out = SecretRules.apply(dense, MASK, new MaskCounter(), work);

    long budget = Work.BASE + Work.PER_CHAR * dense.length();
    assertThat(work.spent()).isFalse();
    // About a fifth of it; the chains above need more than twice the whole.
    assertThat(work.used()).as("units used of " + budget).isLessThan(budget / 2);
    assertThat(out).doesNotContain("hunter2-prod-db").contains("compass bypass token_count");
  }

  @Test
  void aShortTextIsNeverLimited() {
    String text = "password=abcdefg ".repeat(2_000);
    Work work = Work.forValue(text);

    String out = SecretRules.apply(text, MASK, new MaskCounter(), work);

    assertThat(work.spent()).isFalse();
    assertThat(out).isEqualTo(("password=" + MASK + " ").repeat(2_000));
  }
}
