package ai.msrv.gaterook.core.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What the approver sees is what runs: hidden and look-alike characters are cut out and named (§8.5, §9). */
class ActionInspectorTest {

  @Test
  void aCleanActionIsOneTextSegment() {
    ActionInspector.Inspection i = ActionInspector.inspect("UPDATE orders SET status = 'cancelled' WHERE id = 48213;\n"
        + "SELECT 1;", "sql");
    assertThat(i.clean()).isTrue();
    assertThat(i.warning()).isNull();
    assertThat(i.segments()).containsExactly(new ActionInspector.Segment("text",
        "UPDATE orders SET status = 'cancelled' WHERE id = 48213;\nSELECT 1;"));
  }

  @Test
  void trojanSourceBidiControlsAreShownAsCodesEvenInsideLiterals() {
    // The approver would read: WHERE note = 'ok' -- harmless   (U+202E reverses what follows on screen).
    String sql = "DELETE FROM orders WHERE note = 'ok‮; DROP TABLE users; --⁦'";
    ActionInspector.Inspection i = ActionInspector.inspect(sql, "sql");
    assertThat(i.hiddenChars()).isEqualTo(2);
    assertThat(i.confusables()).isZero();
    assertThat(i.segments()).containsExactly(
        new ActionInspector.Segment("text", "DELETE FROM orders WHERE note = 'ok"),
        new ActionInspector.Segment("invisible", "‹U+202E›"),
        new ActionInspector.Segment("text", "; DROP TABLE users; --"),
        new ActionInspector.Segment("invisible", "‹U+2066›"),
        new ActionInspector.Segment("text", "'"));
    assertThat(i.warning()).isEqualTo("Contains 2 hidden characters. They are shown as codes and highlighted: check "
        + "the action before you approve it.");
  }

  @Test
  void aCyrillicLetterInAKeywordIsAConfusable() {
    String sql = "DELЕTE FROM orders WHERE id = 1;";
    ActionInspector.Inspection i = ActionInspector.inspect(sql, "sql");
    assertThat(i.confusables()).isEqualTo(1);
    assertThat(i.segments()).containsExactly(
        new ActionInspector.Segment("text", "DEL"),
        new ActionInspector.Segment("confusable", "Е"),
        new ActionInspector.Segment("text", "TE FROM orders WHERE id = 1;"));
    assertThat(i.warning()).isEqualTo("Contains 1 letter that looks like another. They are shown as codes and "
        + "highlighted: check the action before you approve it.");
  }

  @Test
  void lettersInsideStringLiteralsAreData() {
    // A Cyrillic name in an SQL string is ordinary data; in a double-quoted SQL identifier it is a name, so suspect.
    assertThat(ActionInspector.inspect("UPDATE users SET name = 'Ёжик' WHERE id = 7;", "sql").clean()).isTrue();
    assertThat(ActionInspector.inspect("SELECT * FROM \"оrders\";", "sql").confusables()).isEqualTo(1);
    // Shell and JSON quote with both.
    assertThat(ActionInspector.inspect("echo \"Привет\" 'мир'", "sh").clean()).isTrue();
    assertThat(ActionInspector.inspect("{\"name\":\"Ёжик\"}", "json").clean()).isTrue();
    assertThat(ActionInspector.inspect("kubectl delete pod аpi-0", "sh").confusables()).isEqualTo(1);
  }

