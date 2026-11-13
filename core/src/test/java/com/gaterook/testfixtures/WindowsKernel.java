package com.gaterook.testfixtures;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;

/** Restricts Windows kernel integration assertions to Windows itself, while keeping portable checks active. */
public final class WindowsKernel {
  private WindowsKernel() {}

  /** Call from a Windows-only test; a missing library or an unexpected lookup failure still fails the test. */
  @SuppressWarnings("restricted")
  public static void require(String capability) {
    try (Arena arena = Arena.ofConfined()) {
      boolean wine = SymbolLookup.libraryLookup("ntdll", arena).find("wine_get_version").isPresent();
      assumeFalse(wine, "A real Windows kernel is required to verify " + capability + "; Wine cannot establish this coverage.");
    }
  }
}
