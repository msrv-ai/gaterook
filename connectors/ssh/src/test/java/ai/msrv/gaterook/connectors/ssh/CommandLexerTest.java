package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandLexerTest {

  private static final Set<String> PARAMS = Set.of("service", "lines");

  @Test
  void placeholdersOutsideQuotesBecomeSegments() {
    CommandLexer.Result r = CommandLexer.lex("docker compose logs --tail {lines} {service}", PARAMS);

    assertThat(r.errors()).isEmpty();
    assertThat(r.segments()).containsExactly(new CommandLexer.Text("docker compose logs --tail "),
        new CommandLexer.Placeholder("lines"), new CommandLexer.Text(" "), new CommandLexer.Placeholder("service"));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "grep '{service}' /var/log/syslog|inside '…'",
      "echo \"{service}\"|inside \"…\"",
      "echo $'{service}'|inside $'…'",
      "echo `cat {service}`|inside backticks",
      "echo ${HOME:-{service}}|inside ${…}",
      "uptime # {service}|in a comment"})
  void aPlaceholderInsideQuotingIsRefused(String command, String where) {
    CommandLexer.Result r = CommandLexer.lex(command, PARAMS);

    assertThat(r.errors()).singleElement().asString().contains("{service} is " + where);
  }

  @ParameterizedTest
  @ValueSource(strings = {"awk '{print $1}' /etc/passwd", "find /tmp -name x -exec ls {} \\;", "echo {a,b}",
      "docker inspect -f {{.State.Status}} api", "echo \\{service}", "echo a#b {service}", "cat <<< {service}"})
  void shellBracesThatAreNoPlaceholdersStayText(String command) {
    CommandLexer.Result r = CommandLexer.lex(command, PARAMS);

    assertThat(r.errors()).isEmpty();
  }

  /**
   * Quoting nests: inside {@code "$(…)"} a second {@code "…"} is double quotes again, where the value's {@code $(…)}
   * would run ({@code /bin/sh} ran it: review 2026-10-26). Command substitution in the command line itself is fine.
   */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "docker logs \"$(docker ps -q -f \"name={service}\")\"|inside \"…\"",
      "echo \"$(printf '%s' \"{service}\")\"|inside \"…\"",
      "echo \"$(echo {service})\"|inside \"…\"",
      "echo \"${HOME:-$(echo {service})}\"|inside ${…}",
      "echo $(echo \"`cat {service}`\")|inside backticks"})
  void aPlaceholderInQuotesNestedInASubstitutionIsRefused(String command, String where) {
    assertThat(CommandLexer.lex(command, PARAMS).errors()).singleElement().asString()
        .contains("{service} is " + where);
  }

  /** Arithmetic and [[ … ]] evaluate a quoted value: in bash 'a[$(id)]' runs id there. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "echo $(( {lines} + 1 ))|inside arithmetic $((…))",
      "(( {lines} > 100 )) && echo many|inside arithmetic ((…))",
      "echo $[ {lines} * 2 ]|inside arithmetic $[…]",
      "[[ {lines} -gt 100 ]] && echo many|inside [[…]]",
      "echo \"$(( {lines} ))\"|inside arithmetic $((…))"})
  void aPlaceholderTheShellEvaluatesAsAnExpressionIsRefused(String command, String where) {
    assertThat(CommandLexer.lex(command, PARAMS).errors()).singleElement().asString()
        .contains("{lines} is " + where).contains("evaluates it there");
  }

  @ParameterizedTest
  @ValueSource(strings = {"eval docker logs {service}", "let n={lines}", "true && eval echo {service}",
      "eval \"$(echo x)\" {service}", "eval $(echo {service})", "if true; then eval echo {service}; fi"})
  void aPlaceholderThatEvalOrLetReadsAgainIsRefused(String command) {
    assertThat(CommandLexer.lex(command, PARAMS).errors()).singleElement().asString().contains("an argument of");
  }

  @ParameterizedTest
  @ValueSource(strings = {"docker logs $(docker ps -q -f name={service})", "echo \"$(date)\" {service}",
      "echo $((1 + 2)) {lines}", "[[ -f /etc/hosts ]] && grep -c x {service}", "test {lines} -gt 1 && echo many",
      "echo $(echo $(echo {service}))", "evaluate {service}", "echo eval {service}", "X=1 docker logs {service}",
      "(cd /srv && ls {service})", "{ ls {service}; }", "echo ${#HOME} {service}", "echo $# {service}"})
  void aPlaceholderInAPlainWordStaysAPlaceholder(String command) {
    CommandLexer.Result r = CommandLexer.lex(command, PARAMS);

    assertThat(r.errors()).isEmpty();
    assertThat(r.segments()).filteredOn(s -> s instanceof CommandLexer.Placeholder).isNotEmpty();
  }

  @Test
  void anUndeclaredPlaceholderOutsideQuotesIsAnError() {
    assertThat(CommandLexer.lex("journalctl -u {unit}", PARAMS).errors())
        .containsExactly("{unit} is not a parameter of this runbook");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "echo 'open|A ' is never closed",
      "echo \"open|A \" is never closed",
      "echo `open|A ` is never closed",
      "echo ${open|A ${ is never closed",
      "echo $(open|A $( is never closed",
      "echo $((1 + {lines}|A $(( is never closed",
      "[[ -f x|A [[ is never closed",
      "cat <<EOF|A runbook is one line, so it can't have a heredoc (<<)"})
  void unbalancedQuotingAndHeredocsAreRefused(String command, String error) {
    assertThat(CommandLexer.lex(command, PARAMS).errors()).contains(error);
  }
}
