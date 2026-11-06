/* Independent network Overview reference. The SVG mirrors the native controls;
   all edits are in-memory fixtures and never configure a real service or AI app. */
(function () {
  "use strict";
  const doc = document;
  const $ = (id) => doc.getElementById(id);
  const params = new URLSearchParams(location.search);
  const state = params.get("state") || "in-use";
  const onboarding = state === "first-run" || state === "setup";
  let availability = ["loading", "error"].includes(state) ? state : "ready";
  const services = {
    "dev-db": { name: "PostgreSQL", env: "dev", icon: "database", mark: "postgresql", health: "Healthy", tone: "success" },
    "orders-mongo": { name: "MongoDB", env: "dev", icon: "database", mark: "mongodb", health: "Healthy", tone: "success" },
    "cache-redis": { name: "Redis", env: "dev", icon: "layers", mark: "redis", health: "Slow · 1.8 s", tone: "warning" },
    "dev-vm": { name: "SSH", env: "dev", icon: "terminal", health: "Healthy", tone: "success" },
    "app-logs": { name: "Loki", env: "dev", icon: "logs", health: "Healthy", tone: "success" },
    "dev-cluster": { name: "Kubernetes", env: "dev", icon: "boxes", health: "Healthy", tone: "success" },
    "aws-stage": { name: "AWS", env: "stage", icon: "cloud", health: "Healthy", tone: "success" },
    "billing-api": { name: "HTTP API", env: "stage", icon: "globe", health: "Unreachable", tone: "danger" },
    "stage-db": { name: "PostgreSQL", env: "stage", icon: "database", mark: "postgresql", health: "Healthy", tone: "success" },
    "prod-replica": { name: "PostgreSQL", env: "prod", icon: "database", mark: "postgresql", health: "Healthy", tone: "success" },
    "prod-logs": { name: "GCP logs", env: "prod", icon: "logs", health: "Healthy", tone: "success" },
    "prod-vm": { name: "SSH runbooks", env: "prod", icon: "terminal", health: "Healthy", tone: "success" },
    "github-mcp": { name: "GitHub MCP", env: "dev", icon: "plug", mcp: true, health: "Healthy", tone: "success" },
    "docs-mcp": { name: "Docs MCP", env: "dev", icon: "book-open", mcp: true, health: "Healthy", tone: "success" }
  };
  const apps = {
    claude: { name: "Claude Desktop", mark: "CD", tone: "claude", anchor: "cd" },
    codex: { name: "Codex", mark: "Cx", tone: "codex", anchor: "cx" },
    cursor: { name: "Cursor", mark: "Cu", tone: "cursor", anchor: "cu" },
    antigravity: { name: "Antigravity", mark: "Ag", tone: "antigravity", anchor: "ag" },
    web: { name: "claude.ai", mark: "Cl", tone: "claude", anchor: "web" }
  };
  let endpoints = [
    { slug: "dev", env: "dev", enabled: true, services: ["dev-db", "orders-mongo", "cache-redis", "app-logs", "dev-vm", "dev-cluster", "aws-stage", "billing-api", "docs-mcp"], apps: [{ id: "claude", status: "Connected" }, { id: "codex", status: "Connected" }] },
    { slug: "stage-readonly", env: "stage", readonly: true, enabled: true, services: ["stage-db", "aws-stage", "billing-api"], apps: [{ id: "web", status: "Tunnel off" }] },
    { slug: "incident-prod", env: "prod", enabled: true, services: ["prod-replica", "prod-logs", "prod-vm"], apps: [{ id: "claude", status: "Connected" }] }
  ];
  if (onboarding) endpoints = params.get("endpoints") === "none" ? [] : [{ slug: "dev", env: "dev", enabled: true, services: state === "setup" ? ["dev-db"] : [], apps: [] }];
  let selected = endpoints.find((ep) => ep.slug === params.get("endpoint")) || endpoints[0];
  let selectedNode = null;
  let hoverNode = null;
  let trace = null;
  let frame = null;
  let expanded = false;
  let problemsOnly = false;
  let pulseTimer = null;
  const escape = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
  const icon = (name, size = "") => `<i class="gw-icon ${size}" data-lucide="${name}"></i>`;
  const status = (text, tone = "neutral") => `<span class="gw-status gw-status--${tone}"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">${escape(text)}</span></span>`;
  const refreshIcons = () => window.lucide?.createIcons({ attrs: { "aria-hidden": "true", focusable: "false" } });
  const announce = (message) => { $("nm-live").textContent = message; };
  const nodeElement = (key) => Array.from(doc.querySelectorAll("[data-nm-node]")).find((el) => el.dataset.nmNode === key);
  const connectionOn = (id) => services[id].enabled !== false;
  const unreachable = (ep) => availability === "ready" && ep?.enabled ? ep.services.filter((id) => connectionOn(id) && services[id].health === "Unreachable") : [];
  const searchQuery = () => $("nm-service-search").value.trim().toLocaleLowerCase();
  const shownServices = () => (problemsOnly ? unreachable(selected) : selected?.services || []).filter((id) => `${services[id].name} ${id}`.toLocaleLowerCase().includes(searchQuery()));
  const effectiveOn = (id) => selected?.enabled && connectionOn(id);
  function openDialog(id, trigger) {
    $(id)._gwReturn = trigger;
    window.gwShowDialog($(id));
    refreshIcons();
  }
  function setUnavailable(el, reason) {
    el.setAttribute("aria-disabled", String(Boolean(reason)));
    if (reason) el.setAttribute("title", reason); else el.removeAttribute("title");
  }

  function endpointChoices() {
    $("nm-endpoints").innerHTML = endpoints.length ? endpoints.map((ep, index) => {
      const failed = unreachable(ep);
      return `<div class="gw-setup-map__network-endpoint">
      <label class="gw-choice-card gw-choice-card--compact" data-nm-port="${escape(ep.slug)}">
        <input class="gw-choice-card__input" type="radio" name="nm-endpoint" value="${escape(ep.slug)}" ${ep === selected ? "checked" : ""} aria-labelledby="nm-ep-${index}" aria-describedby="nm-ep-${index}-meta nm-endpoint-help${failed.length ? ` nm-health-tip-${index}` : ""}">
        <span class="gw-setup-map__network-port" data-side="in" aria-hidden="true"></span><span class="gw-setup-map__network-port" data-side="out" aria-hidden="true"></span>
        <span class="gw-choice-card__title" id="nm-ep-${index}"><span class="gw-mono gw-truncate" title="${escape(ep.slug)}">${escape(ep.slug)}</span></span>
        <span class="gw-choice-card__description">${ep.services.length} ${ep.services.length === 1 ? "service" : "services"} · ${ep.apps.length} ${ep.apps.length === 1 ? "app" : "apps"}</span>
        <span class="gw-choice-card__meta" id="nm-ep-${index}-meta"><span class="gw-env gw-env--sm gw-env--${ep.env}">${ep.env}</span> · ${ep.enabled ? ep.readonly ? "Read only" : ep.env === "prod" ? "Restricted tools" : "Changes need approval" : "Access paused"}</span>
      </label>
      ${failed.length ? `<span class="gw-tooltip gw-tooltip--end gw-setup-map__network-health"><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" data-nm-problems="${escape(ep.slug)}" aria-label="Show ${failed.length} unreachable ${failed.length === 1 ? "connection" : "connections"} on ${escape(ep.slug)}" aria-describedby="nm-health-tip-${index}" aria-controls="nm-services nm-mcp-cluster" aria-pressed="${ep === selected && problemsOnly}"><span class="gw-badge gw-badge--danger">${icon("unplug")}${failed.length}</span></button><span class="gw-tooltip__bubble" role="tooltip" id="nm-health-tip-${index}">${failed.length} enabled ${failed.length === 1 ? "connection is" : "connections are"} unreachable: ${failed.map(escape).join(", ")}. Show them on the left.</span></span>` : ""}
      </div>`;
    }).join("") : '<p class="gw-text-small gw-fg-muted">No endpoints yet. Add one to start sharing.</p>';
  }
  function serviceNode(id) {
    const item = services[id];
    const on = effectiveOn(id);
    const failed = on && item.health === "Unreachable";
    const label = !selected.enabled ? "Endpoint off" : !connectionOn(id) ? "Connection off" : item.mcp && item.health === "Healthy" ? `Connected to ${selected.slug}` : item.health;
    const hint = !selected.enabled ? `Endpoint ${selected.slug} is off. Turn it on before changing connection access. The saved connection setting is ${connectionOn(id) ? "on" : "off"}.` : `${on ? "Turn off" : "Turn on"} ${id} across all endpoints. Saved settings and tool permissions are kept.`;
    return `<div class="gw-card gw-setup-map__network-service${failed ? " gw-card--invalid" : !on ? " gw-card--muted" : ""}" data-selected="${selectedNode === `service:${id}`}" data-nm-service="${id}">
      <button class="gw-card--interactive gw-setup-map__network-node" type="button" data-nm-node="service:${id}" aria-pressed="${selectedNode === `service:${id}`}" aria-controls="nm-detail">
      ${item.mark ? `<img class="gw-setup-map__network-mark" src="../assets/integrations/${item.mark}.svg" alt="">` : `<span class="gw-icon-tile gw-icon-tile--neutral" aria-hidden="true">${icon(item.icon)}</span>`}
      <span class="gw-setup-map__network-label"><strong class="gw-text-h3">${item.name}</strong><span class="gw-text-caption gw-mono">${id}</span>${status(label, on ? item.tone : "neutral")}</span>
      </button><div class="gw-setup-map__network-toggle"><span class="gw-text-caption gw-fg-muted">Access ${on ? "on" : "off"}</span><span class="gw-tooltip gw-tooltip--end"><label class="gw-switch"><input class="gw-switch__input" type="checkbox" role="switch" data-nm-connection="${id}" aria-label="Connection ${id}, all endpoints" aria-describedby="nm-switch-tip-${id}" ${on ? "checked" : ""} ${!selected.enabled ? 'aria-disabled="true"' : ""}><span class="gw-switch__control" aria-hidden="true"></span></label><span class="gw-tooltip__bubble" role="tooltip" id="nm-switch-tip-${id}">${escape(hint)}</span></span></div>
    </div>`;
  }
  function renderServices() {
    const items = shownServices();
    const native = items.filter((id) => !services[id].mcp);
    const mcp = items.filter((id) => services[id].mcp);
    const searching = Boolean(searchQuery());
    $("nm-services-scope").textContent = availability !== "ready" ? "Connection data unavailable" : selected ? `${searching ? `${items.length} of ${problemsOnly ? unreachable(selected).length : selected.services.length}` : items.length} ${problemsOnly ? "unreachable" : "services"} on ${selected.slug}` : "Start with an endpoint";
    $("nm-service-search").disabled = !selected || availability !== "ready";
    $("nm-problem-filter").hidden = !problemsOnly;
    $("nm-services").setAttribute("aria-label", selected ? `${problemsOnly ? "Unreachable services" : "Services"} shared through ${selected.slug}` : "No services shared yet");
    $("nm-services").innerHTML = availability !== "ready" ? `<li class="gw-card gw-card--muted"><div class="gw-card__body gw-stack gw-stack--sm">${icon(availability === "loading" ? "loader-circle" : "unplug")}<p class="gw-text-small">${availability === "loading" ? "Loading your services…" : "Could not load services"}</p><p class="gw-text-caption gw-fg-muted">Your saved settings are unchanged.</p></div></li>` : native.slice(0, expanded || searching || problemsOnly ? native.length : 4).map((id) => `<li>${serviceNode(id)}</li>`).join("") || (searching ? items.length ? "" : '<li class="gw-card"><div class="gw-card__body gw-stack gw-stack--sm"><p class="gw-text-small">No matching connections</p><p class="gw-text-caption gw-fg-muted">Try a service name or connection name.</p><button class="gw-btn gw-btn--sm" type="button" data-gw-search-clear="#nm-service-search">Clear search</button></div></li>' : '<li class="gw-card gw-card--dashed"><div class="gw-card__body"><p class="gw-text-small">No databases or APIs shared yet.</p><p class="gw-text-caption gw-fg-muted">Add a service below.</p></div></li>');
    $("nm-services").hidden = availability === "ready" && searching && !native.length && mcp.length > 0;
    $("nm-show-services").hidden = searching || problemsOnly || native.length <= 4 || availability !== "ready";
    $("nm-show-services").textContent = expanded ? "Show fewer" : "Show all";
    $("nm-show-services").setAttribute("aria-expanded", String(expanded));
    const unconnected = !selected?.services.includes("github-mcp") && !problemsOnly && !searching;
    const notConnected = availability !== "ready" ? "Status unavailable" : selected ? `Not connected to ${selected.slug}` : "Not connected to an endpoint";
    $("nm-mcp-service").innerHTML = (availability === "ready" ? mcp.map(serviceNode).join("") : "") + (unconnected ? `<div class="gw-card gw-card--muted gw-setup-map__network-app"><div class="gw-setup-map__network-node"><span class="gw-icon-tile gw-icon-tile--neutral" aria-hidden="true">${icon("plug")}</span><span class="gw-setup-map__network-label"><strong class="gw-text-h3">GitHub MCP</strong><span class="gw-text-caption gw-fg-muted">Installed</span>${status(notConnected)}</span></div><button class="gw-btn gw-btn--sm" type="button" data-nm-review-installed aria-label="Connect GitHub MCP${selected ? ` to endpoint ${escape(selected.slug)}` : ""}">Connect</button></div>` : "");
    $("nm-mcp-hint").textContent = selected ? `Endpoint: ${selected.slug}` : "Choose an endpoint to connect a service.";
    $("nm-mcp-cluster").hidden = (problemsOnly || searching) && mcp.length === 0;
  }
  function renderApps() {
    const shown = selected?.apps.some((link) => link.id === "web") ? ["web", "claude", "codex", "cursor", "antigravity"] : ["claude", "codex", "cursor", "antigravity"];
    $("nm-apps-scope").textContent = selected ? `Access through ${selected.slug}` : "Connect an app after adding a service";
    $("nm-apps").setAttribute("aria-label", selected ? `AI app access through ${selected.slug}` : "AI apps you can connect");
    $("nm-apps").innerHTML = shown.map((id) => {
      const item = apps[id];
      const link = selected?.apps.find((entry) => entry.id === id);
      const label = availability !== "ready" ? "Status unavailable" : link ? selected.enabled ? link.status : "Endpoint off" : id === "antigravity" ? "Not installed" : "Not connected";
      const tone = label === "Connected" ? "success" : ["Needs restart", "Waiting for Claude Desktop"].includes(label) ? "warning" : "neutral";
      const action = !link && id === "antigravity" ? `<a class="gw-btn gw-btn--sm" href="clients.html#cl-ag-t" aria-label="Install Antigravity — setup instructions">Install</a>` : !link && ["claude", "codex", "cursor"].includes(id) ? `<button class="gw-btn gw-btn--sm" type="button" data-nm-connect-app="${id}" aria-label="Connect ${item.name}">Connect</button>` : "";
      return `<li class="gw-card gw-setup-map__network-app" data-selected="${selectedNode === `app:${id}`}" data-nm-app="${id}"><button class="gw-card--interactive gw-setup-map__network-node" type="button" data-nm-node="app:${id}" aria-pressed="${selectedNode === `app:${id}`}" aria-controls="nm-detail"><span class="gw-client-mark gw-client-mark--${item.tone}" aria-hidden="true">${item.mark}</span><span class="gw-setup-map__network-label"><strong class="gw-text-h3">${item.name}</strong>${status(label, tone)}</span></button>${action}</li>`;
    }).join("");
  }

  function renderDetail() {
    const [kind, id] = (selectedNode || "").split(":");
    const ep = selected;
    let title = ep ? `Access through ${ep.slug}` : "Create your first endpoint";
    let description = ep ? `${ep.services.length} services · ${ep.apps.length} configured AI apps. ${ep.description || "Select a node above to explore its access."}` : "An endpoint groups the services and permissions you share with an AI app.";
    let href = "endpoints.html";
    let action = "Manage access";
    let rules = ep?.readonly ? "This endpoint allows read-only tools only. Every call is recorded in Activity." : "Changes require your approval by default. Explicit tool exceptions remain under your control.";
    if (kind === "service") {
      title = `${services[id].name} · ${id}`;
      description = !connectionOn(id) ? "Connection off across all endpoints. Saved settings and tool permissions are kept; turn access on to use it again." : !ep.enabled ? `Endpoint ${ep.slug} is off. This connection keeps its saved setting for other endpoints.` : `${services[id].health}. Shared through ${ep.slug} with ${ep.apps.map((link) => apps[link.id].name).join(", ") || "no AI apps yet"}.`;
      href = services[id].mcp ? "connectors.html#mcp-servers" : `connections.html?q=${encodeURIComponent(id)}`;
      action = "Manage service";
      if (id === "github-mcp") rules = "All 26 tools require approval. This server does not declare read-only tools; nothing runs without a separate decision.";
      if (id === "docs-mcp") rules = "Example documentation service. Its tools require approval; credentials stay in GateRook and calls are recorded in Activity.";
    } else if (kind === "app") {
      const link = ep?.apps.find((entry) => entry.id === id);
      title = apps[id].name;
      description = link ? `${link.status} on ${ep.slug}. ${ep.enabled ? `${ep.services.length} services shared through this endpoint.` : "Access is paused on this endpoint."}` : id === "antigravity" ? "Not installed. Install the app, then connect it to an endpoint." : !ep ? "Create an endpoint and add a service before connecting this app." : !ep.services.length ? "Add a service to this endpoint before connecting this app." : !ep.enabled ? "Turn this endpoint on before connecting the app." : "Not connected to this endpoint. Choose Connect an AI app to review and add access.";
      href = `clients.html#cl-${apps[id].anchor}-t`;
      action = id === "antigravity" ? "How to install" : "Manage app";
    }
    if (ep && !ep.enabled) rules = "All tools are off on this endpoint. AI apps can still see them and ask you to enable access. Other endpoints keep their own settings.";
    if (availability !== "ready") {
      description = availability === "loading" ? "Connection status is loading. Current service and app access has not been confirmed." : "Connection status is unavailable. Current service and app access has not been confirmed.";
      rules = "Saved settings are unchanged. Retry the gateway connection before changing access.";
    }
    $("nm-detail-title").textContent = title;
    $("nm-detail-description").textContent = description;
    $("nm-detail-rules").textContent = rules;
    $("nm-manage").href = href;
    $("nm-manage").innerHTML = action + icon("arrow-up-right", "gw-icon--sm");
    $("nm-endpoint-controls").hidden = !ep;
    $("nm-pause-hint").hidden = !ep;
    if (ep) {
      $("nm-endpoint-url").textContent = `http://127.0.0.1:7465/mcp/${ep.slug}`;
      doc.querySelector('[data-gw-copy-from="#nm-endpoint-url"]').removeAttribute("data-copied");
      $("nm-endpoint-enabled").checked = ep.enabled;
      $("nm-endpoint-enabled").setAttribute("aria-label", `Access through endpoint ${ep.slug}`);
      doc.querySelector('[for="nm-endpoint-enabled"]').textContent = availability !== "ready" ? "Status unavailable" : ep.enabled ? "Endpoint on" : "Endpoint off";
      setUnavailable($("nm-endpoint-enabled"), availability !== "ready" ? "Retry the gateway connection before changing access." : "");
    }
    const traceReason = !ep?.enabled ? "Turn on an endpoint first." : !ep.services.length ? "Add a service first." : availability !== "ready" ? "Retry the gateway connection first." : !ep.services.some(connectionOn) || kind === "service" && !connectionOn(id) ? "Turn on the connection before showing its request path." : !ep.apps.some((link) => link.status === "Connected") ? "A connected AI app is needed to show a request path." : kind === "app" && !ep.apps.some((link) => link.id === id && link.status === "Connected") ? "This app is not connected to the endpoint." : "";
    setUnavailable($("nm-trace"), traceReason);
    if (!traceReason && !shownServices().some(connectionOn)) setUnavailable($("nm-trace"), "Clear the connection filters to show an available request path.");
    $("nm-trace").setAttribute("aria-describedby", "nm-detail-description");
  }

  function drawWires() {
    frame = null;
    const svg = $("nm-wires");
    svg.replaceChildren();
    if (!selected || availability !== "ready" || !matchMedia("(min-width: 1280px)").matches) return;
    const root = $("nm-network").getBoundingClientRect();
    const port = doc.querySelector('input[name="nm-endpoint"]:checked')?.closest("label").getBoundingClientRect();
    if (!port) return;
    svg.setAttribute("viewBox", `0 0 ${root.width} ${root.height}`);
    const addPath = (key, pending) => {
      const element = nodeElement(key);
      if (!element) return;
      const node = (element.closest("[data-nm-service]") || element).getBoundingClientRect();
      const left = key.startsWith("service:");
      const x1 = (left ? node.right : port.right) - root.left;
      const y1 = (left ? (node.top + node.bottom) : (port.top + port.bottom)) / 2 - root.top;
      const x2 = (left ? port.left : node.left) - root.left;
      const y2 = (left ? (port.top + port.bottom) : (node.top + node.bottom)) / 2 - root.top;
      const bend = Math.max(0, x2 - x1) * 0.55;
      const path = doc.createElementNS("http://www.w3.org/2000/svg", "path");
      path.setAttribute("class", "gw-setup-map__network-path");
      path.setAttribute("d", `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`);
      path.dataset.request = `M ${x2} ${y2} C ${x2 - bend} ${y2}, ${x1 + bend} ${y1}, ${x1} ${y1}`;
      path.dataset.node = key;
      path.dataset.pending = String(pending || !selected.enabled);
      path.dataset.muted = String(!selected.enabled || key.startsWith("service:") && !connectionOn(key.slice(8)));
      svg.append(path);
    };
    selected.services.forEach((id) => addPath(`service:${id}`, !connectionOn(id)));
    selected.apps.forEach((link) => addPath(`app:${link.id}`, link.status !== "Connected"));
    highlightWires();
  }
  function scheduleWires() { if (!frame) frame = requestAnimationFrame(drawWires); }
  function highlightWires() {
    const key = hoverNode || selectedNode;
    doc.querySelectorAll("#nm-wires path").forEach((path) => {
      const connectedApp = selected?.apps.find((link) => `app:${link.id}` === key && link.status === "Connected");
      const connectedBranch = key?.startsWith("service:") && selected?.apps.some((link) => `app:${link.id}` === path.dataset.node && link.status === "Connected");
      const active = trace ? path.dataset.node === `service:${trace.service}` || path.dataset.node === `app:${trace.app}` : key === path.dataset.node || connectedBranch || connectedApp && path.dataset.node.startsWith("service:");
      path.dataset.active = String(Boolean(active) && selected?.enabled && path.dataset.muted !== "true");
    });
  }
  function stopPulse() {
    clearTimeout(pulseTimer);
    doc.querySelectorAll("#nm-wires .gw-setup-map__network-pulse").forEach((pulse) => pulse.remove());
  }
  function animateTrace() {
    stopPulse();
    if (!trace || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const paths = Array.from(doc.querySelectorAll("#nm-wires path"));
    const app = paths.find((path) => path.dataset.node === `app:${trace.app}`);
    const service = paths.find((path) => path.dataset.node === `service:${trace.service}`);
    if (!app || !service) return;
    const circle = doc.createElementNS("http://www.w3.org/2000/svg", "circle");
    circle.setAttribute("class", "gw-setup-map__network-pulse");
    const motion = doc.createElementNS("http://www.w3.org/2000/svg", "animateMotion");
    motion.setAttribute("path", app.dataset.request + " " + service.dataset.request.replace(/^M /, "L "));
    const duration = parseFloat(getComputedStyle($("nm-network")).getPropertyValue("--gw-motion-duration-slow")) * 6;
    motion.setAttribute("dur", `${duration}ms`);
    motion.setAttribute("begin", "indefinite");
    motion.setAttribute("repeatCount", "1");
    circle.append(motion);
    $("nm-wires").append(circle);
    motion.addEventListener("endEvent", () => circle.remove(), { once: true });
    motion.beginElement();
    pulseTimer = setTimeout(() => circle.remove(), duration);
  }
  function clearTrace() { stopPulse(); trace = null; $("nm-trace-result").hidden = true; highlightWires(); }
  function render(rebuildChoices = true) {
    if (rebuildChoices) endpointChoices();
    renderServices();
    renderApps();
    renderDetail();
    $("nm-protection-status").textContent = availability !== "ready" ? "Status unavailable" : selected && !selected.enabled ? "Access paused" : "Protected access";
    $("nm-protection-icon").className = availability === "ready" && (!selected || selected.enabled) ? "gw-fg-success" : "gw-fg-muted";
    $("nm-protection-icon").innerHTML = icon(availability !== "ready" ? "shield-question" : selected && !selected.enabled ? "shield-minus" : "shield-check");
    $("nm-protection-summary").textContent = availability !== "ready" ? "Reconnect to check protection status." : "Credentials stay local. Access checked.";
    const missing = availability !== "ready" || !selected || !selected.services.length || !selected.apps.length || !selected.enabled;
    $("nm-notice").hidden = !missing;
    $("nm-retry").hidden = availability === "ready";
    $("nm-notice-title").textContent = availability !== "ready" ? availability === "loading" ? "Loading your connections" : "Could not reach the local gateway" : !selected ? "Start with an endpoint" : !selected.enabled ? "This endpoint is paused" : !selected.services.length ? "Add your first service" : "Next, connect your AI app";
    $("nm-notice-text").textContent = availability !== "ready" ? "Saved settings are unchanged. Retry to load the sample map." : !selected ? "Use Add endpoint in the middle. You will choose its services next." : !selected.enabled ? "Its configured lines are dashed. Turn access on in the details below to use these services." : !selected.services.length ? "Click an add node on the left to choose an MCP server, database or API." : "Click an AI app on the right and review the access you want to share.";
    doc.querySelectorAll("[data-nm-add-service], [data-nm-review-installed]").forEach((button) => setUnavailable(button, availability !== "ready" ? "Retry the gateway connection first." : !selected ? "Create an endpoint first." : ""));
    doc.querySelectorAll("[data-nm-new-endpoint]").forEach((button) => setUnavailable(button, availability !== "ready" ? "Retry the gateway connection first." : ""));
    setUnavailable($("nm-connect-trigger"), availability !== "ready" ? "Retry the gateway connection first." : !selected?.enabled ? "Turn on an endpoint first." : !selected.services.length ? "Add a service to this endpoint first." : "");
    doc.querySelectorAll("[data-nm-connect-app]").forEach((button) => setUnavailable(button, $("nm-connect-trigger").getAttribute("title") || ""));
    refreshIcons();
    scheduleWires();
  }

  function serviceReview() {
    const ep = endpoints.find((item) => item.slug === $("nm-service-endpoint").value);
    const error = !ep ? "Create an endpoint first." : ep.readonly ? "This endpoint is read-only. GitHub MCP has no declared read-only tools. Choose an endpoint that permits approvals." : ep.env === "prod" ? "Review production services in the full endpoint screen before adding access." : !ep.enabled ? "Turn on this endpoint first." : ep.services.includes("github-mcp") ? "GitHub MCP is already shared through this endpoint." : "";
    $("nm-service-error").hidden = !error;
    $("nm-service-error").textContent = error;
    setUnavailable($("nm-service-submit"), error);
    $("nm-service-submit").setAttribute("aria-describedby", "nm-service-error");
    $("nm-service-access").textContent = ep?.apps.length ? `Apps configured for ${ep.slug}: ${ep.apps.map((link) => apps[link.id].name).join(", ")}. Each tool call will need your approval.` : "No AI apps use this endpoint yet. Connect one after adding the service.";
    return error ? null : ep;
  }
  function openService(trigger) {
    if (!selected || availability !== "ready") return;
    $("nm-service-endpoint").innerHTML = endpoints.map((ep) => `<option value="${escape(ep.slug)}">${escape(ep.slug)}</option>`).join("");
    $("nm-service-endpoint").value = selected.slug;
    $("nm-database-link").href = onboarding ? "connection-new.html?state=first-run" : "connection-new.html";
    serviceReview();
    openDialog("nm-add-service", trigger);
  }
  function openServicePicker(trigger) {
    if (!selected || availability !== "ready") return;
    $("nm-picker-endpoint").textContent = `Selected endpoint: ${selected.slug}. Review access after choosing a service.`;
    $("nm-database-link").href = onboarding ? "connection-new.html?state=first-run" : "connection-new.html";
    openDialog("nm-service-picker", trigger);
  }
  function connectReview() {
    const id = $("nm-connect-choice").value;
    const app = apps[id];
    const existing = selected.apps.find((link) => link.id === id);
    const error = existing ? `${app.name} already has a ${existing.status.toLowerCase()} setup on ${selected.slug}. Manage it in All AI apps.` : "";
    $("nm-connect-error").hidden = !error;
    $("nm-connect-error").textContent = error;
    $("nm-connect-submit").textContent = `Connect ${app.name}`;
    setUnavailable($("nm-connect-submit"), error);
    $("nm-connect-submit").setAttribute("aria-describedby", "nm-connect-error");
    $("nm-connect-access").textContent = `Endpoint ${selected.slug} · ${selected.services.length} services`;
    $("nm-connect-services").innerHTML = selected.services.map((id) => `<li class="gw-tag gw-tag--mono">${id}</li>`).join("");
    $("nm-connect-rules").textContent = selected.readonly ? "Only the read-only tools enabled on this endpoint are shared." : "Existing tool permissions, approval rules and limits still apply.";
    $("nm-connect-method").textContent = id === "claude" ? "Review and install the extension in Claude Desktop. Opening it does not confirm a working connection." : id === "codex" ? "The setup uses GateRook's local credentials helper. No service password is stored in Codex configuration. Restart Codex after applying the configuration." : selected.env === "prod" ? "Production uses the local bridge. No bearer token is stored in Cursor configuration. Restart Cursor to finish setup." : "File-based setup stores an access token in Cursor configuration in plaintext. Service passwords stay in GateRook. Browser sign-in is also available in All AI apps.";
    return !error;
  }
  function openConnect(trigger, id) {
    if (!selected?.enabled || !selected.services.length || availability !== "ready") return;
    $("nm-connect-choice").value = id || ["claude", "codex", "cursor"].find((key) => !selected.apps.some((link) => link.id === key)) || "cursor";
    connectReview();
    openDialog("nm-connect", trigger);
  }

  $("nm-endpoints").addEventListener("change", (event) => {
    if (event.target.name !== "nm-endpoint") return;
    selected = endpoints.find((ep) => ep.slug === event.target.value);
    selectedNode = hoverNode = null;
    expanded = false;
    problemsOnly = false;
    clearTrace();
    render(false);
    doc.querySelectorAll("[data-nm-problems]").forEach((button) => button.setAttribute("aria-pressed", "false"));
    announce(`${selected.slug}: ${selected.services.length} services and ${selected.apps.length} configured AI apps.`);
  });
  $("nm-show-services").addEventListener("click", () => {
    expanded = !expanded;
    selectedNode = hoverNode = null;
    clearTrace();
    render(false);
    announce(expanded ? "All endpoint services are shown." : "Showing the first four services. Expand the list to see every service.");
  });
  $("nm-service-search").addEventListener("input", () => {
    selectedNode = hoverNode = null;
    clearTrace();
    render(false);
    announce(`${shownServices().length} matching connections${selected ? ` on ${selected.slug}` : ""}${problemsOnly ? ", filtered to unreachable connections" : ""}.`);
  });
  $("nm-clear-problems").addEventListener("click", () => {
    problemsOnly = false;
    $("nm-service-search").value = "";
    expanded = true;
    clearTrace();
    render();
    doc.querySelector('input[name="nm-endpoint"]:checked').focus();
    announce(`Showing all ${selected.services.length} services on ${selected.slug}.`);
  });
  $("nm-network").addEventListener("change", (event) => {
    const id = event.target.dataset.nmConnection;
    if (!id || !selected?.enabled || availability !== "ready") return;
    services[id].enabled = event.target.checked;
    selectedNode = `service:${id}`;
    hoverNode = null;
    // Keep the changed switch visible and focused so the action is reversible.
    if (problemsOnly && !connectionOn(id)) { problemsOnly = false; expanded = true; }
    clearTrace();
    render();
    doc.querySelector(`[data-nm-connection="${id}"]`).focus({ preventScroll: true });
    announce(`${id} is ${connectionOn(id) ? "on" : "off"} across all endpoints. Saved settings and tool permissions are kept.`);
  });
  $("nm-network").addEventListener("pointerover", (event) => { const node = event.target.closest("[data-nm-node]"); if (node) { hoverNode = node.dataset.nmNode; highlightWires(); } });
  $("nm-network").addEventListener("pointerout", (event) => { if (event.target.closest("[data-nm-node]")) { hoverNode = null; highlightWires(); } });
  $("nm-network").addEventListener("focusin", (event) => { const node = event.target.closest("[data-nm-node]"); if (node) { hoverNode = node.dataset.nmNode; highlightWires(); } });
  $("nm-network").addEventListener("focusout", () => { hoverNode = null; highlightWires(); });
  doc.addEventListener("click", (event) => {
    const problemButton = event.target.closest("[data-nm-problems]");
    if (problemButton) {
      const ep = endpoints.find((item) => item.slug === problemButton.dataset.nmProblems);
      if (!unreachable(ep).length) return;
      selected = ep;
      $("nm-service-search").value = "";
      problemsOnly = true;
      expanded = true;
      selectedNode = `service:${unreachable(ep)[0]}`;
      hoverNode = null;
      clearTrace();
      render();
      nodeElement(selectedNode).focus({ preventScroll: true });
      $("nm-services").scrollIntoView({ block: "nearest", behavior: "instant" });
      announce(`Showing ${unreachable(ep).length} unreachable connections on ${ep.slug}. Each has a red outline and an Unreachable status. Show all returns to every service.`);
      return;
    }
    const node = event.target.closest("[data-nm-node]");
    if (node) {
      selectedNode = node.dataset.nmNode;
      clearTrace();
      doc.querySelectorAll("[data-nm-node]").forEach((el) => el.setAttribute("aria-pressed", String(el === node)));
      doc.querySelectorAll("[data-nm-service]").forEach((el) => { el.dataset.selected = String(`service:${el.dataset.nmService}` === selectedNode); });
      doc.querySelectorAll("[data-nm-app]").forEach((el) => { el.dataset.selected = String(`app:${el.dataset.nmApp}` === selectedNode); });
      renderDetail();
      highlightWires();
      refreshIcons();
      announce($("nm-detail-description").textContent);
    }
    const connectApp = event.target.closest("[data-nm-connect-app]");
    if (connectApp) openConnect(connectApp, connectApp.dataset.nmConnectApp);
    const add = event.target.closest("[data-nm-add-service]");
    if (add && selected && availability === "ready") openServicePicker(add);
    const review = event.target.closest("[data-nm-review-installed]");
    if (review) { $("nm-service-picker").close(); openService($("nm-add-service-trigger")); }
    const back = event.target.closest("[data-nm-pick-service]");
    if (back) { $("nm-add-service").close(); openServicePicker($("nm-add-service-trigger")); }
    const create = event.target.closest("[data-nm-new-endpoint]");
    if (create && availability === "ready") {
      $("nm-endpoint-form").reset();
      $("nm-new-name").setCustomValidity("");
      $("nm-new-name").removeAttribute("aria-invalid");
      $("nm-new-error").hidden = true;
      openDialog("nm-new-endpoint", create);
    }
  });
  $("nm-connect-trigger").addEventListener("click", (event) => openConnect(event.currentTarget));
  $("nm-service-endpoint").addEventListener("change", serviceReview);
  $("nm-connect-choice").addEventListener("change", connectReview);
  $("nm-service-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const ep = serviceReview();
    if (!ep) return;
    selected = ep;
    ep.services.push("github-mcp");
    problemsOnly = false;
    $("nm-service-search").value = "";
    selectedNode = "service:github-mcp";
    clearTrace();
    $("nm-add-service")._gwReturn = $("nm-add-service-trigger");
    $("nm-add-service").close();
    render();
    nodeElement(selectedNode).focus();
    announce(`GitHub MCP added to ${ep.slug}. All 26 tools require approval.`);
  });
  $("nm-new-name").addEventListener("input", () => { $("nm-new-name").setCustomValidity(""); $("nm-new-name").removeAttribute("aria-invalid"); $("nm-new-error").hidden = true; });
  $("nm-endpoint-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const input = $("nm-new-name");
    if (endpoints.some((ep) => ep.slug === input.value)) {
      input.setCustomValidity("This endpoint name is already in use.");
      input.setAttribute("aria-invalid", "true");
      $("nm-new-error").textContent = "Choose a different endpoint name.";
      $("nm-new-error").hidden = false;
      input.reportValidity();
      return;
    }
    selected = { slug: input.value, env: "dev", enabled: true, services: [], apps: [], description: $("nm-new-description-input").value.trim() };
    problemsOnly = false;
    $("nm-service-search").value = "";
    endpoints.push(selected);
    selectedNode = null;
    clearTrace();
    $("nm-new-endpoint").close();
    render();
    announce(`${selected.slug} created. Add a service on the left.`);
  });
  $("nm-connect-form").addEventListener("submit", (event) => {
    event.preventDefault();
    if (!connectReview()) return;
    const id = $("nm-connect-choice").value;
    selected.apps.push({ id, status: id === "claude" ? "Waiting for Claude Desktop" : "Needs restart" });
    selectedNode = `app:${id}`;
    clearTrace();
    $("nm-connect").close();
    render();
    nodeElement(selectedNode).focus();
    announce(`${apps[id].name} setup is pending. Finish setup in the app; a working connection is not yet confirmed.`);
  });
  $("nm-endpoint-enabled").addEventListener("change", (event) => {
    if (!selected || availability !== "ready") return;
    selected.enabled = event.target.checked;
    problemsOnly = false;
    clearTrace();
    render();
    announce(`${selected.slug} is ${selected.enabled ? "on" : "off"}. Other endpoints are unchanged.`);
  });
  $("nm-trace").addEventListener("click", () => {
    if ($("nm-trace").getAttribute("aria-disabled") === "true") return;
    const [kind, id] = (selectedNode || "").split(":");
    const app = kind === "app" ? id : selected.apps.find((link) => link.status === "Connected").id;
    const service = kind === "service" ? id : shownServices().find(connectionOn);
    trace = { app, service };
    const result = service === "github-mcp" ? "GateRook asks you to approve this tool call before it can reach the service." : services[service].health === "Unreachable" ? "The route is configured, but this service is unreachable. GateRook records the failed call." : "GateRook checks the tool's access rules, asks for approval when required, and records the result. Service credentials stay in GateRook.";
    $("nm-trace-title").textContent = `${apps[app].name} → ${selected.slug} → ${service}`;
    $("nm-trace-text").textContent = `Example path only; no request is sent. ${result}`;
    $("nm-trace-result").hidden = false;
    highlightWires();
    animateTrace();
    announce($("nm-trace-title").textContent + ". " + result);
  });
  $("nm-clear-trace").addEventListener("click", () => { clearTrace(); $("nm-trace").focus(); });
  $("nm-retry").addEventListener("click", () => { availability = "ready"; render(); $("nm-map-title").focus(); announce("Sample connection map loaded. No external request was sent."); });

  if (onboarding) {
    $("nm-approvals").hidden = true;
    doc.querySelectorAll(".gw-titlebar .gw-counter,.gw-nav__counter,#cmdk .gw-counter").forEach((counter) => { counter.hidden = true; });
    $("gw-titlebar-approvals-tip").textContent = "Approvals, none waiting";
    ["cmdk-g-connections", "cmdk-g-endpoints"].forEach((id) => {
      const group = doc.querySelector(`#cmdk [aria-labelledby="${id}"]`);
      const keep = state === "setup" ? ["cmdk-o-dev-db", "cmdk-o-ep-dev"] : selected ? ["cmdk-o-ep-dev"] : [];
      group?.querySelectorAll(".gw-menu__item").forEach((el) => { if (!keep.includes(el.id)) el.remove(); });
      if (group && !group.querySelector(".gw-menu__item")) group.remove();
    });
    if (!selected) ["cmdk-o-copy-dev", "cmdk-o-issue-token"].forEach((id) => $(id)?.remove());
  }
  render();
  new ResizeObserver(scheduleWires).observe($("nm-network"));
  doc.fonts.ready.then(scheduleWires);
  window.addEventListener("resize", scheduleWires);
})();
