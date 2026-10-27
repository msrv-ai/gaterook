/* GateRook marketing demo. Static examples only: no fetch, no execution,
 * no credentials, and no persistence of the sample approval comment.
 * Appearance uses the shared kit API; all handlers are scoped to this page.
 */
(function () {
  "use strict";
  // Set each URL only after that platform's installer has been published.
  // Keep null to display Coming soon without a broken download link.
  var releases = { macos: null, windows: null };
  document.querySelectorAll("[data-promo-download]").forEach(function (link) {
    var platform = link.getAttribute("data-promo-download");
    var url = releases[platform];
    if (!url) return;
    link.href = url;
    link.setAttribute("download", "");
    link.removeAttribute("aria-disabled");
    link.removeAttribute("tabindex");
    document.getElementById(link.getAttribute("aria-describedby")).textContent = "Available to download.";
  });
  var themeButton = document.querySelector("[data-promo-theme]");
  var contrastInput = document.querySelector("[data-promo-contrast]");
  function reflectTheme() {
    var appearance = window.gwTheme.get().resolved;
    themeButton.setAttribute("aria-label", "Switch to " + (appearance.theme === "dark" ? "light" : "dark") + " theme");
    contrastInput.checked = appearance.contrast === "more";
  }
  themeButton.addEventListener("click", function () {
    window.gwTheme.set({ theme: window.gwTheme.get().resolved.theme === "dark" ? "light" : "dark" });
  });
  contrastInput.addEventListener("change", function () {
    window.gwTheme.set({ contrast: contrastInput.checked ? "more" : "normal" });
  });
  window.addEventListener("gw-theme-change", reflectTheme);
  reflectTheme();

  var root = document.querySelector("[data-promo-demo]");
  if (!root) return;
  var find = function (name) { return root.querySelector("[data-promo-" + name + "]"); };
  var set = function (name, value) { find(name).textContent = value; };
  var scenarios = {
    dev: {
      resources: [["dev-db", "PostgreSQL", "database"], ["app-logs", "Loki", "scroll-text"], ["dev-vm", "SSH", "server"]],
      endpoint: "dev", target: "dev-db", tool: "sql_execute", client: "Codex",
      policy: "Read to investigate. Ask before changing.",
      prompt: "“Find the stuck order and cancel it in dev.”",
      trace: "Read logs and inspect the order",
      code: "UPDATE orders\nSET status = 'cancelled'\nWHERE id = 48213;",
      rule: "Changes to dev-db require your approval.",
      badge: "dev"
    },
    stage: {
      resources: [["stage-db", "PostgreSQL", "database"], ["aws-stage", "AWS", "cloud"], ["billing-api", "HTTP API", "globe"]],
      endpoint: "stage-readonly", target: "stage-db", tool: "sql_query", client: "claude.ai",
      policy: "Read-only access. Write requests are blocked.",
      prompt: "“Check recent orders in the QA environment.”",
      trace: "Inspect data without changing it",
      code: "SELECT id, status FROM orders\nWHERE created_at > now() - interval '1 hour'\nLIMIT 50;",
      rule: "This endpoint allows reads only. A write request is denied by policy.",
      badge: "stage / QA"
    },
    prod: {
      resources: [["prod-replica", "PostgreSQL", "database"], ["prod-logs", "Cloud Logging", "scroll-text"], ["prod-vm", "SSH runbooks", "server"]],
      endpoint: "incident-prod", target: "prod-vm", tool: "ssh_run", client: "Claude Desktop",
      policy: "Selected reads. Approved runbooks. No arbitrary commands.",
      prompt: "“Inspect the API and propose a service restart.”",
      trace: "Inspect production logs and service state",
      code: "sudo systemctl restart api.service",
      rule: "Production change: the restart-api runbook needs your approval and a comment.",
      badge: "prod"
    }
  };
  var environment = "dev";
  var decided = false;
  var comment = document.getElementById("demo-comment");

  function announce(message) { find("announcement").textContent = message; }
  function updateApproval() {
    var needsComment = environment === "prod" && !comment.value.trim();
    find("approve").setAttribute("aria-disabled", String(needsComment));
    if (needsComment) find("approve").setAttribute("aria-describedby", "comment-hint");
    else find("approve").removeAttribute("aria-describedby");
  }
  function render(shouldAnnounce) {
    var scenario = scenarios[environment];
    decided = false;
    comment.value = "";
    comment.required = environment === "prod";
    ["endpoint", "target", "tool", "policy", "prompt", "trace", "code", "rule", "client"].forEach(function (key) {
      set(key, scenario[key]);
    });
    scenario.resources.forEach(function (resource, index) {
      var label = root.querySelector('[data-promo-resource="' + index + '"]');
      label.textContent = resource[0];
      root.querySelector('[data-promo-resource-note="' + index + '"]').textContent = resource[1];
      var icon = document.createElement("i");
      icon.className = "gw-icon";
      icon.setAttribute("data-lucide", resource[2]);
      label.closest("li").querySelector(".gw-icon-tile").replaceChildren(icon);
    });
    if (window.gwIcons) window.gwIcons(root);
    var badge = find("env-badge");
    badge.className = "gw-env gw-env--" + environment;
    badge.textContent = scenario.badge;
    var readOnly = environment === "stage";
    find("status").className = "gw-badge gw-badge--" + (readOnly ? "info" : "warning");
    set("status", readOnly ? "Read only" : "Waiting for you");
    set("action-label", readOnly ? "Allowed query · exact statement" : "Review the exact action before it runs");
    find("decisions").hidden = readOnly;
    find("readonly").hidden = !readOnly;
    find("result").hidden = true;
    find("comment-field").hidden = environment !== "prod";
    find("runbook").hidden = environment !== "prod";
    root.querySelector(".gw-approval-request").setAttribute("data-state", readOnly ? "read-only" : "pending");
    updateApproval();
    if (shouldAnnounce) announce(scenario.badge + " example. " + scenario.policy);
  }
  function finish(kind) {
    if (decided || (kind === "approved" && (environment === "stage" || find("approve").getAttribute("aria-disabled") === "true"))) return;
    decided = true;
    var blocked = kind === "blocked";
    var approved = kind === "approved";
    find("status").className = "gw-badge gw-badge--" + (approved ? "success" : "danger");
    set("status", approved ? "Approved by you" : blocked ? "Denied by policy" : "Denied by you");
    var message = approved
      ? "Demo: approved by you. GateRook would run this exact action once and record your decision. Nothing was executed here."
      : blocked
        ? "Demo: write request blocked. The stage-readonly endpoint does not allow changes, so there is nothing to approve."
        : "Demo: denied by you. The action would not run. Your decision would appear in the activity log.";
    set("feedback", message);
    find("decisions").hidden = true;
    find("readonly").hidden = true;
    find("result").hidden = false;
    comment.readOnly = true;
    root.querySelector(".gw-approval-request").setAttribute("data-state", approved ? "approved" : "denied");
    find("reset").focus();
    announce(message);
  }
  root.addEventListener("change", function (event) {
    if (event.target.name === "promo-env" && scenarios[event.target.value]) {
      environment = event.target.value;
      comment.readOnly = false;
      render(true);
    }
  });
  comment.addEventListener("input", updateApproval);
  find("approve").addEventListener("click", function () { finish("approved"); });
  find("deny").addEventListener("click", function () { finish("denied"); });
  find("try-write").addEventListener("click", function () {
    set("tool", "sql_execute");
    set("code", "UPDATE orders\nSET status = 'cancelled'\nWHERE id = 48213;");
    set("action-label", "Blocked write · exact statement");
    set("prompt", "“Cancel this order in the QA environment.”");
    set("trace", "Write request checked against the read-only policy");
    set("rule", "Writes to stage-db are disabled on stage-readonly. This request cannot run.");
    finish("blocked");
  });
  find("reset").addEventListener("click", function () {
    comment.readOnly = false;
    render(true);
    (environment === "stage" ? find("try-write") : find("deny")).focus();
  });

  render(false);
})();
