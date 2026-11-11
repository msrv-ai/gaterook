package ai.msrv.gaterook.connectors.ssh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Splits a runbook's command into literal text and placeholders the way a POSIX shell would read it (report 06 §4).
 * A placeholder only counts where the shell reads a plain word: its value is put in single quotes when the runbook
 * runs, and inside {@code '…'} that would close the author's quotes ({@code grep '{x}'} would become
 * {@code grep ''v''}, the value unquoted), inside {@code "…"} {@code $(…)} would still run, inside backticks the
 * value's own backtick would end the substitution early, inside {@code ${…}} the shell strips the quotes, and in a
 * comment it means nothing. Heredocs need several lines, which a runbook doesn't have.
 *
 * <p>The shell's quoting nests, so the lexer keeps a stack of what it is inside of: {@code "$(echo "{x}")"} puts
 * {@code {x}} in double quotes again, inside the command substitution, and is refused like {@code "{x}"}. A command
 * substitution {@code $(…)} reads like the command line itself, so {@code $(cat {x})} is fine — but not within any
 * kind of quoting. Arithmetic ({@code $((…))}, {@code ((…))}, {@code $[…]}) and {@code [[…]]} evaluate a quoted
 * value as an expression — bash runs {@code $(…)} inside an array subscript such as {@code a[$(id)]} — and
 * commands such as {@code eval}, {@code sh} or {@code ssh} read their arguments again: a placeholder there is
 * refused too. Dispatch wrappers are also refused because their option grammars can select another interpreter.
 * Command names must be literal; quoting and escapes in a literal name do not hide an evaluator.
 *
 * <p>Dollar-single quoting and quotes inside double-quoted parameter expansions have different boundaries across
 * supported POSIX shells. A parameterized command using either form is refused rather than parsed as Bash only.
 *
 * <p>What the lexer can't tell for sure is refused: the cost is a runbook written another way, never a value that
 * leaves its quotes.
 */
final class CommandLexer {

  /** A piece of the command: literal text, or the placeholder of one parameter. */
  sealed interface Segment permits Text, Placeholder {}

  record Text(String text) implements Segment {}

  record Placeholder(String name) implements Segment {}

  record Result(List<Segment> segments, List<String> errors) {
    boolean ok() {
      return errors.isEmpty();
    }
  }

  /** What the shell is inside of while it reads the command. */
  private enum Kind {
    TOP, SUBSHELL, SINGLE, DOUBLE, ANSI_C, BACKTICK, PARAM, ARITH, TEST, COMMENT;

    /** Where the shell reads plain words: the command line and a {@code $(…)} in it. */
    boolean commands() {
      return this == TOP || this == SUBSHELL;
    }
  }

  /** Words before the command's name that leave the next word in command position. */
  private static final Set<String> PREFIXES = Set.of("!", "if", "then", "else", "elif", "do", "while", "until",
      "command", "builtin");

  /** Commands that reinterpret arguments, or dispatch a command whose option grammar we do not parse. */
  private static final Set<String> EVALUATORS = Set.of("eval", "let", "sh", "bash", "dash", "ash", "ksh", "ksh93",
      "mksh", "zsh", "csh", "tcsh", "fish", "ssh", "su", "watch", "source", ".", "env", "sudo", "doas", "time",
      "exec", "timeout", "nohup", "nice", "stdbuf", "xargs", "parallel", "busybox");

  /** One level of quoting or substitution, and what it needs to find its own end. */
  private static final class Frame {
    final Kind kind;
    final String opener;
    int depth;                   // SUBSHELL, ARITH: nested ( … ) or [ … ]
    boolean commandWord = true;  // TOP, SUBSHELL: the next word names a command
    boolean inWord;              // TOP, SUBSHELL: inside a word
    String evaluator;            // TOP, SUBSHELL: a command whose arguments cannot be safely parameterized

    Frame(Kind kind, String opener) {
      this.kind = kind;
      this.opener = opener;
    }
  }

  private CommandLexer() {}

