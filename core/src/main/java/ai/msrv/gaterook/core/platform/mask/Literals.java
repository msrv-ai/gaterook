package ai.msrv.gaterook.core.platform.mask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Aho-Corasick over the lower-case ASCII literals of all rules: one pass over a text finds every occurrence of every
 * literal, so the rule set costs about one scan per value instead of one {@code indexOf} per literal (a megabyte has
 * about 50 of them to look for).
 */
final class Literals {

  /** Receives the position where a literal starts and the rule it belongs to. */
  interface Hit {
    void at(int start, int rule);
  }

  private final int[][] next;
  private final int[] fail;
  private final int[][] rules;   // per state: rules of every literal ending here
  private final int[][] lengths; // per state: lengths of those literals, parallel to rules

  /** @param literals per rule index, the rule's literals */
  Literals(List<List<String>> literals) {
    List<int[]> trie = new ArrayList<>();
    List<List<Integer>> ruleOut = new ArrayList<>();
    List<List<Integer>> lengthOut = new ArrayList<>();
    trie.add(newRow());
    ruleOut.add(new ArrayList<>());
    lengthOut.add(new ArrayList<>());
    for (int rule = 0; rule < literals.size(); rule++) {
      for (String literal : literals.get(rule)) {
        int state = 0;
        for (int i = 0; i < literal.length(); i++) {
          int c = literal.charAt(i);
          if (c >= 128) {
            throw new IllegalArgumentException("Literals are ASCII: " + literal);
          }
          if (trie.get(state)[c] < 0) {
            trie.add(newRow());
            ruleOut.add(new ArrayList<>());
            lengthOut.add(new ArrayList<>());
            trie.get(state)[c] = trie.size() - 1;
          }
          state = trie.get(state)[c];
        }
        ruleOut.get(state).add(rule);
        lengthOut.get(state).add(literal.length());
      }
    }
    int states = trie.size();
    next = trie.toArray(new int[0][]);
    fail = new int[states];
    // Breadth-first: failure links and the full goto function, so scanning never backtracks.
    ArrayDeque<Integer> queue = new ArrayDeque<>();
    for (int c = 0; c < 128; c++) {
      if (next[0][c] < 0) {
        next[0][c] = 0;
      } else {
        fail[next[0][c]] = 0;
        queue.add(next[0][c]);
      }
    }
    while (!queue.isEmpty()) {
      int state = queue.poll();
      ruleOut.get(state).addAll(ruleOut.get(fail[state]));
      lengthOut.get(state).addAll(lengthOut.get(fail[state]));
      for (int c = 0; c < 128; c++) {
        int target = next[state][c];
        if (target < 0) {
          next[state][c] = next[fail[state]][c];
        } else {
          fail[target] = next[fail[state]][c];
          queue.add(target);
        }
      }
    }
    rules = new int[states][];
    lengths = new int[states][];
    for (int s = 0; s < states; s++) {
      rules[s] = ruleOut.get(s).stream().mapToInt(Integer::intValue).toArray();
      lengths[s] = lengthOut.get(s).stream().mapToInt(Integer::intValue).toArray();
    }
  }

  /** Every literal occurrence in {@code lower} (already lower case). */
  void scan(String lower, Hit hit) {
    int state = 0;
    int length = lower.length();
    for (int i = 0; i < length; i++) {
      int c = lower.charAt(i);
      state = c < 128 ? next[state][c] : 0;
      int[] out = rules[state];
      if (out.length > 0) {
        int[] len = lengths[state];
        for (int k = 0; k < out.length; k++) {
          hit.at(i - len[k] + 1, out[k]);
        }
      }
    }
  }

  private static int[] newRow() {
    int[] row = new int[128];
    Arrays.fill(row, -1);
    return row;
  }
}
