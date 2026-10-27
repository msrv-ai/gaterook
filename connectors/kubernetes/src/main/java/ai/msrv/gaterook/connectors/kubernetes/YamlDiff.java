package ai.msrv.gaterook.connectors.kubernetes;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The unified diff of one object: what is in the cluster against what the API server's dry run says will be there
 * after the apply, both as YAML ({@link ObjectNormalizer#forDiff}). The hunks are GNU {@code diff -u}'s — the tool
 * {@code kubectl diff} runs — three lines of context, changes closer than seven lines in one hunk, a range of one
 * line written without its count and an empty range by the line before it ({@code @@ -0,0 +1,4 @@}).
 *
 * @param lines the diff with its {@code ---}/{@code +++} header, empty when nothing changes
 */
record YamlDiff(List<String> lines, int added, int removed) {

  static final int CONTEXT = 3;

  /**
   * @param name how the header names the object ({@code deployment/api})
   * @param live the object as it is, {@code null} when the apply creates it
   * @param after the dry run's answer
   */
  static YamlDiff of(String name, JsonNode live, JsonNode after) {
    List<String> before = live == null ? List.of() : lines(ObjectNormalizer.forDiff(live));
    List<String> next = lines(ObjectNormalizer.forDiff(after));
    return of(name, before, next);
  }

  static YamlDiff of(String name, List<String> before, List<String> next) {
    Patch<String> patch = DiffUtils.diff(before, next);
    List<AbstractDelta<String>> deltas = patch.getDeltas();
    if (deltas.isEmpty()) {
      return new YamlDiff(List.of(), 0, 0);
    }
    List<String> out = new ArrayList<>();
    out.add("--- " + name + " (live)");
    out.add("+++ " + name + " (after apply)");
    int added = 0;
    int removed = 0;
    int i = 0;
    while (i < deltas.size()) {
      // GNU find_hunk: the next change joins this hunk when fewer than 2 * CONTEXT + 1 lines lie between them.
      int j = i;
      while (j + 1 < deltas.size() && deltas.get(j + 1).getSource().getPosition() - end(deltas.get(j))
          < 2 * CONTEXT + 1) {
        j++;
      }
      AbstractDelta<String> first = deltas.get(i);
      AbstractDelta<String> last = deltas.get(j);
      int srcStart = Math.max(0, first.getSource().getPosition() - CONTEXT);
      int srcEnd = Math.min(before.size(), end(last) + CONTEXT);
      int tgtStart = first.getTarget().getPosition() - (first.getSource().getPosition() - srcStart);
      int tgtEnd = last.getTarget().getPosition() + last.getTarget().size() + (srcEnd - end(last));
      out.add("@@ -" + range(srcStart, srcEnd - srcStart) + " +" + range(tgtStart, tgtEnd - tgtStart) + " @@");
      int cursor = srcStart;
      for (int k = i; k <= j; k++) {
        AbstractDelta<String> d = deltas.get(k);
        for (; cursor < d.getSource().getPosition(); cursor++) {
          out.add(" " + before.get(cursor));
        }
        for (String l : d.getSource().getLines()) {
          out.add("-" + l);
          removed++;
        }
        for (String l : d.getTarget().getLines()) {
          out.add("+" + l);
          added++;
        }
        cursor = end(d);
      }
      for (; cursor < srcEnd; cursor++) {
        out.add(" " + before.get(cursor));
      }
      i = j + 1;
    }
    return new YamlDiff(List.copyOf(out), added, removed);
  }

  private static int end(AbstractDelta<String> d) {
    return d.getSource().getPosition() + d.getSource().size();
  }

  /** GNU's range: {@code start,count}, {@code start} alone for one line, the line before for none. */
  private static String range(int start0, int count) {
    if (count == 0) {
      return start0 + ",0";
    }
    return count == 1 ? String.valueOf(start0 + 1) : (start0 + 1) + "," + count;
  }

  static List<String> lines(JsonNode object) {
    return YamlText.write(object).lines().toList();
  }

  boolean changed() {
    return !lines.isEmpty();
  }

  String text() {
    return String.join("\n", lines);
  }
}