  static Result lex(String command, Set<String> parameters) {
    List<Segment> segments = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    Deque<Frame> stack = new ArrayDeque<>();
    stack.push(new Frame(Kind.TOP, ""));
    int i = 0;
    int n = command.length();
    boolean ansiCQuoting = false;
    boolean parameterQuotes = false;
    while (i < n) {
      Frame f = stack.peek();
      char c = command.charAt(i);
      ansiCQuoting |= f.kind == Kind.ANSI_C;
      parameterQuotes |= f.kind == Kind.PARAM && within(stack, Kind.DOUBLE) && (c == '\'' || c == '"');
      int placeholderEnd = placeholderEnd(command, i);
      if (placeholderEnd > 0) {
        String name = command.substring(i + 1, placeholderEnd - 1);
        boolean declared = parameters.contains(name);
        String where = quoting(stack);
        if (where == null) {
          String evaluator = evaluator(stack);
          if (!declared) {
            errors.add("{" + name + "} is not a parameter of this runbook");
            text.append(command, i, placeholderEnd);
          } else if (f.commandWord) {
            errors.add("{" + name + "} cannot select a command or appear before its literal name");
            text.append(command, i, placeholderEnd);
          } else if (evaluator != null) {
            errors.add("{" + name + "} is an argument of " + evaluator
                + ", which can execute or reinterpret its arguments: use a fixed command directly");
            text.append(command, i, placeholderEnd);
          } else {
            flush(text, segments);
            segments.add(new Placeholder(name));
          }
          f.commandWord = false;
          f.inWord = true;
        } else {
          if (declared) {
            errors.add("{" + name + "} is " + where + (where.startsWith("inside arithmetic")
                || where.startsWith("inside [[") ? ": the shell evaluates it there, quoted or not"
                : ": put it outside quotes, the gateway quotes its value"));
          }
          text.append(command, i, placeholderEnd);
        }
        i = placeholderEnd;
        continue;
      }
      int consumed = switch (f.kind) {
        case TOP, SUBSHELL -> commands(command, i, stack, f, errors);
        case SINGLE -> {
          if (c == '\'') {
            stack.pop();
          }
          yield 1;
        }
        case ANSI_C -> c == '\\' ? 2 : popIf(c == '\'', stack, 1);
        case DOUBLE -> c == '\\' ? 2 : c == '"' ? popIf(true, stack, 1) : substitution(command, i, stack);
        case BACKTICK -> c == '\\' ? 2 : popIf(c == '`', stack, 1);
        case PARAM -> {
          if (c == '\\') {
            yield 2;
          }
          if (c == '}') {
            yield popIf(true, stack, 1);
          }
          if (c == '\'' && !within(stack, Kind.DOUBLE)) {
            stack.push(new Frame(Kind.SINGLE, "'"));
            yield 1;
          }
          if (c == '"') {
            stack.push(new Frame(Kind.DOUBLE, "\""));
            yield 1;
          }
          yield substitution(command, i, stack);
        }
        case ARITH -> arithmetic(command, i, stack, f);
        case TEST -> {
          if (c == ']' && i + 1 < n && command.charAt(i + 1) == ']' && i > 0
              && Character.isWhitespace(command.charAt(i - 1))) {
            yield popIf(true, stack, 2);
          }
          yield quotes(command, i, stack);
        }
        case COMMENT -> {
          if (c == '\n') { // to the end of the line; placeholders in it are reported above
            stack.pop();
            separator(stack.peek());
          }
          yield 1;
        }
      };
      int to = Math.min(n, i + consumed);
      text.append(command, i, to);
      i = to;
    }
    if (stack.peek().kind == Kind.COMMENT) {
      stack.pop();
    }
    if (stack.size() > 1) {
      Frame open = stack.peek();
      errors.add(switch (open.kind) {
        case SINGLE -> "A ' is never closed";
        case DOUBLE -> "A \" is never closed";
        case ANSI_C -> "A $' is never closed";
        case BACKTICK -> "A ` is never closed";
        default -> "A " + open.opener + " is never closed";
      });
    }
    flush(text, segments);
    if (segments.stream().anyMatch(s -> s instanceof Placeholder)) {
      if (ansiCQuoting) {
        errors.add("Dollar-single quoting ($'…') has shell-dependent boundaries: use plain quotes in a parameterized "
            + "runbook");
      }
      if (parameterQuotes) {
        errors.add("Quotes inside a double-quoted parameter expansion have shell-dependent boundaries: simplify "
            + "the expansion in a parameterized runbook");
      }
    }
    return new Result(List.copyOf(segments), List.copyOf(errors));
  }