  @Test
  void anApostropheInACommentOrAnOpenLiteralHidesNothing() {
    // The Cyrillic U+043E in the orders look-alike after a comment with an apostrophe: the comment opens no literal.
    assertThat(ActionInspector.inspect("-- it's the cleanup\nDELETE FROM оrders WHERE note = 'x';", "sql")
        .confusables()).isEqualTo(1);
    assertThat(ActionInspector.inspect("/* it's */ DELETE FROM оrders WHERE note = 'x';", "sql").confusables())
        .isEqualTo(1);
    assertThat(ActionInspector.inspect("# don't worry\nkubectl delete pod аpi-0 -l 'app=x'", "sh").confusables())
        .isEqualTo(1);
    assertThat(ActionInspector.inspect("note: fine # it's\nname: аpi\nother: 'x'", "yaml").confusables())
        .isEqualTo(1);
    // A literal nobody closes is no literal: the rest of the action is checked.
    assertThat(ActionInspector.inspect("UPDATE t SET a = 'x WHERE оwner = 1;", "sql").confusables()).isEqualTo(1);
    // Real literals stay data: SQL's doubled quote, an escaped double quote in shell, # inside a word or a literal.
    assertThat(ActionInspector.inspect("UPDATE t SET note = 'it''s Ёж' WHERE id = 1; -- ok", "sql").clean()).isTrue();
    assertThat(ActionInspector.inspect("echo \"say \\\"Ёж\\\" ok\"", "sh").clean()).isTrue();
    assertThat(ActionInspector.inspect("echo a#'Ёж' '# Ёж'", "sh").clean()).isTrue();
    // A comment is outside the literals: its letters are still checked.
    assertThat(ActionInspector.inspect("SELECT 1; -- Ёж", "sql").confusables()).isEqualTo(2);
  }

  @Test
  void aQuirkOfOneSqlDialectHidesNothingInTheOther() {
    // "sql" is PostgreSQL or MySQL: a character is in a literal only when both (and plain SQL) say so. Each case
    // hides the Cyrillic U+0430 in the name look-alike from a reader that knows only one dialect.
    // MySQL comments from '#': the apostrophe of "don't" opens nothing.
    assertThat(ActionInspector.inspect("UPDATE accounts SET plan = 'pro' # don't\nWHERE nаme = 'bob'", "sql")
        .confusables()).isEqualTo(1);
    // MySQL escapes with a backslash: 'a\'b' is one literal there.
    assertThat(ActionInspector.inspect("UPDATE t SET a = 'x\\'y' WHERE nаme = 'z'", "sql").confusables())
        .isEqualTo(1);
    // PostgreSQL's E'…' escapes too, its block comments nest and $$…$$ is a string.
    assertThat(ActionInspector.inspect("UPDATE t SET a = E'x\\'y' WHERE nаme = 'z'", "sql").confusables())
        .isEqualTo(1);
    assertThat(ActionInspector.inspect("/* outer /* inner */ it's */ DELETE FROM t WHERE nаme = 'z'", "sql")
        .confusables()).isEqualTo(1);
    assertThat(ActionInspector.inspect("SELECT $$ it's $$ FROM t WHERE nаme = 'z'", "sql").confusables())
        .isEqualTo(1);
    assertThat(ActionInspector.inspect("SELECT $tag$ it's $tag$ FROM t WHERE nаme = 'z'", "sql").confusables())
        .isEqualTo(1);
    // MySQL runs what is inside /*! … */: a quote there opens a literal that the other dialects don't see.
    assertThat(ActionInspector.inspect("SELECT 1 /*! , 'a */ b' */ FROM t WHERE nаme = 'z'", "sql").confusables())
        .isEqualTo(1);
    // MySQL's '--' needs a space after it, PostgreSQL's doesn't: 1--1 is a comment for one only.
    assertThat(ActionInspector.inspect("SELECT 1--1 'x\nFROM t WHERE nаme = 'z'", "sql").confusables())
        .isEqualTo(1);
    // A shell's backslash makes the next quote plain, bash's $'…' escapes.
    assertThat(ActionInspector.inspect("echo \\' ; rm -rf /tmp/nаme '", "sh").confusables()).isEqualTo(1);
    assertThat(ActionInspector.inspect("echo $'a\\'b' nаme 'c'", "sh").confusables()).isEqualTo(1);
    // A substitution inside "…" is code with quotes of its own: nothing after it is trusted to be a literal.
    assertThat(ActionInspector.inspect("echo \"$(rm -rf /tmp/nаme)\"", "sh").confusables()).isEqualTo(1);
    assertThat(ActionInspector.inspect("echo \"$(echo \"x\" nаme)\" 'Ёж'", "sh").confusables()).isEqualTo(3);
    assertThat(ActionInspector.inspect("echo \"`id` nаme\"", "sh").confusables()).isEqualTo(1);
    assertThat(ActionInspector.inspect("echo \"Ёж ${HOME}\"", "sh").clean()).isTrue();
    // What every dialect reads as a string stays data.
    assertThat(ActionInspector.inspect("UPDATE users SET name = 'Ёжик' WHERE note = E'ok' AND x = $$Ёж$$;", "sql")
        .confusables()).isEqualTo(2);
    assertThat(ActionInspector.inspect("UPDATE users SET name = 'Ёжик', note = 'it''s' # Ёж\n;", "sql")
        .confusables()).isEqualTo(2);
    assertThat(ActionInspector.inspect("echo $'Ёж' \"Ёж\" 'Ёж'", "sh").clean()).isTrue();
    // A parameter or a name with $ in it is no dollar quote.
    assertThat(ActionInspector.inspect("SELECT $1, a$b$ FROM t WHERE nаme = 'z'", "sql").confusables()).isEqualTo(1);
  }

