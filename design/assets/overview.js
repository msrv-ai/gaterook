/* Interactive Overview reference. This state never calls an API or changes an app's
   configuration. Shared fixtures follow BLUEPRINT; additions last until reload. */
(function () {
  "use strict";
  const doc = document;
  const byId = (id) => doc.getElementById(id);
  const params = new URLSearchParams(location.search);
  const state = params.get("state") || "in-use";
  const onboarding = state === "first-run" || state === "setup";
  let availability = state === "loading" || state === "error" ? state : "ready";
  let appsUnavailable = params.get("apps") === "unavailable";
  let expanded = false;
  const services = {
    "dev-db": { type: "PostgreSQL", env: "dev", icon: "database", health: "Healthy", tone: "success" },
    "orders-mongo": { type: "MongoDB", env: "dev", icon: "database", health: "Healthy", tone: "success" },
    "cache-redis": { type: "Redis", env: "dev", icon: "database", health: "Slow · 1.8 s", tone: "warning" },
    "dev-vm": { type: "SSH", env: "dev", icon: "terminal", health: "Healthy", tone: "success" },
    "app-logs": { type: "Loki", env: "dev", icon: "logs", health: "Healthy", tone: "success" },
    "dev-cluster": { type: "Kubernetes", env: "dev", icon: "boxes", health: "Healthy", tone: "success" },
    "aws-stage": { type: "AWS", env: "stage", icon: "cloud", health: "Healthy", tone: "success" },
    "billing-api": { type: "HTTP API", env: "stage", icon: "globe", health: "Unreachable", tone: "danger" },
    "stage-db": { type: "PostgreSQL", env: "stage", icon: "database", health: "Healthy", tone: "success" },
    "prod-replica": { type: "PostgreSQL", env: "prod", icon: "database", health: "Healthy", tone: "success" },
    "prod-logs": { type: "GCP Cloud Logging", env: "prod", icon: "logs", health: "Healthy", tone: "success" },
    "prod-vm": { type: "SSH · runbooks only", env: "prod", icon: "terminal", health: "Healthy", tone: "success" },
    "github-mcp": { type: "External MCP · 26 tools", env: "dev", icon: "plug", health: "Ready", tone: "success" }
  };
  const apps = {
    claude: { name: "Claude Desktop", mark: "CD", tone: "claude", anchor: "cd" },
    codex: { name: "Codex", mark: "Cx", tone: "codex", anchor: "cx" },
    cursor: { name: "Cursor", mark: "Cu", tone: "cursor", anchor: "cu" },
    web: { name: "claude.ai", mark: "Cl", tone: "claude", anchor: "web" }
  };
  let endpoints = [
    { slug: "dev", name: "Everyday development", env: "dev", enabled: true,
      services: ["billing-api", "cache-redis", "dev-db", "orders-mongo", "dev-vm", "app-logs", "dev-cluster", "aws-stage"],
      apps: [{ id: "claude", status: "Connected" }, { id: "codex", status: "Connected" }] },
    { slug: "stage-readonly", name: "Staging · read-only access", env: "stage", enabled: true, readonly: true,
      services: ["billing-api", "stage-db", "aws-stage"], apps: [{ id: "web", status: "Tunnel off" }] },
    { slug: "incident-prod", name: "Production · restricted access", env: "prod", enabled: true,
      services: ["prod-replica", "prod-logs", "prod-vm"], apps: [{ id: "claude", status: "Connected" }] }
  ];
  if (onboarding) endpoints = params.get("endpoints") === "none" ? [] : [
    { slug: "dev", name: "Your first endpoint", env: "dev", enabled: true,
      services: state === "setup" ? ["dev-db"] : [], apps: [] }
  ];
  let selected = endpoints.find((ep) => ep.slug === params.get("endpoint")) || endpoints[0];
  const escape = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
  const icon = (name, size = "") => `<i class="gw-icon ${size}" data-lucide="${name}"></i>`;
  const status = (label, tone = "neutral") => `<span class="gw-status gw-status--${tone}"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">${escape(label)}</span></span>`;
  const empty = (title, text, glyph = "circle-dashed") => `<li class="gw-empty gw-empty--compact">${icon(glyph, "gw-empty__icon")}<h4 class="gw-empty__title">${title}</h4><p class="gw-empty__text">${text}</p></li>`;
  const refreshIcons = () => window.lucide?.createIcons({ attrs: { "aria-hidden": "true", focusable: "false" } });
  function announce(message) { byId("ov-map-status").textContent = message; }
  function openDialog(id, trigger) {
    const dialog = byId(id);
    dialog._gwReturn = trigger;
    window.gwShowDialog(dialog);
    refreshIcons();
  }
  function toast(title, body) { window.gwToast?.({ title, body, tone: "info" }); }

  function renderEndpoints() {
    byId("ov-endpoints").innerHTML = endpoints.length ? endpoints.map((ep, index) => `
      <label class="gw-choice-card gw-choice-card--compact">
        <input class="gw-choice-card__input" type="radio" name="ov-endpoint" value="${escape(ep.slug)}" ${ep === selected ? "checked" : ""} aria-labelledby="ov-ep-${index}-title" aria-describedby="ov-ep-${index}-description ov-endpoint-help">
        <span class="gw-choice-card__title" id="ov-ep-${index}-title"><span class="gw-mono gw-truncate" title="${escape(ep.slug)}">${escape(ep.slug)}</span></span>
        <span class="gw-choice-card__description" id="ov-ep-${index}-description">${escape(ep.name)}</span>
        <span class="gw-choice-card__meta"><span class="gw-env gw-env--sm gw-env--${ep.env}">${ep.env}</span> · ${ep.services.length} ${ep.services.length === 1 ? "service" : "services"} · ${ep.enabled ? "On" : "Off"}</span>
      </label>`).join("") : '<p class="gw-text-small gw-fg-muted">No endpoints yet. Create one to choose what your AI can use.</p>';
  }

  function renderServices() {
    const list = byId("ov-services");
    const items = selected?.services || [];
    byId("ov-service-count").textContent = items.length;
    byId("ov-service-scope").textContent = selected ? `Shared through ${selected.slug}` : "Add an endpoint to start sharing";
    list.setAttribute("aria-label", selected ? `Services shared through ${selected.slug}` : "No services shared yet");
    byId("ov-show-services").hidden = items.length <= 4 || availability !== "ready";
    byId("ov-show-services").setAttribute("aria-expanded", String(expanded));
    byId("ov-show-services").innerHTML = `${expanded ? "Show fewer services" : `Show all ${items.length} services`}${icon("chevron-down", "gw-btn__chevron")}`;
    byId("ov-unshared").hidden = onboarding || endpoints.some((ep) => ep.services.includes("github-mcp")) || availability !== "ready";
    if (availability !== "ready") {
      list.innerHTML = empty(availability === "loading" ? "Loading your services" : "Could not load your services", availability === "loading" ? "Checking the local gateway. Your saved settings are unchanged." : "The local gateway did not respond. Retry to check your saved connections.", availability === "loading" ? "loader-circle" : "unplug") + '<li><button class="gw-btn gw-btn--sm" type="button" data-ov-retry>Retry connection</button></li>';
    } else if (!items.length) {
      list.innerHTML = empty("No services shared yet", selected ? "Add an MCP server, database or API to this endpoint. Start with Add service below." : "Create an endpoint in the middle, then add your first service.", "plug");
    } else {
      list.innerHTML = items.slice(0, expanded ? items.length : 4).map((id) => {
        const service = services[id];
        const paused = !selected.enabled;
        return `<li class="gw-card gw-card--interactive gw-status-tile ${paused ? "gw-card--muted" : ""}">
          <span class="gw-icon-tile gw-icon-tile--sm gw-icon-tile--neutral" aria-hidden="true">${icon(service.icon)}</span>
          <div class="gw-status-tile__main"><h4 class="gw-status-tile__title gw-text-h3"><a class="gw-card__link gw-mono" href="${id === "github-mcp" ? "connectors.html#mcp-servers" : `connections.html?q=${encodeURIComponent(id)}`}" title="${escape(id)}">${escape(id)}</a></h4><p class="gw-text-caption gw-fg-muted">${escape(service.type)} · <span class="gw-env gw-env--sm gw-env--${service.env}">${service.env}</span></p></div>
          <div class="gw-status-tile__end">${status(paused ? "Endpoint off" : service.health, paused ? "neutral" : service.tone)}</div>
        </li>`;
      }).join("");
    }
    byId("ov-services-step").dataset.state = !items.length || availability !== "ready" ? "todo" : selected.enabled && items.some((id) => services[id].tone !== "success") ? "attention" : "done";
  }

  function renderApps() {
    const links = selected?.apps || [];
    byId("ov-app-count").textContent = appsUnavailable || availability !== "ready" ? "—" : links.length;
    byId("ov-app-scope").textContent = selected ? `Access through ${selected.slug} only` : "Choose an endpoint first";
    byId("ov-try").hidden = !links.some((link) => link.status === "Connected") || !selected?.enabled || appsUnavailable || availability !== "ready";
    byId("ov-apps").setAttribute("aria-label", selected ? `AI apps using ${selected.slug}` : "No AI apps connected");
    byId("ov-apps").innerHTML = availability !== "ready" || appsUnavailable
      ? empty("App status unavailable", "Your app settings have not been changed. Retry to check their connection status.", "circle-help") + '<li><button class="gw-btn gw-btn--sm" type="button" data-ov-retry>Retry app status</button></li>'
      : !links.length ? empty("Your AI app goes here", selected?.services.length ? "Connect an app to use this endpoint's services. You will review its access first." : "Add a service, then connect the AI app you already use.", "app-window")
      : links.map((link) => {
        const app = apps[link.id];
        const label = selected.enabled ? link.status : "Endpoint off";
        return `<li class="gw-card gw-card--interactive gw-status-tile ${selected.enabled ? "" : "gw-card--muted"}">
          <span class="gw-client-mark gw-client-mark--${app.tone}" aria-hidden="true">${app["mark"]}</span>
          <div class="gw-status-tile__main"><h4 class="gw-status-tile__title gw-text-h3"><a class="gw-card__link" href="clients.html#cl-${app.anchor}-t">${app.name}</a></h4><p class="gw-text-caption gw-fg-muted">${!selected.enabled ? "Access paused on this endpoint" : link.status === "Connected" ? `${selected.services.length} services shared` : link.status === "Tunnel off" ? "Remote access is paused" : "Finish setup in the app"}</p></div>
          <div class="gw-status-tile__end">${status(label, label === "Connected" ? "success" : label === "Needs restart" || label === "Waiting for Claude Desktop" ? "warning" : "neutral")}</div>
        </li>`;
      }).join("");
    byId("ov-apps-step").dataset.state = !links.length || appsUnavailable || availability !== "ready" ? "todo" : links.every((link) => link.status === "Connected") ? "done" : "attention";
    const connect = byId("ov-connect-app");
    const unavailable = !selected?.services.length || !selected?.enabled || availability !== "ready" || appsUnavailable;
    connect.setAttribute("aria-disabled", String(unavailable));
    connect.setAttribute("aria-describedby", "ov-app-scope");
    if (unavailable) byId("ov-app-scope").textContent = !selected ? "Create an endpoint first." : !selected.enabled ? "Turn this endpoint on before connecting an app." : availability !== "ready" || appsUnavailable ? "Retry to load app status before connecting." : "Add a service to this endpoint first.";
  }

  function renderMap(rebuildEndpoints = true) {
    if (rebuildEndpoints) renderEndpoints();
    renderServices();
    renderApps();
    byId("ov-gateway-step").dataset.state = selected ? "done" : "todo";
    byId("ov-endpoint-details").hidden = !selected;
    byId("ov-paused").hidden = !selected || selected.enabled;
    if (selected) {
      byId("ov-endpoint-url").textContent = `http://127.0.0.1:7465/mcp/${selected.slug}`;
      const copy = doc.querySelector('[data-gw-copy-from="#ov-endpoint-url"]');
      copy.removeAttribute("data-copied");
      byId("ov-endpoint-enabled").checked = selected.enabled;
      byId("ov-endpoint-enabled").setAttribute("aria-disabled", String(availability !== "ready"));
      byId("ov-endpoint-enabled").setAttribute("aria-label", `Access through endpoint ${selected.slug}`);
      doc.querySelector('[for="ov-endpoint-enabled"]').textContent = selected.enabled ? "On" : "Off";
      byId("ov-access-status").textContent = availability !== "ready" ? "Access status unavailable" : selected.enabled ? "Access on" : "Access paused";
      byId("ov-access-hint").textContent = availability !== "ready" ? "Retry the gateway connection before changing access." : "Pause this endpoint without losing its settings.";
      byId("ov-manage-endpoint").href = `endpoint-detail.html?endpoint=${encodeURIComponent(selected.slug)}`;
    }
    byId("ov-next").hidden = !onboarding && !(selected?.preview && !selected.apps.length);
    if (!byId("ov-next").hidden) {
      byId("ov-next-title").textContent = !selected ? "Start with an endpoint" : !selected.services.length ? "Start with your first service" : !selected.apps.length ? "Next, connect your AI app" : "Finish setup in your AI app";
      byId("ov-next-description").textContent = !selected ? "Give your workspace a name using Add endpoint below. You will choose its services next." : !selected.services.length ? "Use Add service on the left. GateRook will help you review what to share." : !selected.apps.length ? "Your service is ready. Use Connect AI app on the right to review access." : "Complete installation or restart the app, then ask it what services are available through GateRook.";
    }
    doc.querySelectorAll("[data-ov-add-service]").forEach((button) => {
      button.setAttribute("aria-disabled", String(!selected || availability !== "ready"));
      button.setAttribute("aria-describedby", "ov-service-scope");
    });
    doc.querySelectorAll("[data-ov-new-endpoint]").forEach((button) => {
      button.setAttribute("aria-disabled", String(availability !== "ready"));
      button.setAttribute("aria-describedby", "ov-service-scope");
    });
    if (availability !== "ready") byId("ov-service-scope").textContent = "Retry the gateway connection before adding a service.";
    refreshIcons();
  }

  function serviceReview() {
    const ep = endpoints.find((item) => item.slug === byId("ov-service-endpoint").value);
    const error = !ep ? "Create an endpoint first." : ep.readonly ? "This endpoint is read-only. GitHub MCP has no declared read-only tools. Choose a development endpoint that allows approvals." : ep.env === "prod" ? "Review production services and permissions in the full endpoint screen before adding access." : !ep.enabled ? "Turn this endpoint on before adding a service." : ep.services.includes("github-mcp") ? "GitHub MCP is already shared through this endpoint." : "";
    byId("ov-service-error").hidden = !error;
    byId("ov-service-error").textContent = error;
    byId("ov-service-submit").setAttribute("aria-disabled", String(Boolean(error)));
    byId("ov-service-submit").setAttribute("aria-describedby", "ov-service-error");
    byId("ov-service-access").textContent = ep?.apps.length ? `Apps configured for ${ep.slug}: ${ep.apps.map((link) => apps[link.id].name).join(", ")}. They will be able to request these tools; each call needs your approval.` : "No AI apps use this endpoint yet. Connect one after adding the service.";
    return error ? null : ep;
  }

  function connectReview() {
    const id = byId("ov-connect-choice").value;
    const app = apps[id];
    const existing = selected.apps.find((item) => item.id === id);
    const error = existing ? `${app.name} already has a ${existing.status.toLowerCase()} setup for ${selected.slug}. Open All AI apps to manage it.` : "";
    byId("ov-connect-error").hidden = !error;
    byId("ov-connect-error").textContent = error;
    byId("ov-connect-submit").setAttribute("aria-disabled", String(Boolean(error)));
    byId("ov-connect-submit").setAttribute("aria-describedby", "ov-connect-error");
    byId("ov-connect-submit").textContent = `Connect ${app.name}`;
    byId("ov-connect-access").textContent = `Endpoint ${selected.slug} · ${selected.services.length} services`;
    byId("ov-connect-services").innerHTML = selected.services.map((name) => `<li class="gw-tag gw-tag--mono">${escape(name)}</li>`).join("");
    byId("ov-connect-rules").textContent = selected.readonly ? "Only the read-only tools enabled on this endpoint are available. Other tools remain off." : "Only tools enabled on this endpoint are available. Existing approval rules and limits still apply.";
    byId("ov-connect-method").textContent = id === "claude" ? "GateRook opens an extension for you to review and install in Claude Desktop. Opening it does not confirm a working connection." : id === "codex" ? "GateRook adds this endpoint to Codex configuration using its local credentials helper. No service password is included. Restart Codex to finish setup." : selected.env === "prod" ? "Production uses GateRook's local bridge. No bearer token is stored in Cursor configuration. Restart Cursor after reviewing and applying the configuration." : "The file-based setup adds this endpoint and an access token to Cursor configuration in plaintext. Service passwords stay in GateRook. Browser sign-in is also available in All AI apps.";
    return !error;
  }

  byId("ov-endpoints").addEventListener("change", (event) => {
    if (event.target.name !== "ov-endpoint") return;
    selected = endpoints.find((ep) => ep.slug === event.target.value);
    expanded = false;
    renderMap(false);
    announce(`Showing ${selected.services.length} services and ${selected.apps.length} AI apps for endpoint ${selected.slug}.`);
  });
  byId("ov-show-services").addEventListener("click", () => { expanded = !expanded; renderServices(); refreshIcons(); });
  byId("ov-endpoint-enabled").addEventListener("change", (event) => {
    if (!selected) return;
    selected.enabled = event.target.checked;
    renderMap();
    announce(`Endpoint ${selected.slug} is ${selected.enabled ? "on" : "off"}. Other endpoints are unchanged.`);
  });
  doc.addEventListener("click", (event) => {
    const add = event.target.closest("[data-ov-add-service]");
    if (add && add.getAttribute("aria-disabled") !== "true") {
      byId("ov-service-endpoint").innerHTML = endpoints.map((ep) => `<option value="${escape(ep.slug)}">${escape(ep.slug)}</option>`).join("");
      byId("ov-service-endpoint").value = selected.slug;
      byId("ov-database-link").href = state === "first-run" ? "connection-new.html?state=first-run" : "connection-new.html";
      serviceReview();
      openDialog("ov-add-service", add);
    }
    const create = event.target.closest("[data-ov-new-endpoint]");
    if (create && create.getAttribute("aria-disabled") !== "true") {
      byId("ov-endpoint-form").reset();
      byId("ov-new-name").setCustomValidity("");
      byId("ov-new-name").removeAttribute("aria-invalid");
      byId("ov-new-error").hidden = true;
      openDialog("ov-new-endpoint", create);
    }
    if (event.target.closest("[data-ov-retry]")) {
      availability = "ready";
      appsUnavailable = false;
      renderMap();
      announce("Sample connection data loaded. No external request was made.");
      byId("ov-map-title").setAttribute("tabindex", "-1");
      byId("ov-map-title").focus();
    }
  });
  byId("ov-service-endpoint").addEventListener("change", serviceReview);
  byId("ov-service-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const ep = serviceReview();
    if (!ep) return;
    ep.services.unshift("github-mcp");
    selected = ep;
    expanded = false;
    byId("ov-add-service")._gwReturn = doc.querySelector("#ov-services-step .gw-card__footer [data-ov-add-service]");
    byId("ov-add-service").close();
    renderMap();
    doc.querySelector("#ov-services-step .gw-card__footer [data-ov-add-service]").focus();
    announce(`GitHub MCP added to ${ep.slug}. All 26 tools require approval.`);
    toast("GitHub MCP added to the preview", `26 tools on ${ep.slug}, each requiring approval. ${ep.apps.length ? "Existing apps can request access." : "Next, connect your AI app."}`);
  });
  byId("ov-new-name").addEventListener("input", () => {
    byId("ov-new-name").setCustomValidity("");
    byId("ov-new-name").removeAttribute("aria-invalid");
    byId("ov-new-error").hidden = true;
  });
  byId("ov-endpoint-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const input = byId("ov-new-name");
    const slug = input.value;
    if (endpoints.some((ep) => ep.slug === slug)) {
      input.setCustomValidity("An endpoint with this name already exists.");
      input.setAttribute("aria-invalid", "true");
      byId("ov-new-error").hidden = false;
      byId("ov-new-error").textContent = "This name is already in use. Choose a different endpoint name.";
      input.focus();
      input.reportValidity();
      return;
    }
    selected = { slug, name: byId("ov-new-description-input").value.trim() || "Your new workspace", env: "dev", enabled: true, services: [], apps: [], preview: true };
    endpoints.push(selected);
    byId("ov-new-endpoint").close();
    renderMap();
    announce(`Endpoint ${slug} created. Add a service next.`);
    toast("Endpoint created in the preview", `${slug} starts empty. Add a service from the left side of the map.`);
  });
  byId("ov-connect-app").addEventListener("click", (event) => {
    if (event.currentTarget.getAttribute("aria-disabled") === "true") return;
    const available = ["cursor", "claude", "codex"].find((id) => !selected.apps.some((item) => item.id === id));
    byId("ov-connect-choice").value = available || "cursor";
    connectReview();
    openDialog("ov-connect", event.currentTarget);
  });
  byId("ov-connect-choice").addEventListener("change", connectReview);
  byId("ov-connect-form").addEventListener("submit", (event) => {
    event.preventDefault();
    if (!connectReview()) return;
    const id = byId("ov-connect-choice").value;
    selected.apps.push({ id, status: id === "claude" ? "Waiting for Claude Desktop" : "Needs restart" });
    byId("ov-connect").close();
    renderMap();
    announce(`${apps[id].name} setup is pending for ${selected.slug}. Finish setup in the app.`);
    toast("App setup preview added", `${apps[id].name} is waiting for you to finish setup. A working connection has not been confirmed.`);
  });
  byId("ov-manage-endpoint").addEventListener("click", (event) => {
    if (!selected || selected.slug === "dev" && !selected.preview) return;
    event.preventDefault();
    byId("ov-created-summary").textContent = `${selected.slug}: ${selected.services.length} services, ${selected.apps.length} apps. ${selected.readonly ? "This endpoint permits read-only tools only." : selected.env === "prod" ? "Production access uses restricted tools and the local bridge." : "This endpoint exists only in this Overview preview."}`;
    byId("ov-created-summary").nextElementSibling.textContent = "The full endpoint reference demonstrates dev. Changes made in this Overview preview do not carry over to other reference screens.";
    openDialog("ov-created-details", event.currentTarget);
  });

  // The compact alert and exact-action cards always agree with the live countdowns.
  function syncApprovals() {
    const count = byId("ov-wait-list")?.children.length || 0;
    byId("ov-attention").hidden = !count;
    byId("ov-attention-count").textContent = `${count} ${count === 1 ? "request needs" : "requests need"} your approval`;
    byId("ov-wait-none").hidden = Boolean(count) || onboarding;
    if (byId("ov-wait")) byId("ov-wait").hidden = !count;
    if (byId("ov-wait-count")) byId("ov-wait-count").textContent = count;
    byId("ov-wait-list")?.setAttribute("aria-label", `Waiting for approval, ${count} requests`);
    doc.querySelectorAll(".gw-titlebar .gw-counter, .gw-nav__counter, #cmdk .gw-counter").forEach((counter) => {
      counter.textContent = count;
      counter.hidden = !count;
      counter.setAttribute("aria-label", `${count} waiting`);
    });
    byId("gw-titlebar-approvals-tip").textContent = `Approvals, ${count ? `${count} waiting` : "none waiting"}`;
  }
  if (onboarding) {
    byId("ov-wait").remove();
    const keep = state === "setup" ? ["cmdk-o-dev-db", "cmdk-o-ep-dev"] : selected ? ["cmdk-o-ep-dev"] : [];
    ["cmdk-g-connections", "cmdk-g-endpoints"].forEach((id) => {
      const group = doc.querySelector(`#cmdk [aria-labelledby="${id}"]`);
      group?.querySelectorAll(".gw-menu__item").forEach((option) => { if (!keep.includes(option.id)) option.remove(); });
      if (group && !group.querySelector(".gw-menu__item")) group.remove();
    });
    if (!selected) ["cmdk-o-copy-dev", "cmdk-o-issue-token"].forEach((id) => byId(id)?.remove());
  }
  doc.addEventListener("gw-countdown-end", (event) => {
    const item = event.target.closest("#ov-wait-list > li");
    if (!item) return;
    const hadFocus = item.contains(doc.activeElement);
    item.remove();
    syncApprovals();
    if (hadFocus) (byId("ov-wait-list")?.querySelector("a") || byId("ov-map-title")).focus();
  });
  renderMap();
  syncApprovals();
})();
