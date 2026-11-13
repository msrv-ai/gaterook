package com.gaterook.core.platform.mask;

import java.util.LinkedHashMap;
import java.util.Map;

/** Replacements of one masking pass, by rule id: what {@code _meta["com.gaterook/masked"]} and the audit report. */
public final class MaskCounter {

  private final Map<String, Integer> byRule = new LinkedHashMap<>();
  private int replacements;

  public void hit(String rule) {
    replacements++;
    byRule.merge(rule, 1, Integer::sum);
  }

  public int replacements() {
    return replacements;
  }

  public Map<String, Integer> byRule() {
    return Map.copyOf(byRule);
  }
}
