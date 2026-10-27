package ai.msrv.gaterook.connectors.ssh;

/**
 * POSIX single quotes (report 06 §7.5): inside {@code '…'} the shell interprets nothing, so the only character to
 * take care of is the quote itself, which closes the quotes, adds an escaped quote and opens them again. The result
 * is one word for every POSIX shell (sh, dash, bash, zsh, ksh), whatever the value holds: {@code $(…)}, backticks,
 * {@code ;}, globs, newlines, UTF-8.
 *
 * <p>Quoting protects from shell injection, not from option injection ({@code '--help'} still reaches the program as
 * an option): {@link RunbookCompiler} refuses a leading dash unless the runbook allows it.
 */
final class ShellQuote {

  private ShellQuote() {}

  static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('\'');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\'') {
        out.append("'\"'\"'");
      } else {
        out.append(c);
      }
    }
    return out.append('\'').toString();
  }
}
