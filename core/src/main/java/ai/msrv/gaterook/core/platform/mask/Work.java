package ai.msrv.gaterook.core.platform.mask;

/**
 * The regular-expression work one value may cost across all secret rules. The engine reads the text through this
 * view, so every character it looks at is counted, backtracking included, and each match attempt is charged a fixed
 * amount on top. When the budget is spent the next read throws {@link Spent}: the rule that was running withholds what
 * it had not checked yet ({@link TextRule#collect}) and the rules after it do the same, so a text built to make the
 * engine rescan itself costs a bounded amount of work and never leaves a keyword's surroundings unchecked.
 *
 * <p>The budget is a base for short values plus a share per character. Ordinary text, even text full of keywords,
 * uses a small fraction of it; only text made of overlapping keywords and unterminated values comes near.
 */
final class Work implements CharSequence {

  /** Units for any value, so that short texts never meet the limit. */
  static final long BASE = 1L << 14;
  /** Units per character of the value: the cost of every match attempt, in characters read, stays linear in it. */
  static final long PER_CHAR = 128;
  /** The fixed cost of starting a match attempt, in characters read. */
  static final long ATTEMPT = 16;

  /** Thrown by a read when the budget is spent. Stackless and shared: it carries no information beyond its type. */
  static final class Spent extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private Spent() {
      super("The masking work of this value is spent", null, false, false);
    }
  }

  private static final Spent SPENT = new Spent();

  private final String text;
  private final long budget;
  private long left;
  private boolean spent;

  Work(String text, long budget) {
    this.text = text;
    this.budget = budget;
    this.left = budget;
  }

  /** The budget of an ordinary value: {@link #BASE} plus {@link #PER_CHAR} per character. */
  static Work forValue(String text) {
    return new Work(text, BASE + PER_CHAR * text.length());
  }

  /** Counts without limit: tests measure how the work grows. */
  static Work counting(String text) {
    return new Work(text, Long.MAX_VALUE);
  }

  String text() {
    return text;
  }

  /** Units used so far. */
  long used() {
    return budget - left;
  }

  /** Whether a read or a charge has failed for lack of budget. */
  boolean spent() {
    return spent;
  }

  /** Charges {@code units} for work that is not a read, e.g. the start of a match attempt. */
  void charge(long units) {
    if (left < units) {
      left = 0;
      spent = true;
      throw SPENT;
    }
    left -= units;
  }

  @Override
  public char charAt(int index) {
    if (left <= 0) {
      spent = true;
      throw SPENT;
    }
    left--;
    return text.charAt(index);
  }

  @Override
  public int length() {
    return text.length();
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return text.subSequence(start, end);
  }

  @Override
  public String toString() {
    return text;
  }
}
