/* Progressive enhancement only. All integration links and article text are
 * already in HTML; browsing does not depend on this script or a backend. */
(function () {
  "use strict";
  var catalog = document.querySelector("[data-docs-catalog]");
  if (!catalog) return;
  var input = catalog.querySelector("[data-docs-search-input]");
  var cards = Array.from(catalog.querySelectorAll("[data-docs-card]"));
  var groups = Array.from(catalog.querySelectorAll("[data-docs-group]"));
  function filter() {
    var terms = input.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    var count = 0;
    cards.forEach(function (card) {
      var matches = terms.every(function (term) { return card.getAttribute("data-docs-search").includes(term); });
      card.hidden = !matches;
      if (matches) count++;
    });
    groups.forEach(function (group) { group.hidden = !group.querySelector("[data-docs-card]:not([hidden])"); });
    catalog.querySelector("[data-docs-count]").textContent = count + " integration guide" + (count === 1 ? "" : "s");
    catalog.querySelector("[data-docs-empty]").hidden = count !== 0;
  }
  input.addEventListener("input", filter);
  catalog.querySelector("[data-docs-clear]").addEventListener("click", function () { input.value = ""; filter(); input.focus(); });
  catalog.querySelectorAll('nav a[href^="#"]').forEach(function (link) {
    link.addEventListener("click", function () { input.value = ""; filter(); });
  });
  filter();
})();
