/*!
 * GateRook · theme boot
 * Load synchronously in <head>, before the stylesheet paints, so the page
 * never flashes the wrong theme:
 *   <script src="../assets/theme-boot.js"></script>
 *
 * Resolves the user's choice into attributes on <html>:
 *   theme    "system" | "light" | "dark"   → data-theme="light" | "dark"
 *   contrast "system" | "more" | "normal"  → data-contrast="more" | (absent)
 * "system" follows the OS live (prefers-color-scheme / prefers-contrast).
 * In Electron the OS value comes from nativeTheme; the app persists the choice
 * through its own settings and calls window.gwTheme.set() on start.
 *
 * API: window.gwTheme.get() → { theme, contrast, resolved }
 *      window.gwTheme.set({ theme?, contrast? })
 *      window.addEventListener("gw-theme-change", e => e.detail)
 */
(function () {
  var KEY = "gw-appearance";
  var root = document.documentElement;
  var dark = window.matchMedia("(prefers-color-scheme: dark)");
  var more = window.matchMedia("(prefers-contrast: more)");
  var state = { theme: "system", contrast: "system" };

  try {
    var saved = JSON.parse(localStorage.getItem(KEY) || "{}");
    if (saved.theme) state.theme = saved.theme;
    if (saved.contrast) state.contrast = saved.contrast;
  } catch (e) { /* storage blocked: stay on system */ }

  function resolved() {
    return {
      theme: state.theme === "system" ? (dark.matches ? "dark" : "light") : state.theme,
      contrast: state.contrast === "system" ? (more.matches ? "more" : "normal") : state.contrast,
    };
  }

  function apply() {
    var r = resolved();
    root.setAttribute("data-theme", r.theme);
    if (r.contrast === "more") root.setAttribute("data-contrast", "more");
    else root.removeAttribute("data-contrast");
    window.dispatchEvent(new CustomEvent("gw-theme-change", { detail: { theme: state.theme, contrast: state.contrast, resolved: r } }));
  }

  function onSystemChange() { if (state.theme === "system" || state.contrast === "system") apply(); }
  dark.addEventListener("change", onSystemChange);
  more.addEventListener("change", onSystemChange);

  window.gwTheme = {
    get: function () { return { theme: state.theme, contrast: state.contrast, resolved: resolved() }; },
    set: function (next) {
      if (next && next.theme) state.theme = next.theme;
      if (next && next.contrast) state.contrast = next.contrast;
      try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) { /* not persisted */ }
      apply();
    },
  };

  apply();
})();