  @Test
  void sqlServerIdentifiersCannotHideConfusablesBehindApostrophes() {
    // Bracket contents, including escaped closing brackets and apparent comments, are names rather than strings.
    for (String name : List.of("[n'аme']", "[n]]'аme']", "[n/*'аme'*/]", "[n--'аme']", "\"n'аme'\"")) {
      assertThat(ActionInspector.inspect("UPDATE " + name + " SET value = 1", "sql").confusables())
          .as(name).isEqualTo(1);
    }
    assertThat(ActionInspector.inspect("UPDATE [it's] SET nаme = 'bob'", "sql").confusables()).isEqualTo(1);
    assertThat(ActionInspector.inspect("UPDATE [orders] SET name = N'Ёжик'", "sql").clean()).isTrue();
    assertThat(ActionInspector.inspect("SELECT '[n''аme'']'", "sql").clean()).isTrue();
    assertThat(ActionInspector.inspect("SELECT [n'аme'", "sql").confusables()).isEqualTo(1);
  }

  @Test
  void tabsZeroWidthAndTrailingSpacesAreHidden() {
    ActionInspector.Inspection i = ActionInspector.inspect("rm\t-rf /tmp/x​  \nls   ", "sh");
    assertThat(i.segments()).extracting(ActionInspector.Segment::v).containsExactly("rm", "‹U+0009›", "-rf /tmp/x",
        "‹U+200B›", "‹U+0020›", "‹U+0020›", "\nls", "‹U+0020›", "‹U+0020›", "‹U+0020›");
    assertThat(i.hiddenChars()).isEqualTo(7);
    // Spaces inside a line are just spaces; line breaks (LF and CRLF) are kept.
    assertThat(ActionInspector.inspect("a  b\r\nc", "sh").clean()).isTrue();
    // A lone CR, a BOM, NBSP and a soft hyphen are not.
    assertThat(ActionInspector.inspect("a\rb﻿c d­e", "text").hiddenChars()).isEqualTo(4);
  }

  @Test
  void bothKindsAreCountedTogether() {
    ActionInspector.Inspection i = ActionInspector.inspect("DRОPTABLE‍ x", "sql");
    assertThat(i.hiddenChars()).isEqualTo(1);
    assertThat(i.confusables()).isEqualTo(1);
    assertThat(i.warning()).startsWith("Contains 1 hidden character and 1 letter that looks like another.");
    assertThat(ActionInspector.inspect(null, "sql").segments()).isEqualTo(List.of());
  }
}
