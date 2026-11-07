/* Shared, local-only MCP setup preview. Markup is generated from fragments/mcp-setup.html. */
(function () {
  "use strict";
  var doc = document;
  var toast = window.gwToast || function () {};
  var add = doc.getElementById("dlg-add-mcp");
  if (!add) return;
  function prepare(source) {
    add.querySelector("form").reset();
    var transport = add.querySelector('input[name="mcp-transport"][value="' + (source === "http" ? "http" : "stdio") + '"]');
    transport.checked = true;
    transport.dispatchEvent(new Event("change", { bubbles: true }));
  }
  // Prepare before kit.js opens the dialog, including a chooser's local next step.
  doc.addEventListener("click", function (event) {
    var trigger = event.target.closest('[data-gw-dialog-open="dlg-add-mcp"], [data-mcp-setup-source]');
    if (trigger && trigger.getAttribute("aria-disabled") !== "true") prepare(trigger.getAttribute("data-mcp-setup-source"));
  }, true);
  doc.addEventListener("click", function (event) {
    if (!event.target.closest("[data-mcp-setup-back]")) return;
    var review = doc.getElementById("dlg-review-mcp");
    add._gwReturn = review._gwReturn;
    review._gwReturn = null;
    review.close("back");
    window.gwShowDialog(add);
    doc.getElementById("mcp-name").focus();
  });
  // ───── Add MCP server → review → start ─────
  var stdio = doc.getElementById("mcp-stdio-group");
  var http = doc.getElementById("mcp-http-group");
  doc.querySelectorAll('input[name="mcp-transport"]').forEach(function (radio) {
    radio.addEventListener("change", function () {
      var isHttp = radio.value === "http" && radio.checked;
      stdio.hidden = isHttp;
      http.hidden = !isHttp;
      doc.getElementById("mcp-command").disabled = isHttp;
      doc.getElementById("mcp-url").disabled = !isHttp;
      doc.getElementById("dlg-add-mcp-d").textContent = isHttp ? "Connect this service by URL. GateRook checks access and records tool calls." : "Put any MCP server behind the gateway. It runs as its own process; its tools get modes, approvals and audit.";
      var reviewButton = doc.querySelector('#dlg-add-mcp button[type="submit"]');
      reviewButton.lastChild.textContent = isHttp ? " Review connection" : " Review and start";
    });
  });
  // Existing Connectors deep links keep their direct setup behavior.
  var setupQuery = new URLSearchParams(location.search);
  if (setupQuery.get("add") === "mcp") {
    var transport = doc.querySelector('input[name="mcp-transport"][value="' + (setupQuery.get("transport") === "http" ? "http" : "stdio") + '"]');
    transport.checked = true;
    transport.dispatchEvent(new Event("change", { bubbles: true }));
    function openUrlSetup() {
      var setupDialog = doc.getElementById("dlg-add-mcp");
      setupDialog._gwReturn = doc.querySelector('#mcp-servers [data-gw-dialog-open="dlg-add-mcp"]');
      setupDialog._gwReturn.focus();
      window.gwShowDialog(setupDialog);
      doc.getElementById("mcp-name").focus();
    }
    // Wait for initial fragment navigation before placing focus in the dialog.
    if (doc.readyState === "complete") requestAnimationFrame(openUrlSetup);
    else window.addEventListener("load", function () { requestAnimationFrame(openUrlSetup); }, { once: true });
  }
  var review = doc.getElementById("dlg-review-mcp");
  function put(key, text) { review.querySelectorAll('[data-mcp="' + key + '"]').forEach(function (n) { n.textContent = text; }); }
  function part(key) { return review.querySelector('[data-mcp="' + key + '"]'); }
  // The npm package of an npx / pnpm dlx / bunx command, and its pinned version if any
  function npmPackage(command) {
    var words = command.trim().split(/\s+/);
    if (!/^(npx|pnpx|bunx)$/.test(words[0]) && !(words[0] === "pnpm" && words[1] === "dlx")) return null;
    var pkg = words.slice(1).filter(function (w) { return w.charAt(0) !== "-" && w !== "dlx"; })[0] || "";
    var at = pkg.lastIndexOf("@");
    var version = at > 0 ? pkg.slice(at + 1) : "";
    return { name: pkg, version: /^\d/.test(version) ? version : "" }; // "@latest" or a range is not a pin
  }
  // Capture phase: the review is filled before kit.js opens it
  doc.addEventListener("submit", function (e) {
    if (!e.target.matches || !e.target.matches("#dlg-add-mcp form")) return;
    var name = doc.getElementById("mcp-name").value.trim();
    var isHttp = doc.querySelector('input[name="mcp-transport"]:checked').value === "http";
    put("name", name);
    put("verb", isHttp ? "Connect" : "Start");
    put("target-key", isHttp ? "URL" : "Command");
    put("target", isHttp ? doc.getElementById("mcp-url").value.trim() : doc.getElementById("mcp-command").value.trim());
    put("risk", isHttp
      ? "Every call sends these secrets to this URL. Nobody has verified the server behind it."
      : "This code runs on your computer with these secrets. Nobody has verified it: it can read them and reach anything your user account can.");
    var pkg = isHttp ? null : npmPackage(doc.getElementById("mcp-command").value);
    part("version-row").hidden = !pkg;
    part("runs-row").hidden = isHttp;
    if (pkg) {
      part("pinned").hidden = !pkg.version;
      part("unpinned").hidden = !!pkg.version;
      put("version", pkg.version);
    }
    var envName = doc.getElementById("mcp-env-name").value.trim();
    var secret = doc.getElementById("mcp-env-secret").value;
    var secrets = part("secrets");
    secrets.textContent = "";
    if (envName && secret) {
      var n = doc.createElement("span");
      n.className = "gw-mono";
      n.textContent = envName;
      var v = doc.createElement("span");
      v.className = "gw-mono";
      v.textContent = secret === "new" ? name + "/token" : secret;
      secrets.appendChild(n);
      secrets.appendChild(doc.createTextNode(" from the secret "));
      secrets.appendChild(v);
    } else {
      secrets.textContent = "None";
    }
    var ack = doc.getElementById("ack-mcp");
    ack.checked = false;
    ack.dispatchEvent(new Event("change", { bubbles: true })); // kit.js: the start button waits for it again
  }, true);
  doc.getElementById("mcp-review-form").addEventListener("submit", function () {
    var name = part("name").textContent;
    // after the dialog closes, so the toast lands on the page and not inside the closing dialog
    setTimeout(function () {
      toast({ tone: "success", title: "MCP server " + name + (part("verb").textContent === "Connect" ? " connected" : " started"), body: "Its tools are listed and Off until you add them to an endpoint. Declared read-only tools start in Read only; other tools start in Approval." });
    }, 0);
  });

})();