  /**
   * The command line or a {@code $(…)}: words, separators, and what opens quoting, substitution, arithmetic, a test
   * or a comment. Returns how many characters were read.
   */
  private static int commands(String s, int i, Deque<Frame> stack, Frame f, List<String> errors) {
    char c = s.charAt(i);
    int n = s.length();
    if (c == ' ' || c == '\t') {
      f.inWord = false;
      return 1;
    }
    if (c == ';' || c == '&' || c == '|' || c == '\n') {
      separator(f);
      return 1;
    }
    if (c == '(' || c == ')') {
      if (c == '(' && !f.inWord && i + 1 < n && s.charAt(i + 1) == '(') {
        stack.push(new Frame(Kind.ARITH, "(("));
        f.commandWord = false;
        return 2;
      }
      if (f.kind == Kind.SUBSHELL) {
        if (c == '(') {
          f.depth++;
        } else if (f.depth > 0) {
          f.depth--;
        } else {
          stack.pop(); // the end of $( … )
          Frame outer = stack.peek();
          outer.inWord = true;
          return 1;
        }
      }
      separator(f);
      return 1;
    }
    boolean wordStart = !f.inWord;
    boolean commandStart = f.commandWord;
    f.inWord = true;
    if (wordStart) {
      if (c == '#') {
        stack.push(new Frame(Kind.COMMENT, "#"));
        return 1;
      }
      if (c == '[' && i + 2 < n && s.charAt(i + 1) == '[' && Character.isWhitespace(s.charAt(i + 2))) {
        stack.push(new Frame(Kind.TEST, "[["));
        f.commandWord = false;
        return 2;
      }
      if (f.commandWord) {
        commandWord(s, i, f);
      }
    }
    if (c == '\\') {
      return 2;
    }
    if (c == '<' && i + 1 < n && s.charAt(i + 1) == '<') {
      if (i + 2 < n && s.charAt(i + 2) == '<') {
        return 3; // a here-string (<<<) is one line: fine
      }
      errors.add("A runbook is one line, so it can't have a heredoc (<<)");
      return 2;
    }
    if (c == '{' && wordStart && commandStart && i + 1 < n && Character.isWhitespace(s.charAt(i + 1))) {
      separator(f); // { list; }: the next word names a command
      return 1;
    }
    return quotes(s, i, stack);
  }

