/* Decorative service / gateway / AI diagram. No timers, network requests or
 * infrastructure calls. HTML remains a complete, linked diagram without JS. */
(function () {
  "use strict";
  document.querySelectorAll("[data-promo-flow]").forEach(function (root) {
    var stage = root.querySelector("[data-flow-stage]");
    var svg = stage.querySelector("svg");
    var hub = stage.querySelector("[data-flow-hub]");
    var sources = Array.from(stage.querySelectorAll("[data-flow-source]"));
    var clients = Array.from(stage.querySelectorAll("[data-flow-client]"));
    var paths = Array.from(svg.querySelectorAll("[data-flow-path]"));
    var button = root.querySelector("[data-flow-pause]");
    var reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
    var paused = false;
    var visible = true;
    var frame = 0;

    function reflectMotion() {
      root.dataset.flowRunning = String(!paused && visible && !document.hidden && !reduced.matches);
      button.hidden = reduced.matches;
      button.querySelector("[data-flow-pause-icon]").hidden = paused;
      button.querySelector("[data-flow-play-icon]").hidden = !paused;
      root.querySelector("[data-flow-pause-label]").textContent = paused ? "Resume animation" : "Pause animation";
    }

    function draw() {
      frame = 0;
      // Read all geometry before writing the SVG. Layout order, rather than a
      // duplicated breakpoint number, determines the orientation.
      var box = stage.getBoundingClientRect();
      if (!box.width || !box.height) return;
      var center = hub.getBoundingClientRect();
      var starts = sources.map(function (node) { return node.getBoundingClientRect(); });
      var ends = clients.map(function (node) { return node.getBoundingClientRect(); });
      var vertical = sources[0].closest(".gw-promo__flow-side").getBoundingClientRect().bottom <= center.top;
      var cx = center.left + center.width / 2 - box.left;
      var cy = center.top + center.height / 2 - box.top;
      var routes = starts.map(function (start, index) {
        var end = ends[index];
        if (vertical) {
          var sx = start.left + start.width / 2 - box.left, sy = start.bottom - box.top;
          var ex = end.left + end.width / 2 - box.left, ey = end.top - box.top;
          var upper = (sy + cy) / 2, lower = (cy + ey) / 2;
          return "M" + sx + " " + sy + " C" + sx + " " + upper + " " + cx + " " + upper + " " + cx + " " + cy + " C" + cx + " " + lower + " " + ex + " " + lower + " " + ex + " " + ey;
        }
        var sx = start.right - box.left, sy = start.top + start.height / 2 - box.top;
        var ex = end.left - box.left, ey = end.top + end.height / 2 - box.top;
        var left = (sx + cx) / 2, right = (cx + ex) / 2;
        return "M" + sx + " " + sy + " C" + left + " " + sy + " " + left + " " + cy + " " + cx + " " + cy + " C" + right + " " + cy + " " + right + " " + ey + " " + ex + " " + ey;
      });
      svg.setAttribute("viewBox", "0 0 " + box.width + " " + box.height);
      paths.forEach(function (path) { path.setAttribute("d", routes[Number(path.dataset.flowPath)]); });
      root.dataset.flowReady = "true";
    }

    function scheduleDraw() { if (!frame) frame = window.requestAnimationFrame(draw); }
    button.addEventListener("click", function () { paused = !paused; reflectMotion(); });
    reduced.addEventListener("change", reflectMotion);
    document.addEventListener("visibilitychange", reflectMotion);
    if ("ResizeObserver" in window) {
      var resize = new ResizeObserver(scheduleDraw);
      [stage, hub].concat(sources, clients).forEach(function (node) { resize.observe(node); });
    } else window.addEventListener("resize", scheduleDraw);
    if ("IntersectionObserver" in window) {
      var intersection = new IntersectionObserver(function (entries) { visible = entries[0].isIntersecting; reflectMotion(); });
      intersection.observe(stage);
    }
    if (document.fonts) document.fonts.ready.then(scheduleDraw);
    draw();
    reflectMotion();
  });
})();
