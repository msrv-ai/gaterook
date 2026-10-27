package ai.msrv.gaterook.core.clients.internal;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "What will change" (kit {@code gw-diff}): a unified diff of the config file before and after our edit, with three
 * lines of context, as hunks the UI draws line by line. A token never reaches it: previews diff the text with the
 * placeholder {@code ‹new token›}.
 */
public final class DiffService {

  private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

  private DiffService() {}

  /** One line of a hunk: {@code context}, {@code added} or {@code removed}, with its numbers in either file. */
  public record Line(String type, Integer oldNo, Integer newNo, String text) {}

  public record Hunk(String header, List<Line> lines) {}

  /** {@code added}/{@code removed} count lines, as the kit's "7 additions, 1 deletion". */
  public record Diff(int added, int removed, List<Hunk> hunks) {

    public static Diff none() {
      return new Diff(0, 0, List.of());
    }
  }

  /** The diff of {@code before} (null: no file) → {@code after} (null: file removed). */
  public static Diff diff(String before, String after) {
    List<String> a = lines(before);
    List<String> b = lines(after);
    Patch<String> patch = DiffUtils.diff(a, b);
    if (patch.getDeltas().isEmpty()) {
      return Diff.none();
    }
    List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("before", "after", a, patch, 3);
    List<Hunk> hunks = new ArrayList<>();
    String header = null;
    List<Line> current = null;
    int oldNo = 0;
    int newNo = 0;
    int added = 0;
    int removed = 0;
    for (String u : unified) {
      if (u.startsWith("---") || u.startsWith("+++")) {
        continue;
      }
      Matcher m = HUNK.matcher(u);
      if (m.find()) {
        if (current != null) {
          hunks.add(new Hunk(header, List.copyOf(current)));
        }
        current = new ArrayList<>();
        oldNo = Integer.parseInt(m.group(1));
        newNo = Integer.parseInt(m.group(3));
        // java-diff-utils numbers an empty side from its position + 1 ("-1,0" for no file); unified diff, and the
        // kit, write the line before it ("-0,0"), as splice does.
        header = "@@ -" + range(oldNo - 1, count(m.group(2))) + " +" + range(newNo - 1, count(m.group(4))) + " @@";
        continue;
      }
      if (current == null || u.isEmpty()) {
        continue;
      }
      char kind = u.charAt(0);
      String text = u.substring(1);
      switch (kind) {
        case '+' -> {
          current.add(new Line("added", null, newNo++, text));
          added++;
        }
        case '-' -> {
          current.add(new Line("removed", oldNo++, null, text));
          removed++;
        }
        default -> current.add(new Line("context", oldNo++, newNo++, text));
      }
    }
    if (current != null) {
      hunks.add(new Hunk(header, List.copyOf(current)));
    }
    return new Diff(added, removed, List.copyOf(hunks));
  }

  /**
   * The diff of one surgical edit, line for line as it happened: the lines the edit touched in {@code before}
   * against the lines they became. A comma appended to the previous entry's {@code \}} shows as that line changed,
   * as the kit draws it ({@code -    \}}, {@code +    \},}, then the new entry), where a minimal line diff would
   * pretend the old line moved. Our editors change one region; two would show as one hunk spanning both, so a
   * restore, which can differ anywhere, uses {@link #diff}.
   */
  public static Diff splice(String before, String after) {
    String a = before == null ? "" : before;
    String b = after == null ? "" : after;
    if (a.equals(b)) {
      return Diff.none();
    }
    int p = 0;
    int max = Math.min(a.length(), b.length());
    while (p < max && a.charAt(p) == b.charAt(p)) {
      p++;
    }
    int s = 0;
    while (s < max - p && a.charAt(a.length() - 1 - s) == b.charAt(b.length() - 1 - s)) {
      s++;
    }
    int endA = a.length() - s;
    int endB = b.length() - s;
    int[] startsA = lineStarts(a);
    int[] startsB = lineStarts(b);
    int fromLine = lineOf(startsA, p);
    int toA;
    int toB;
    boolean wholeLines = atLineStart(a, p) && atLineStart(a, endA) && atLineStart(b, endB);
    if (wholeLines) {
      toA = lineOf(startsA, endA);
      toB = lineOf(startsB, endB);
    } else {
      toA = lineOf(startsA, endA) + 1;
      toB = lineOf(startsB, endB) + 1;
    }
    List<String> la = lines(before);
    List<String> lb = lines(after);
    toA = Math.min(toA, la.size());
    toB = Math.min(toB, lb.size());
    int ctxFrom = Math.max(0, fromLine - SPLICE_CONTEXT);
    int ctxAfterA = Math.min(la.size(), toA + SPLICE_CONTEXT);
    int ctxAfterB = Math.min(lb.size(), toB + SPLICE_CONTEXT);
    List<Line> out = new ArrayList<>();
    for (int i = ctxFrom; i < fromLine; i++) {
      out.add(new Line("context", i + 1, i + 1, la.get(i)));
    }
    for (int i = fromLine; i < toA; i++) {
      out.add(new Line("removed", i + 1, null, la.get(i)));
    }
    for (int i = fromLine; i < toB; i++) {
      out.add(new Line("added", null, i + 1, lb.get(i)));
    }
    for (int i = toA, j = toB; i < ctxAfterA && j < ctxAfterB; i++, j++) {
      out.add(new Line("context", i + 1, j + 1, la.get(i)));
    }
    int oldCount = toA - ctxFrom + Math.min(ctxAfterA - toA, ctxAfterB - toB);
    int newCount = toB - ctxFrom + Math.min(ctxAfterA - toA, ctxAfterB - toB);
    String header = "@@ -" + range(ctxFrom, oldCount) + " +" + range(ctxFrom, newCount) + " @@";
    return new Diff(toB - fromLine, toA - fromLine, List.of(new Hunk(header, List.copyOf(out))));
  }

  /** The kit's preview keeps five lines around an edit, so a small config shows whole. */
  private static final int SPLICE_CONTEXT = 5;

  private static int count(String group) {
    return group == null ? 1 : Integer.parseInt(group);
  }

  /** {@code start,count} of a unified diff header; an empty side is "the line before", {@code 0,0} for a new file. */
  private static String range(int from, int count) {
    if (count == 0) {
      return from + ",0";
    }
    return count == 1 ? Integer.toString(from + 1) : (from + 1) + "," + count;
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  /** The 0-based line holding char {@code pos}. */
  private static int lineOf(int[] starts, int pos) {
    int lo = 0;
    int hi = starts.length - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (starts[mid] <= pos) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return lo;
  }

  private static boolean atLineStart(String text, int pos) {
    return pos == 0 || pos >= text.length() && text.endsWith("\n") || pos < text.length() && pos > 0
        && text.charAt(pos - 1) == '\n';
  }

  /** Lines without their ends; a final newline doesn't make an extra empty line. */
  static List<String> lines(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    List<String> out = new ArrayList<>();
    for (String l : t.split("\n", -1)) {
      out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
    }
    return out;
  }
}