  /** Resolve a literal executable name, including paths and shell quote removal, before allowing arguments. */
  private static void commandWord(String s, int i, Frame f) {
    int end = i;
    while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) {
      end++;
    }
    if (end < s.length() && s.charAt(end) == '=' && end > i && !Character.isDigit(s.charAt(i))) {
      return; // NAME=value: still before the command, including after a quoted assignment value
    }
    String word = literalWord(s, i);
    if (word == null || word.isEmpty()) {
      f.evaluator = "a command whose name is not a fixed literal";
      f.commandWord = false;
      return;
    }
    String name = word.substring(word.lastIndexOf('/') + 1);
    if (EVALUATORS.contains(name)) {
      f.evaluator = name;
      f.commandWord = false;
    } else if (!PREFIXES.contains(name) && !word.startsWith("-")) {
      f.commandWord = false;
    }
  }

  /** Null means expansion or syntax we cannot resolve to one literal command name. */
  private static String literalWord(String s, int start) {
    StringBuilder word = new StringBuilder();
    char quote = 0;
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote == '\'') {
        if (c == '\'') {
          quote = 0;
        } else {
          word.append(c);
        }
      } else if (c == '\\') {
        if (++i == s.length()) {
          return null;
        }
        char next = s.charAt(i);
        if (quote == '"' && "\\$`\"\n".indexOf(next) < 0) {
          word.append('\\');
        }
        word.append(next);
      } else if (quote == '"' && c == '"') {
        quote = 0;
      } else if (c == '$' || c == '`') {
        return null;
      } else if (quote == 0 && (c == '\'' || c == '"')) {
        quote = c;
      } else if (quote == 0 && " \t;&|()\n".indexOf(c) >= 0) {
        break;
      } else if (quote == 0 && "<>{}*?[]~".indexOf(c) >= 0) {
        return null;
      } else {
        word.append(c);
      }
    }
    return quote == 0 ? word.toString() : null;
  }

  private static void separator(Frame f) {
    f.commandWord = true;
    f.evaluator = null;
    f.inWord = false;
  }

  /** Quotes and substitutions that may open wherever a word goes on. */
  private static int quotes(String s, int i, Deque<Frame> stack) {
    char c = s.charAt(i);
    if (c == '\\') {
      return 2;
    }
    if (c == '\'') {
      stack.push(new Frame(Kind.SINGLE, "'"));
      return 1;
    }
    if (c == '"') {
      stack.push(new Frame(Kind.DOUBLE, "\""));
      return 1;
    }
    if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
      stack.push(new Frame(Kind.ANSI_C, "$'"));
      return 2;
    }
    return substitution(s, i, stack);
  }

  /**
   * {@code $((}, {@code $(}, {@code ${}, {@code $[} and backticks, which open in the command line and inside double
   * quotes alike.
   */
  private static int substitution(String s, int i, Deque<Frame> stack) {
    char c = s.charAt(i);
    int n = s.length();
    if (c == '`') {
      stack.push(new Frame(Kind.BACKTICK, "`"));
      return 1;
    }
    if (c != '$' || i + 1 >= n) {
      return 1;
    }
    char next = s.charAt(i + 1);
    if (next == '(' && i + 2 < n && s.charAt(i + 2) == '(') {
      stack.push(new Frame(Kind.ARITH, "$(("));
      return 3;
    }
    if (next == '(') {
      stack.push(new Frame(Kind.SUBSHELL, "$("));
      return 2;
    }
    if (next == '{') {
      stack.push(new Frame(Kind.PARAM, "${"));
      return 2;
    }
    if (next == '[') {
      stack.push(new Frame(Kind.ARITH, "$["));
      return 2;
    }
    return 1;
  }

  /** Inside {@code $((…))}, {@code ((…))} or {@code $[…]}: nested parentheses or brackets, then the end. */
  private static int arithmetic(String s, int i, Deque<Frame> stack, Frame f) {
    char c = s.charAt(i);
    boolean brackets = f.opener.equals("$[");
    char open = brackets ? '[' : '(';
    char close = brackets ? ']' : ')';
    if (c == open) {
      f.depth++;
      return 1;
    }
    if (c == close) {
      if (f.depth > 0) {
        f.depth--;
        return 1;
      }
      if (brackets) {
        return popIf(true, stack, 1);
      }
      if (i + 1 < s.length() && s.charAt(i + 1) == ')') {
        return popIf(true, stack, 2);
      }
      return 1;
    }
    return quotes(s, i, stack);
  }

  private static int popIf(boolean end, Deque<Frame> stack, int length) {
    if (end) {
      stack.pop();
      Frame outer = stack.peek();
      if (outer.kind.commands()) {
        outer.inWord = true;
      }
    }
    return length;
  }

  private static boolean within(Deque<Frame> stack, Kind kind) {
    for (Frame f : stack) {
      if (f.kind == kind) {
        return true;
      }
    }
    return false;
  }

  /** Where a placeholder here would be, if anywhere but a plain word of the command line or a {@code $(…)}. */
  private static String quoting(Deque<Frame> stack) {
    for (Frame f : stack) { // innermost first
      String where = switch (f.kind) {
        case TOP, SUBSHELL -> null;
        case SINGLE -> "inside '…'";
        case DOUBLE -> "inside \"…\"";
        case ANSI_C -> "inside $'…'";
        case BACKTICK -> "inside backticks";
        case PARAM -> "inside ${…}";
        case ARITH -> "inside arithmetic " + f.opener + "…" + (f.opener.equals("$[") ? "]" : "))");
        case TEST -> "inside [[…]]";
        case COMMENT -> "in a comment";
      };
      if (where != null) {
        return where;
      }
    }
    return null;
  }

  /** An evaluator or unresolved dispatcher around the placeholder, including an enclosing substitution. */
  private static String evaluator(Deque<Frame> stack) {
    for (Frame f : stack) {
      if (f.evaluator != null) {
        return f.evaluator;
      }
    }
    return null;
  }

  /** The index after {@code {name}} if a placeholder starts at {@code i}, else -1. */
  static int placeholderEnd(String s, int i) {
    if (s.charAt(i) != '{' || i + 2 >= s.length() || !isNameStart(s.charAt(i + 1))) {
      return -1;
    }
    int j = i + 2;
    while (j < s.length() && j - i <= 32 && isNamePart(s.charAt(j))) {
      j++;
    }
    return j < s.length() && s.charAt(j) == '}' && j - i - 1 <= 32 ? j + 1 : -1;
  }

  private static boolean isNameStart(char c) {
    return c >= 'a' && c <= 'z';
  }

  private static boolean isNamePart(char c) {
    return isNameStart(c) || (c >= '0' && c <= '9') || c == '_';
  }

  private static void flush(StringBuilder text, List<Segment> segments) {
    if (!text.isEmpty()) {
      segments.add(new Text(text.toString()));
      text.setLength(0);
    }
  }
}
