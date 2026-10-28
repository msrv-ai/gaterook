# GateRook interface blueprint

This specification connects the application architecture to the design kit: information architecture, screen contracts, component APIs, shared terminology, and a consistent fictional demo world. See [DESIGN.md](DESIGN.md) for visual and behavioral rules, [AGENTS.md](AGENTS.md) for the workflow, and [application architecture](../docs/ARCHITECTURE.md) for runtime details. Reference screens describe UI behavior; they are not evidence of a deployed release or live infrastructure.

## 1 · Product principles and interface behavior

| Principle | Interface contract |
| --- | --- |
| Agents never see secrets | Stored secrets are write-only; use Replace/Rotate, masking, and one-time token display |
| Mutations require approval by default | Offer Off, Read only, Approval, and explicit Bypass; reserve green approval actions for the human decision |
| Approve the exact action | Show literal code or diff without ligatures; agent commentary is separate and unverified |
| Production has stricter boundaries | Show `gw-env` in all risky contexts and explain stricter defaults |
| Setup is understandable | Secrets & resources → Connections → Policies → Endpoints → AI apps; calls travel back through the chain |
| Every call is auditable | Every object links to filtered Activity; setup changes have their own view |
| Desktop works locally | Local fonts and assets, strict CSP, core on loopback, visible core status |
| Desktop has one user | Decisions say **by you**; named users exist only in labeled server-mode previews |

Overview simplifies first use to the next two actions: connect a resource, then connect an AI app. Full setup remains visible in onboarding; endpoint details show their own wiring.

## 2 · Information architecture

```text
Overview       Pending decisions, connected resources, and health
Approvals      Requests awaiting the user's decision
Resources
  Connections  Configured databases, SSH, logs, clusters, clouds, and APIs
  Connectors   Built-in types, templates, plugins, and MCP servers
  Secrets      Master key, stored secrets, and external secret stores
Access
  Endpoints    URLs, tools, modes, and tokens
  AI apps      Client connection and authentication
  Policies     Environment-specific guardrails
  Playground   Invoke a tool as an agent would
Observe
  Activity     Calls, filters, event details, traces, and setup changes
  Insights     Volumes, decisions, latency, and errors
Settings       Appearance, authorization, notifications, network, and data
```

The sidebar footer shows **Core running · 127.0.0.1:7465 · v0.4.0 · Tunnel off** in the shared demo.

| Settings group | Sections |
| --- | --- |
| Workspace | General: startup, core, port, updates; Appearance: theme and contrast |
| Security | Authorization: default seven-day TTL and OAuth; Users & roles: server preview only |
| Approvals | Notifications; Approval rules, including Require a comment on prod; environment windows and approvers are in Policies |
| Network | Tunnel and its sole Anthropic caller allowlist control; Local API host/origin and port controls |
| Data | Audit retention: 1/2/3/5 years, minimum/default one; export; database and separate key backups |

Settings anchors use `settings.html#settings-<section>` with `general`, `appearance`, `authorization`, `users`, `notifications`, `approval-rules`, `tunnel`, `tunnel-callers`, `local-api`, `audit`, and `backup`. Application routes use `/settings/<section>`. Policies support `#dev`, `#stage`, `#prod`, and `#<env>-<section>`; select the correct tab before scrolling to a section.

Application reference pages are listed in section 6. Connection URLs support `?type=<connector>`, `?duplicate=<name>`, `?edit=<name>`, and `?state=first-run`. Duplicate copies settings, never credentials. Activity supports `q`, `range`, `view=traces|changes`, and `#event-<id>`. First-run and setup previews use `?state=first-run|setup`.

## 3 · Requirements mapped to screens

| Requirement | Screens and behavior |
| --- | --- |
| Configure and test a resource | Connections and connection-new; edit and duplicate modes |
| External secret references and rotation | Secrets and connection form; write-only controls |
| Extend without rebuilding | Connectors; explicit untrusted-code review; declared hints labeled unverified |
| Assemble endpoint access | Endpoint detail, Tools & policies, and Connections |
| Connect AI clients | AI apps, endpoint AI apps, and tunnel settings; reviewed diff and backup |
| Publish a tunnel | Warning confirmation lists exact routes and gates; consent happens on this computer |
| Decide an action | Approvals and notification previews; channel eligibility limits apply |
| Revoke access and inspect history | Activity, endpoint Tokens, client grants, and signed-in sessions |
| Inspect configuration changes | Activity → Setup changes, with actor, reason, and before/after |
| Trace a task | Activity → Traces and Insights |
| Enforce policy | Policies and endpoint tool modes; no approval bypass |
| Manual MCP invocation | Playground with agent-equivalent results and review links |
| Append-only audit and export | Activity, event-detail, and Audit settings |
| Roles, OIDC, and 2FA | Labeled server-mode previews only |
| Risky access combinations | risk-callout on Endpoints and endpoint detail |
| Excessive account rights | Test connection results and least-privilege guidance |
| SSRF and DNS rebinding | HTTP policy and Local API controls |
| Core startup and recovery | core-starting, sidebar status, and General settings |

Notifications may include Approve only for a non-production action shown in full and requiring no comment. Desktop notifications always use Review and Deny. The IP caller gate protects the MCP route; the user's OAuth browser is not required to originate from Anthropic's ranges.

## 4 · Components

The full English API reference, variants, parts, behavior, accessibility, and markup examples are in [COMPONENTS.md](COMPONENTS.md). Exact machine-readable fields remain in [manifest.json](manifest.json). Use `gw-` BEM classes, native/ARIA state attributes, and shared semantic tones.


| Component | Source |
| --- | --- |
| `gw-logo` | [scss/components/_logo.scss](scss/components/_logo.scss) |
| `gw-icon / gw-icon-tile / gw-spinner` | [scss/components/_icon.scss](scss/components/_icon.scss) |
| `gw-btn / gw-btn-group` | [scss/components/_button.scss](scss/components/_button.scss) |
| `gw-card / gw-stack` | [scss/components/_card.scss](scss/components/_card.scss) |
| `gw-shell` | [scss/components/_shell.scss](scss/components/_shell.scss) |
| `gw-titlebar` | [scss/components/_shell.scss](scss/components/_shell.scss) |
| `gw-sidebar` | [scss/components/_shell.scss](scss/components/_shell.scss) |
| `gw-nav` | [scss/components/_shell.scss](scss/components/_shell.scss) |
| `gw-page` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-page-header` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-breadcrumb` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-section` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-toolbar` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-split` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-grid` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-divider` | [scss/components/_page.scss](scss/components/_page.scss) |
| `gw-kbd` | [scss/components/_kbd.scss](scss/components/_kbd.scss) |
| `gw-avatar` | [scss/components/_avatar.scss](scss/components/_avatar.scss) |
| `gw-client-mark` | [scss/components/_avatar.scss](scss/components/_avatar.scss) |
| `gw-badge / gw-counter` | [scss/components/_badge.scss](scss/components/_badge.scss) |
| `gw-status` | [scss/components/_status.scss](scss/components/_status.scss) |
| `gw-env` | [scss/components/_status.scss](scss/components/_status.scss) |
| `gw-mode` | [scss/components/_status.scss](scss/components/_status.scss) |
| `gw-tag / gw-tag-list` | [scss/components/_tag.scss](scss/components/_tag.scss) |
| `gw-kv` | [scss/components/_kv.scss](scss/components/_kv.scss) |
| `gw-stat` | [scss/components/_stat.scss](scss/components/_stat.scss) |
| `gw-progress` | [scss/components/_progress.scss](scss/components/_progress.scss) |
| `gw-meter` | [scss/components/_progress.scss](scss/components/_progress.scss) |
| `gw-empty` | [scss/components/_empty.scss](scss/components/_empty.scss) |
| `gw-field` | [scss/components/_field.scss](scss/components/_field.scss) |
| `gw-input` | [scss/components/_input.scss](scss/components/_input.scss) |
| `gw-textarea` | [scss/components/_input.scss](scss/components/_input.scss) |
| `gw-select` | [scss/components/_input.scss](scss/components/_input.scss) |
| `gw-input-group` | [scss/components/_input.scss](scss/components/_input.scss) |
| `gw-checkbox` | [scss/components/_choice.scss](scss/components/_choice.scss) |
| `gw-radio` | [scss/components/_choice.scss](scss/components/_choice.scss) |
| `gw-switch` | [scss/components/_choice.scss](scss/components/_choice.scss) |
| `gw-segmented` | [scss/components/_segmented.scss](scss/components/_segmented.scss) |
| `gw-secret` | [scss/components/_secret.scss](scss/components/_secret.scss) |
| `gw-choice-card` | [scss/components/_choice-card.scss](scss/components/_choice-card.scss) |
| `gw-search` | [scss/components/_search.scss](scss/components/_search.scss) |
| `gw-file-drop` | [scss/components/_file-drop.scss](scss/components/_file-drop.scss) |
| `gw-form` | [scss/components/_form.scss](scss/components/_form.scss) |
| `gw-tabs` | [scss/components/_tabs.scss](scss/components/_tabs.scss) |
| `gw-menu-host / gw-menu / gw-popover` | [scss/components/_menu.scss](scss/components/_menu.scss) |
| `gw-tooltip` | [scss/components/_tooltip.scss](scss/components/_tooltip.scss) |
| `gw-dialog / gw-drawer` | [scss/components/_dialog.scss](scss/components/_dialog.scss) |
| `gw-toast-region / gw-toast` | [scss/components/_toast.scss](scss/components/_toast.scss) |
| `gw-alert` | [scss/components/_alert.scss](scss/components/_alert.scss) |
| `gw-stepper` | [scss/components/_stepper.scss](scss/components/_stepper.scss) |
| `gw-paginator` | [scss/components/_paginator.scss](scss/components/_paginator.scss) |
| `gw-skeleton` | [scss/components/_skeleton.scss](scss/components/_skeleton.scss) |
| `gw-code / gw-code-block / gw-tok-*` | [scss/components/_code.scss](scss/components/_code.scss) |
| `gw-diff` | [scss/components/_diff.scss](scss/components/_diff.scss) |
| `gw-copy-field` | [scss/components/_copy-field.scss](scss/components/_copy-field.scss) |
| `gw-table-wrap / gw-table` | [scss/components/_table.scss](scss/components/_table.scss) |
| `gw-chart / gw-legend / gw-sparkline` | [scss/components/_chart.scss](scss/components/_chart.scss) |
| `gw-waterfall` | [scss/components/_waterfall.scss](scss/components/_waterfall.scss) |
| `gw-timeline` | [scss/components/_timeline.scss](scss/components/_timeline.scss) |

## 5 · Reusable compositions

The complete composition contracts and state demonstrations are in [COMPONENTS.md](COMPONENTS.md).

| Composition | Demo | Used by |
| --- | --- | --- |
| `setup-map` | [Demo](elements/setup-map.html) | `pages/onboarding.html`, `pages/endpoint-detail.html` |
| `how-it-works` | [Demo](elements/how-it-works.html) | `pages/secrets.html`, `pages/clients.html`, `pages/connectors.html`, `pages/settings.html` |
| `getting-started` | [Demo](elements/getting-started.html) |  |
| `approval-request` | [Demo](elements/approval-request.html) | `pages/approvals.html`, `pages/overview.html`, `pages/activity.html` |
| `approval-notification` | [Demo](elements/approval-notification.html) | `pages/approvals.html`, `pages/settings.html` |
| `tool-policy-list` | [Demo](elements/tool-policy-list.html) | `pages/endpoint-detail.html`, `pages/policies.html`, `pages/playground.html` |
| `risk-callout` | [Demo](elements/risk-callout.html) | `pages/endpoint-detail.html`, `pages/endpoints.html`, `pages/policies.html` |
| `token-issue` | [Demo](elements/token-issue.html) | `pages/endpoint-detail.html`, `pages/settings.html` |
| `client-card` | [Demo](elements/client-card.html) | `pages/clients.html`, `pages/endpoint-detail.html`, `pages/onboarding.html` |
| `connection-form` | [Demo](elements/connection-form.html) | `pages/connection-new.html`, `pages/onboarding.html` |
| `connector-card` | [Demo](elements/connector-card.html) | `pages/connectors.html`, `pages/connection-new.html` |
| `event-detail` | [Demo](elements/event-detail.html) | `pages/activity.html`, `pages/approvals.html`, `pages/playground.html` |
| `log-explorer` | [Demo](elements/log-explorer.html) | `pages/activity.html`, `pages/insights.html` |
| `settings-layout` | [Demo](elements/settings-layout.html) | `pages/settings.html`, `pages/endpoint-detail.html` |
| `status-screen` | [Demo](elements/status-screen.html) | `pages/core-starting.html` |
| `command-palette` | [Demo](elements/command-palette.html) | `pages/_shell.html`, `pages/overview.html`, `pages/approvals.html`, `pages/connections.html`, `pages/connection-new.html`, `pages/connectors.html`, `pages/secrets.html`, `pages/endpoints.html`, `pages/endpoint-detail.html`, `pages/clients.html`, `pages/policies.html`, `pages/playground.html`, `pages/activity.html`, `pages/insights.html`, `pages/settings.html` |
| `status-tile` | [Demo](elements/status-tile.html) | `pages/overview.html` |

## 6 · Screen contracts

Each entry retains the registered screen purpose, required compositions, and requirement coverage. Read the runtime architecture for implementation status and the actual application API.

### Overview · GateRook

[Reference screen](pages/overview.html)

The home screen with as little noise as possible, in three states. In use (default): page header (Overview, one line, Add connection as the only page action) → Waiting for your approval at the very top as the quick-access zone, only while requests wait (3 approval-request --compact sorted by time to auto-deny, a counter, All approvals; expired requests leave the list and every counter in the shell follows; at zero the section becomes one quiet line) → Your connections (status-tile in gw-grid --3, six shown by default, problems first and never folded: billing-api Unreachable with Test again and a busy state, cache-redis Slow · 1.8 s, dev-db, orders-mongo, dev-cluster and dev-vm; 6 healthy ones behind a disclosure placed before the hidden list) → AI apps (status-tile in gw-grid --2: Cursor Not connected + Connect first, Claude Desktop and Codex Connected with what they can use, claude.ai through the tunnel) → one quiet line about Activity. First run (?state=first-run, nothing set up): one Get started card with a gw-stepper --guide of three steps: 1 Add your first connection (one primary Add connection → the type chooser in connection-new), 2 Connect your AI app (status-tile for Claude Desktop, Codex, Cursor, Antigravity; Connect is a <button aria-disabled> with the reason 'Add a connection first…'), 3 Check that it works. Setup (?state=setup, a connection exists, no AI app yet): Almost done, step 1 complete (dev-db, the master key backup note), step 2 current with working Connect links, step 3 waits for the first request. setup-map, KPIs and the calls table live on their own pages (Onboarding, Endpoint detail, Insights, Activity). A small page script keeps only the blocks of the current state, prunes the command palette outside In use and keeps the approval counters in sync. In the demo the setup guide's Connect opens that app's Connect review on clients.html?state=setup#cl-<app>-t, and How to install lands on the Antigravity row (?state=setup | first-run #cl-ag-t). The first-run Add connection opens connection-new.html?state=first-run.

**Compositions:** `approval-request --compact (×3, pending; urgent tone on the 0:48 one; In use only)`, `status-tile (connections: unreachable + Test again / busy, slow, healthy; AI apps: not connected + Connect, connected, tunnel; first run: Connect unavailable with a reason, not installed + How to install)`, `command-palette (shell; pruned to what exists in First run / Setup)`.

**Components:** `gw-shell / gw-titlebar / gw-sidebar / gw-nav (canonical shell)`, `gw-popover + gw-segmented (appearance, shell)`, `gw-page / gw-page-header`, `gw-section`, `gw-grid (--2, --3)`, `gw-stack (--sm)`, `gw-card`, `gw-stepper (--guide: complete, current, upcoming)`, `gw-btn (--primary, default --sm, --ghost, --link, gw-btn__chevron)`, `gw-icon / gw-icon-tile`, `gw-counter (section + shell)`, `gw-status (--success --warning --danger --info --neutral --checking)`, `gw-env (--sm)`, `gw-client-mark (--claude --codex --cursor --antigravity)`, `gw-code / gw-mono`, `gw-spinner (busy Test again)`, `gw-toast via window.gwToast (test result)`, `gw-link`.

**Requirement coverage:**

- Purpose and core principle: agents never see secrets; the first-run caption distinguishes reads from changes.
- Administrator scenario: approve or deny mutations with quick access at the top.
- Policies and approvals: show the exact action; timeout means denial.
- Connections and secrets: periodic health checks show Unreachable, Slow, or Healthy.
- Clients: Claude Desktop, Codex, Cursor, Antigravity, and claude.ai.
- Audit: every call is logged, with a direct link to Activity.

### Welcome · GateRook

[Reference screen](pages/onboarding.html)

First run, using the shell's no-sidebar variant: the titlebar stays (drag region and Appearance only), there is no data-gw-nav, and main spans the full width with gw-page--narrow. Order: gw-logo --xl → page header (Welcome to GateRook, one sentence on the value, a Skip setup ghost link) → the three promises (agent never sees secrets, you approve every change, every call is logged) → Set up in four steps: a gw-stepper (Protect master key → Add a connection → Create an endpoint → Connect your AI app) with step 1 current → two columns. Left: the step 1 card, which explains the master key, shows the path ~/.gaterook/master.keyset as a copy field, permissions 0400 and the format ('Google Tink keyset, sealed with a key from the macOS Keychain'), ends its explanation with the trust boundary ('It protects against other users and copied files, not against programs you run, including AI agents with a terminal.'), says where to put the backup, and states the consequence of skipping in a warning alert. Its buttons are Back up key (primary, with a loading state) and I'll do it later (a link button, aria-describedby pointing at the consequence). Right: a setup-map --vertical preview in the first-run state, where each node names the step that fills it and Secrets needs attention until the key is backed up. Footer note: Everything stays on this computer, and the core listens on 127.0.0.1 only. A small page script switches between the step panels. Steps 2 to 4 hand off to connection-new.html, endpoints.html and clients.html (step 4 names Cursor's three ways: a token in the file, sign-in through the browser, or the bridge). The script keeps the stepper ARIA in sync, moves focus to the new step's title and announces the step.

**Compositions:** `setup-map --vertical (first-run preview in setup order, the same order as the stepper; todo + attention states, Secrets node turns done after the backup)`.

**Components:** `gw-shell (no-sidebar variant) / gw-titlebar`, `gw-popover + gw-segmented (appearance)`, `gw-page --narrow / gw-page-header`, `gw-logo --xl`, `gw-grid (--auto, --2)`, `gw-card (--muted, --dashed via setup-map, __footer--split)`, `gw-icon / gw-icon-tile (--brand, --sm)`, `gw-stack (--sm, --lg)`, `gw-section`, `gw-stepper (current / upcoming / complete, scripted)`, `gw-field (__label) + gw-copy-field`, `gw-kv --dense`, `gw-code / gw-mono`, `gw-alert --warning`, `gw-btn (--primary, --link, --ghost)`, `gw-spinner (busy Back up key)`, `gw-badge (setup-map states)`.

**Requirement coverage:**

- Purpose and core principle: agents never see secrets; clients use one URL.
- Deployment: the desktop core binds to 127.0.0.1 and does not depend on a hosted gateway service.
- Connections and secrets: the master-key file stays outside the database and its backups; use envelope encryption.
- Master-key theft or loss: mode 0400, a separate backup, and no recovery without the key.
- One-click client connection: review the diff and create a backup before writing; Claude Desktop and Codex obtain tokens on the fly.
- Policies and approvals: Off, Read only, Approval, and Bypass; mutations require approval by default.
- Audit: append-only.
- Desktop non-functional requirements.

### Approvals

[Reference screen](pages/approvals.html)

The approval queue (most urgent first) next to the full request for the selected item, plus the history of decided requests. You approve or deny the exact action, with a comment (required on prod). One slim header line shows where requests are delivered (Desktop on, Slack #deploy-approvals, Telegram not set up). On the desktop every request has one approver, you: decisions read 'by you' (k8s_apply rejected by you; restart-api approved by you from Slack); named approvers and the partial state appear only in labelled server-mode previews. Clock: 14:30 — k8s_rollout_restart auto-denies in 0:48, sql_execute in 4:12 (5 min window), http_request in 9:30 (10 min window on stage).

**Compositions:** `approval-request (full pending ×3: sql_execute on dev-db 4:12 selected, k8s_rollout_restart on dev-cluster 0:48 urgent, http_request on billing-api stage 9:30; single approver)`, `approval-request--compact (queue rows, gw-card--selected + aria-current on the selected Review link, danger timer under 60 s, data-gw-countdown-announce)`, `approval-notification (Slack preview per request inside a collapsed 'How this request was delivered' disclosure)`.

**Components:** `gw-page--full`, `gw-page-header (__meta: delivery channels + approvers)`, `gw-split`, `gw-tabs (Pending 3 / History 58)`, `gw-card (--selected, --muted)`, `gw-code-block (+ --wrap, gw-tok-*)`, `gw-kv (--inline, --dense)`, `gw-badge`, `gw-mode--approval`, `gw-env (--sm)`, `gw-client-mark`, `gw-progress --sm (warning/danger)`, `gw-tooltip--start`, `gw-field + gw-textarea`, `gw-btn (--approve, --danger, --ghost, --link, --sm)`, `gw-status`, `gw-avatar --sm`, `gw-table (--interactive, row links to Activity) + gw-table-wrap`, `gw-paginator`, `gw-empty (--compact queue, full detail)`, `gw-divider--vertical`, `gw-link`, `toasts via data-gw-toast`, `page-local script: select request by hash, warning-to-danger and expired timer re-render, empty-queue preview`.

**Requirement coverage:**

- Administrator scenario: approve or deny mutations from the app, notifications, Slack, or Telegram within channel eligibility rules.
- Policies and approvals: request, notification, decision with comment, and execution; exact action; timeout denies.
- Prompt-injection boundary: the exact action is authoritative; Agent's note is unverified and is not forwarded to channels.
- Audit: every object links to Activity, including trace 4f2a9c, history rows, and View all.
- BLUEPRINT sections 6 (Approvals), 7 (decision vocabulary), and 9 (requests and history).

### Policies

[Reference screen](pages/policies.html)

Guardrails per environment in tabs (Development, Staging, Production; Production selected and showing stricter defaults). Each tab is one gw-form with SQL, SSH, Kubernetes, HTTP egress, Logs, Limits and Approvals sections, plus 'More … settings' disclosures for schema keys outside the layout. Inheritance follows the core: stage inherits each value it doesn't set from dev (empty field with the dev value as placeholder); prod inherits nothing and has its own, stricter defaults, whose hints compare with dev ('Prod default · dev: 5 s'); the Values line counts them ('4 set on dev · base for stage', '3 stage defaults · 6 set on stage · 32 inherited from dev', '24 prod defaults · 2 set on prod'). Read-only SQL transactions and blocked private networks are locks ('Always on', 'Blocked unless allowed below'), not switches; every mutation rule is <env>.<section>.write-approval. A connector section without a connection of its kind on the environment is an empty card with read-only rules (HTTP egress on dev). Reset clears every value the environment stores, non-inherited ones included, lists them in its dialog and shows only when there is something to clear. An explanation that mutations need approval by default and explicit Bypass skips approval sits above the tabs. Anchors: #dev / #stage / #prod select the tab, #<env>-<section> (#prod-approvals, #dev-sql) also scrolls to the section. Prod values: row limit 100 (dev 1,000), statement timeout 3 s (dev 5 s; one value per environment), no DDL, Kubernetes verbs get, list, describe, logs (dev adds rollout restart and apply; no watch), approval timeout 10 min, a comment required to approve (default on); Slack and Telegram never offer Approve on prod. One approver everywhere on the desktop; named approvers only in server mode.

**Compositions:** `risk-callout --compact (stage HTTP egress: endpoints dev and stage-readonly can send data to billing.stage.acme.dev through the allowlist)`.

**Components:** `gw-page--narrow`, `gw-page-header`, `gw-alert (--info invariant with link to Endpoints for tool modes, --warning --compact risk)`, `gw-tabs (env marker gw-env aria-hidden + word + connection count)`, `gw-toolbar (applies-to and provenance counts, View activity, danger-ghost Reset)`, `gw-form (--sticky-actions on prod, section + section-header + legend h2, rows, row, actions, note)`, `gw-field (hint, error, optional)`, `gw-input / gw-input-group (affix, addon)`, `gw-select (empty option = the environment's default: 'Same as dev' on stage, the prod default on prod)`, `gw-kv lock rows with gw-badge + lock icon for rules nobody can switch ('Always on' read-only SQL transactions, 'Blocked' private networks, 'Always masked')`, `gw-checkbox (switchable rules)`, `gw-segmented (Approvals needed, disabled option with reason)`, `gw-tag / gw-tag-list (--mono, --warning)`, `gw-meter (ok / warn with current use)`, `gw-kv (--inline, --dense)`, `gw-mode (--approval, --read)`, `gw-card --muted (runbook cards)`, `gw-empty --compact (no SSH/Kubernetes/HTTP connection on an env)`, `gw-dialog --sm --danger (Reset stage policy, Reset prod policy)`, `gw-icon-tile`, `gw-link`, `page-local script: hash deep links such as #dev-sql select the env tab`.

**Requirement coverage:**

- Policies and approvals: SQL classification, read-only transactions, statement timeout, row limits, schema/table allowlists; SSH runbooks with typed parameters and separately authorized arbitrary shell; Kubernetes verb/resource/namespace allowlists with approval for mutations; per-token/tool rate limits, response limits, deadlines, approval timeout, approvers, and channels.
- Security: mutations need approval by default; explicit Bypass skips approval while policy denials and limits remain enforced.
- HTTP SSRF: allowlists, blocked metadata address 169.254.169.254, explicit private-network permission, and post-DNS validation.
- Dangerous combinations within one endpoint use risk-callout.
- SSH command-injection protection uses runbooks.
- Environment is a connection attribute; production defaults are stricter.
- BLUEPRINT section 6 (Policies) and section 3 (Policies and SSRF traceability).

### Connections

[Reference screen](pages/connections.html)

The list of all 12 connections from BLUEPRINT §9 and how healthy they are right now. The header shows health counts (10 healthy, 1 slow, 1 unreachable) and the one primary action, Add connection. Below it is a collapsed 'How connections work' strip (add and test, credentials stay here, add to an endpoint, agents call it by name) that remembers whether it was opened. The toolbar has search ('/' focuses it, and ?q= from other pages pre-fills it), a Type listbox with counts, an Environment segmented control (All / dev / stage / prod) and a Health listbox with counts, plus a live result count and Test all. The table columns are: Connection (mono name, env marker, target host on a second line), Type with icon (plus Read replica / Runbooks only badges), Credentials (Encrypted here / Vault reference / Short-lived, with the kind underneath), Health (status with latency, plus 'Checked 1 min ago'), Used by (links to endpoints) and a row menu: Test connection (checks in place, then a toast), Edit, View activity (connection:<name>), Duplicate, Delete connection. The billing-api row has an inline compact danger alert with Test again and View errors. One danger dialog is filled for whichever row opened it; for dev-db it lists endpoint dev with its 4 tools and Claude Desktop and Codex, the pending sql_execute approval that would be denied, says the encrypted password is destroyed and that the audit log is kept, and has Cancel autofocused. When filters match nothing, an empty row with Clear filters appears. A collapsed 'Empty and loading states' panel shows the first run and a skeleton table.

**Compositions:** `how-it-works`.

**Components:** `gw-shell / gw-titlebar / gw-sidebar / gw-nav (verbatim shell)`, `gw-page`, `gw-page-header (__meta with gw-status counts)`, `gw-btn (--primary --ghost --sm --icon --link, __chevron)`, `gw-card (--muted)`, `gw-stack`, `gw-toolbar (__start role=search, __end)`, `gw-search --sm`, `gw-menu-host + gw-menu (role=listbox filters with __check __icon __hint; role=menu row actions --right, __item--danger, __hint, __divider)`, `gw-segmented --sm (data-tone dev/stage/prod)`, `gw-divider --vertical`, `gw-table-wrap + gw-table (__cell--actions, __empty)`, `gw-env --sm`, `gw-badge --outline --sm`, `gw-status (--success --warning --danger, --checking via script)`, `gw-alert (--danger --warning --compact)`, `gw-empty (default, --compact)`, `gw-section`, `gw-grid --2`, `gw-skeleton --text`, `gw-dialog --md --danger`, `gw-kv --dense`, `gw-code`, `gw-link`, `gw-icon / gw-icon-tile / gw-counter / gw-spinner`, `gw-toast (window.gwToast)`.

**Requirement coverage:**

- Administrator scenario: add a resource and Test connection or Test all in place.
- Connections and secrets: periodic health checks, write-only secrets, external references, short-lived Vault dynamic/SSH CA/GCP impersonation/AWS AssumeRole credentials, and environment attributes.
- Audit: View activity from each connection using activity.html?q=connection:<name>; deletion preserves audit records.
- Secret-leak protection: agents use names only; secret values are never displayed.

### Add connection

[Reference screen](pages/connection-new.html)

Three modes of one screen plus a first-run demo state, picked from the URL (the app routes them; elements carry data-mode-show="add duplicate | edit"). (1) The wizard (connection-new.html, ?type=<connector> preselects a type): a 4-step wizard on a narrow page (breadcrumb Connections › Add connection, Cancel in the header). A gw-stepper shows progress, and a small page script keeps data-state, aria-current, the sr-only 'completed' text and the choice made under each finished step in step with the panels. Back and Continue switch the panels and move focus to the step heading; #step-2 to #step-4 link straight to a step. Step 1 is the connector-card picker: one radio group across Databases, Remote access, Logs, Kubernetes, Cloud, HTTP and MCP, with PostgreSQL selected and ClickHouse disabled because it is not installed. Step 2 is the connection-form element for dev-db: connection fields, credentials (five secret sources; the new password can be revealed only while typing it), environment and safety (prod shows its stricter defaults) and a collapsed Advanced section. Step 3 is the test panel: Connected · PostgreSQL 16.4 · 12 ms, the permissions found (Can write on public.*) and the read-only recommendation with Change user, which goes back to step 2 and focuses User. Test again shows the testing state in place. Collapsed below are copyable SQL that creates a read-only gw_reader user and a failed-test example with the error details. Step 4 offers endpoint checkboxes (dev checked; stage-readonly; incident-prod disabled because it accepts prod connections only). A preview built from the tool-policy-list element shows the default modes: sql_list_schemas, sql_describe_table and sql_query Read only, sql_execute Approval with 'Mutates data: approval or off'. Checking stage-readonly adds its preview, where sql_execute is Off and locked, and a warning that dev data would be reachable by claude.ai through the tunnel. Choosing no endpoint shows an empty state. Save connection is the primary button; it shows a toast with an Open Connections action, and a note says the password is encrypted with the master key on save. (2) ?duplicate=<name>: the same wizard starting at Configure, every setting copied and the name <name>-copy, credentials never copied (an info alert says so; the secret is empty or a new reference). (3) ?edit=<name>: one connection — header with type, host, View activity and Test connection; a health stat for the last 24 hours with a sparkline and a 'Used by' stat; the settings form with the secret as gw-secret stored (Replace / Rotate, no reveal) or a reference (Change / Test); a 'Used by' table of tools × endpoint × mode (gw-mode) with View activity; Save changes ('Applies from the next call'); and Delete connection through a danger confirm that lists what stops working and the pending approvals that will be denied. First run (?state=first-run, wizard only; Overview's first-run Add connection links here): h1 'Add your first connection', plain-words section descriptions, the secret encrypted here by default with the other sources behind a 'Use a secret manager instead (Vault, GCP, AWS)' disclosure, step 4 'Create endpoint' showing the endpoint Save creates, named after the connection's environment (dev in the demo; on prod sql_execute is Off), as a summary with no choice, plus the tool preview, and after Save a '<name> is ready' card with the endpoint, the tools, where the password lives and the primary 'Connect your AI app' (clients.html?state=setup) next to 'Back to Overview' (overview.html?state=setup). After Save the header loses Cancel and says what happened; the card holds the ways on. Cancel goes back to overview.html?state=first-run. No approval counters, no connections or endpoints in the palette; its Add connection stays in first run.

**Compositions:** `connector-card (picker variant)`, `connection-form (Configure sections + Test panel)`, `tool-policy-list (read-only variant with gw-mode)`.

**Components:** `gw-shell / gw-titlebar / gw-sidebar / gw-nav (verbatim shell)`, `gw-page --narrow`, `gw-page-header + gw-breadcrumb`, `gw-stepper (data-state complete/current/upcoming, aria-current=step)`, `gw-section`, `gw-form (__section __section-header __legend __description __rows __row __actions __note)`, `gw-choice-card (radio and checkbox, --compact, :disabled)`, `gw-field (__label __hint __optional)`, `gw-input (--mono)`, `gw-select`, `gw-input-group (__affix __addon, data-gw-reveal)`, `gw-secret (data-state=empty)`, `gw-env (default, --sm)`, `gw-alert (--info --warning --danger --compact)`, `gw-tag-list + gw-tag --mono`, `gw-card (default with __footer --split; --muted --flush --dashed)`, `gw-status --success`, `gw-kv --dense`, `gw-badge (--warning --success --outline --sm)`, `gw-spinner --sm (aria-busy button)`, `gw-code-block (--wrap, gw-tok-*)`, `gw-table-wrap + gw-table`, `gw-mode (--read --approval --off)`, `gw-icon-tile`, `gw-empty --compact`, `gw-grid --auto`, `gw-stack`, `gw-btn (--primary --ghost --link --sm)`, `gw-link`, `gw-code`, `gw-toast (window.gwToast with action)`, `gw-stat + gw-sparkline (edit mode health)`, `gw-secret (data-state=stored / reference in edit mode)`, `gw-dialog --danger (Delete connection, edit mode)`.

**Requirement coverage:**

- Administrator scenario: choose type, host, and account, test, then save; encrypt the password and never display it again; support Vault/GCP SM/AWS SM references.
- Connections and secrets: connector JSON Schema forms, testing before saving, write-only secrets, references, short-lived credentials, and stricter production defaults.
- Connector selection includes built-ins, signed plugins, OpenAPI templates, and external MCP servers.
- MCP tools: sql_query reads and sql_execute requires approval; show readOnly/destructive/idempotent hints.
- Modes are Off, Read only, Approval, and Bypass; Bypass skips approval within policy limits.
- Excessive account permissions: explain test results and provide SQL for a read-only user.
- Warn when adding dev data to an endpoint published for claude.ai.

### Connectors · GateRook

[Reference screen](pages/connectors.html)

Shows which resource types the gateway can reach and how to add a new one without rebuilding: a collapsible how-it-works strip explains the three levels (1 built-in, 2 templates without code, 3 plugins and MCP servers). The primary 'Add connector' menu offers New template, Install plugin and Add MCP server. Tabs with counts: Installed (12), Catalog (5), Templates (5) and MCP servers (1). Installed has a live search with an empty state. Each card shows its connections count and a View activity link. The Templates tab has a list plus an editor for orders_by_status: the SQL with :param placeholders becomes a typed parameter table and a generated tool preview (name, description, annotations, default mode, inputSchema JSON). The MCP servers tab has a table with a collapsed process-details card. Plugin installs end in a check result: signed by the catalog leads to a review and install; unsigned leads to a danger dialog that says the code runs as your user in a separate plugin process and gets only its connection's secrets during a call, 'Not sandboxed on this system…' where there is no sandbox, and needs explicit confirmation. Starting an external MCP server is reviewed in a danger dialog too (command — for github-mcp the allowed argv node /Users/me/.gaterook/mcp/github-mcp/dist/index.js after npm ci --ignore-scripts, not npx —, version pin, secrets passed, PATH, HOME and basic user variables, tools Off and then Approval on an endpoint). Tool counts per connector match every other screen (BLUEPRINT §9); Logs is one built-in connector with Loki, GCP Cloud Logging, AWS CloudWatch and OpenSearch back ends; tools of github-mcp read 'Declared by github-mcp, not verified' and, with no annotations declared, stay approval or off.

**Compositions:** `how-it-works`, `connector-card`.

**Components:** `gw-shell`, `gw-titlebar`, `gw-sidebar`, `gw-nav`, `gw-page`, `gw-page-header`, `gw-btn`, `gw-menu-host`, `gw-menu`, `gw-card`, `gw-stack`, `gw-tabs`, `gw-counter`, `gw-toolbar`, `gw-search`, `gw-grid`, `gw-icon-tile`, `gw-badge`, `gw-status`, `gw-empty`, `gw-table-wrap`, `gw-table`, `gw-env`, `gw-mode`, `gw-code`, `gw-code-block`, `gw-form`, `gw-field`, `gw-input`, `gw-textarea`, `gw-select`, `gw-checkbox`, `gw-segmented`, `gw-kv`, `gw-alert`, `gw-tooltip`, `gw-dialog`, `gw-file-drop`, `gw-toast`.

**Requirement coverage:**

- Administrator scenario: add a resource type without rebuilding through SQL/SSH/OpenAPI templates, a Java plugin, or an MCP server.
- Connector extensibility: UI templates, PF4J plugin file/URL/catalogue, stdio/HTTP MCP with secret injection, a manifest with ID/version/configuration/tools/permissions, and catalogue signatures.
- External MCP servers such as GitHub act as connectors behind policies, approvals, and audit.
- Tools expose inputSchema and declared readOnly/idempotent hints; use Off, Read only, or Approval.
- Connector SPI: generate forms from JSON Schema and show tool annotations.
- Untrusted plugins and MCP servers: verify signatures, isolate processes, minimize environment variables, and require explicit unsigned-code confirmation.

### Secrets · GateRook

[Reference screen](pages/secrets.html)

Shows how credentials are stored, encrypted and handed to a connector at call time, and where they come from. An info alert states the invariant: secrets never leave this computer and are never sent to the agent. An expanded how-it-works strip has four steps (store or reference, encrypted with the master key, decrypted only inside the connector at call time, the agent gets the masked result). The master key card shows ~/.gaterook/master.keyset, 0400, primary key ID, OS protection, protects 8 secrets, and last backup 12 days ago with a warning hint, then the trust boundary: 'Protects against other users and copied files, not against programs you run, including AI agents with a terminal.' Its actions are Back up key (primary) and Rotate key; the rotate dialog explains zero-downtime re-wrapping of data keys and leads to a progress dialog. The Stored secrets table has 11 rows (8 encrypted here: app-logs, aws-stage access key v2, cache-redis, dev-cluster, dev-db, github-mcp, orders-mongo, the Vault sign-in; 3 references: stage-db in Vault, billing-api/token in GCP SM, billing-api/webhook-secret in AWS SM, store not connected) (name, used by + env, source Encrypted here / Vault / GCP SM / AWS SM, version, last rotated). Row actions are Rotate or Test, Replace, and View usage, which goes to Activity with a filter. Rotate works only where the connector can set a new value at the source (dev-db/password); elsewhere it stays in place, aria-disabled with the core's reason in a tooltip ('This connector can't issue a new value for itself. Replace it with a new value.'). Nothing can be revealed. Search and source filter come with an empty row, and the unresolved AWS reference is shown as an error. External store cards: HashiCorp Vault / OpenBao connected via AppRole, GCP Secret Manager connected via impersonation, and AWS Secrets Manager not connected, with a Connect dialog. Disconnect uses a danger confirmation that names the store. Short-lived credentials cards: GCP impersonation (a short-lived token, renewed automatically), AWS AssumeRole with ExternalId, SSH certificates from Vault CA (a fresh key pair for each call) and Vault dynamic DB users (renewed automatically, revoked when the connection goes idle), each with the connections it serves and View activity.

**Compositions:** `how-it-works`.

**Components:** `gw-shell`, `gw-titlebar`, `gw-sidebar`, `gw-nav`, `gw-page`, `gw-page-header`, `gw-alert`, `gw-card`, `gw-stack`, `gw-grid`, `gw-kv`, `gw-status`, `gw-btn`, `gw-section`, `gw-toolbar`, `gw-search`, `gw-select`, `gw-table-wrap`, `gw-table`, `gw-env`, `gw-empty`, `gw-icon-tile`, `gw-code`, `gw-dialog`, `gw-stepper`, `gw-progress`, `gw-field`, `gw-input`, `gw-checkbox`, `gw-form`, `gw-copy-field`, `gw-toast`.

**Requirement coverage:**

- Core principle: inject secrets only at the connector and mask returned results.
- Administrator scenario: encrypt a password without redisplaying it, or reference Vault/GCP SM/AWS SM instead of copying it.
- Write-only secrets, versions and rotation, master key outside the database with uninterrupted rotation, references, GCP impersonation, AWS AssumeRole with ExternalId, Vault SSH CA, and dynamic accounts.
- Secret-leak and key-loss protection: mode 0400, separate key backup, and OS protection through safeStorage.
- Stack: Google Tink envelope encryption with a per-secret data key, Spring Vault/OpenBao, GCP and AWS secret stores.
- BLUEPRINT sections 1 (write-only secrets), 3 (Secrets traceability), and 6 (Secrets screen).

### Endpoints

[Reference screen](pages/endpoints.html)

List of the 3 MCP endpoints (dev, stage-readonly, incident-prod). Each card shows at a glance: URL with Copy, connections count with env markers, connection health, tools by mode (read only / approval / off), the AI apps using it (client marks), active tokens with expiry warning, approvals waiting, and the risky-combination warning (compact). Create endpoint is the one primary action: a dialog with slug, live URL preview, optional description and a starting template (Empty / Read-only dev / Incident response). Each card has View activity, Open in Playground, Configure and a More menu (Duplicate dialog, Disable with Undo toast — a disabled endpoint's menu offers Enable instead —, Delete with a danger confirm that names the endpoint).

**Compositions:** `risk-callout (--compact, present and partial states)`.

**Components:** `gw-shell / gw-titlebar / gw-sidebar / gw-nav (canonical shell)`, `gw-page`, `gw-page-header`, `gw-card`, `gw-stack`, `gw-icon-tile`, `gw-kv (--inline, --dense)`, `gw-copy-field`, `gw-env`, `gw-status`, `gw-mode`, `gw-client-mark`, `gw-badge`, `gw-counter`, `gw-alert (--compact, --info)`, `gw-menu-host / gw-menu (__item--danger, __divider)`, `gw-dialog (--md, --sm, --danger)`, `gw-field / gw-input / gw-input-group`, `gw-choice-card (--compact)`, `gw-btn (--primary, --ghost, --danger, --icon, --link)`, `gw-divider`, `gw-toast via gwToast`.

**Requirement coverage:**

- MCP endpoints: /mcp/<slug>, selected connections and tools, and tokens.
- Administrator scenario: assemble the dev endpoint.
- Security: highlight dangerous combinations and split access when necessary.
- claude.ai access through a tunnel: stage-readonly is configured for publication while the tunnel is off.
- Audit: View activity links to activity.html?q=endpoint:<slug>.
- BLUEPRINT sections 6 (Endpoints), 3 (risk-callout), and 9 (endpoints, clients, and tokens).

### Endpoint dev

[Reference screen](pages/endpoint-detail.html)

Everything about endpoint dev in one place. Header: breadcrumb Endpoints › dev, h1 dev (mono), description, meta (Running status, URL copy field, MCP revisions 2025-11-25 · 2026-07-28, sign-in badge Tokens · OAuth off, link to 3 approvals waiting), actions (View activity, Open in Playground, More menu: Duplicate, Disable with Undo, Delete endpoint as danger confirm). Full risk-callout (3 ingredients, Review tools, Split endpoint, Dismiss with reason). setup-map --wiring: Claude Desktop + Codex connected, Cursor detected → endpoint dev (modes, policy, limits) → 8 connections with env and health. Tabs (real tabpanels, deep-linkable by #tools/#connections/#tokens/#clients/#settings): Tools & policies (tool-policy-list with all 22 §9 tools in 9 groups, annotations incl. Open world, Off/Read only/Approval with destructive lock, per-tool limits inherited from the env policy, working search + mode filter + live summary + empty state, working Set all to…); Connections (billing-api unreachable alert, table with type, target, tools, health, View activity, Remove with danger confirm, Add connection dialog); Tokens (token-issue table incl. the Claude Desktop bridge row, Issue token → one-time result step with guard, Revoke confirms); AI apps (client-card --compact: Claude Desktop, Codex, Cursor with Connect diff dialog — on incident-prod the token and browser choices are unavailable and the bridge is chosen — and Disconnect confirms); Add connection dialog: a connection's tools start in the default modes of its type, and a prod connection can't go on a non-prod endpoint (aria-disabled row with the core's reason; 'A prod connection goes only on a prod endpoint, where the prod policy applies'); the unreachable alert names the time of the last check ('at 13:42'), more than two unreachable connections share one alert; after Split a toast with 'Open <new>' leads to the new endpoint's AI apps tab; Settings (settings-layout: Instructions for agents textarea --code with the approval rules sent in instructions + counter; Limits: max response size, rate limits per token and per tool, usage meter; Authorization: OAuth 2.1 switch with audience = endpoint URL, resource metadata, PKCE S256, CIMD; Protocol: supported revisions 2026-07-28 and 2025-11-25 with per-client usage in the last 7 days; save bars appear only with unsaved changes).

**Compositions:** `risk-callout (full, present)`, `setup-map (--wiring)`, `tool-policy-list`, `token-issue`, `client-card (--compact + connect / disconnect dialogs)`, `settings-layout`.

**Components:** `gw-shell / gw-titlebar / gw-sidebar / gw-nav (canonical shell)`, `gw-page`, `gw-page-header`, `gw-breadcrumb`, `gw-section`, `gw-tabs`, `gw-card (--flush, --muted)`, `gw-stack`, `gw-toolbar`, `gw-search (--sm)`, `gw-menu-host / gw-menu (listbox, menu, --wide, --right, __item--danger)`, `gw-table-wrap / gw-table`, `gw-segmented (--sm, data-tone)`, `gw-mode`, `gw-env`, `gw-status`, `gw-badge (--outline, --sm)`, `gw-counter`, `gw-code`, `gw-copy-field (+ --secret-once)`, `gw-kv (--dense)`, `gw-alert (--warning, --danger, --info, --compact)`, `gw-empty (--compact)`, `gw-dialog (--sm, --md, --lg, --danger)`, `gw-field / gw-input / gw-input-group / gw-select / gw-textarea--code / gw-checkbox / gw-switch`, `gw-tag / gw-tag-list`, `gw-form (--sticky-actions)`, `gw-meter`, `gw-diff`, `gw-client-mark`, `gw-icon-tile`, `gw-divider`, `gw-btn (--primary, --secondary, --ghost, --danger, --danger-ghost, --link, --icon)`, `gw-toast via gwToast`.

**Requirement coverage:**

- Administrator scenario: assemble dev with read access, approved changes, logs, SSH runbooks, and approved Kubernetes restarts; revoke access in one action.
- Endpoint tokens support multiple names, TTL, applicable IP allowlists, instant revocation, one-time display, and hash-only storage. OAuth uses PKCE S256, Protected Resource Metadata, endpoint audience, and CIMD. Include Playground.
- Policies: Off/Read only/Approval/Bypass, approval by default, per-token/tool rate limits, response limits, and timeouts.
- MCP hints include readOnly/destructive/idempotent/openWorld; server instructions explain approvals and approval_status; support protocol revisions 2025-11-25 and 2026-07-28.
- One-click connection: show the exact configuration diff, back up before writing, and revoke on Disconnect.
- Security: dangerous combinations, prompt injection, short token TTL, revocation, and a .mcpb bridge without a token in configuration.
- Audit: View activity and Last used link to filtered Activity.

### AI apps · GateRook

[Reference screen](pages/clients.html)

Connect AI apps to an endpoint in one click and see their state at a glance. Header: counts (2 connected, 1 detected but not connected, 1 not installed), when the computer was last scanned, and a Scan again button. How it works starts collapsed: find the AI app, show the diff, back up then write, Disconnect removes the entry and revokes the token. On this computer: every app starts as a compact disclosure row with its mark, name, connection status and chevron. Opening Cursor (detected) reveals the Connect flow in place — endpoint select, a prod warning if incident-prod is picked, the sign-in choice (token in the file, written in plain text to ~/.cursor/mcp.json, or sign in through the browser; on incident-prod both stay unavailable with 'Not on prod endpoints' and 'Through the bridge' is chosen), the token step (name cursor-laptop, expires in 7 days), the exact diff of ~/.cursor/mcp.json with the core's permissions line and notes, the backup path and Connect Cursor as the card's primary button. Opening Claude Desktop (one .mcpb bridge extension per endpoint) reveals one row per endpoint (dev, incident-prod with a prod marker) and Disconnect on each, plus Add endpoint and Remove bridge. The compact Codex row expands to its details (~/.codex/config.toml, http_headers_helper as one command string running ~/.gaterook/bin/gw-bridge headers, grant codex-laptop with no gwt_… value, the core's managed entry in a collapsed TOML block); Antigravity (not installed) expands to installation and scan actions. In the browser: the claude.ai row shows Tunnel off and expands to what gets published (/mcp/stage-readonly, /oauth/*, /.well-known/oauth-*) and what stays local, the three gates (Anthropic IP ranges reach /mcp/stage-readonly, shown read-only with a link to Settings › Tunnel › Callers, while the sign-in page opens in your own browser; each sign-in allowed on this computer; the endpoint's own modes), the tunnel history (public today 08:40 to 12:10, off since 12:10), last call 12:08, a 'Signed in' table with Revoke and Publish for claude.ai (warning confirm listing what becomes public; a quick tunnel gets a new address each time it starts; Undo never re-publishes). The Claude Desktop and Codex cards carry the trust boundary note. Manual setup starts collapsed: the endpoint URL with Copy, tokens from Endpoints › Tokens, the core's snippets (MCP Inspector 2.12 with --config gw-inspector.json: the token sits in a 0600 file, never on the command line), and the token line without an app name. Disconnects go through danger dialogs that name the client. Two more demo states, as on Overview: ?state=setup (dev-db on endpoint dev, no AI app yet) shows the apps as client-card --compact rows in Overview's order, and the hash from Overview's guide (#cl-cd-t, #cl-cx-t, #cl-cu-t) opens that app's Connect review inline in one full-width card on top: what it can use in words (one endpoint, no select), exactly what will change (Claude Desktop: the .mcpb extension and its command, no file, no token, with a collapsed claude_desktop_config.json fallback; Codex and Cursor: diff + backup), the card's one primary Connect <app>, focus on the card's heading. After Connect: Waiting for Claude Desktop then Connected, Codex Needs restart, Cursor Connected or Waiting for Cursor, then Check that it works with the prompt to copy; header counts follow; Disconnect puts the card back. claude.ai is never published (Publish dev is secondary, behind the warning confirm); Manual setup is collapsed. ?state=first-run: the same rows with Connect aria-disabled and the reason, Publish aria-disabled with its reason, the header's Add connection as the only primary, no manual setup.

**Compositions:** `how-it-works`, `client-card`.

**Components:** `gw-page`, `gw-page-header`, `gw-section`, `gw-card`, `gw-stack`, `gw-grid`, `gw-status`, `gw-divider`, `gw-client-mark`, `gw-kv`, `gw-btn`, `gw-btn__chevron`, `gw-alert`, `gw-field`, `gw-select`, `gw-diff`, `gw-code`, `gw-code-block`, `gw-tok-*`, `gw-copy-field`, `gw-env`, `gw-tabs --contained`, `gw-dialog --sm --md --danger --warning`, `gw-icon-tile`, `gw-counter`, `gw-spinner`, `gw-toast`, `gw-choice-card --compact (Cursor sign-in choice)`, `gw-table --dense (endpoints of Claude Desktop, Signed in)`, `gw-input --mono (token name)`, `gw-status --checking (Waiting for Claude Desktop / Waiting for Cursor)`, `gw-client-card --compact rows and native disclosure buttons (in use; setup and first-run onboarding rows)`, `gw-copy-field (the prompt for Check that it works)`.

**Requirement coverage:**

- Administrator scenario: Connect Claude Desktop, Codex, Cursor, or Antigravity; configure a tunnel for claude.ai.
- One-click client configurations: .mcpb bridge; Codex config.toml with http_headers_helper and default_tools_approval_mode; ~/.cursor/mcp.json; ~/.gemini/config/mcp_config.json; claude.ai through a tunnel. Back up changes; Disconnect removes the entry and revokes access.
- Deployment: Anthropic servers reach published MCP/OAuth routes only; Publish for claude.ai requires confirmation.
- Endpoint authentication: tokens and OAuth for claude.ai.
- Token-file risk: Claude Desktop and Codex obtain tokens on the fly; disclose plaintext Cursor tokens and offer OAuth where supported. Keep the sole Anthropic caller allowlist control in Settings > Tunnel > Callers.
- BLUEPRINT section 3: client connection, tunnel exposure, configuration diff, and backup.

### Playground · GateRook

[Reference screen](pages/playground.html)

Call a tool the way an agent does and see exactly what it gets back. Layout is gw-page--full with gw-split--40. Left side: endpoint select (dev, with its URL), then the tool picker (tool-policy-list picker variant) with search pre-filled "sql" (4 of 22 tools match, with mode chips), annotation badges, mode badges and sql_query selected. Below it an Arguments form card generated from the inputSchema, in the schema renderer's words: connection select with an env marker and host, sql textarea --code, limit ('Max rows; the endpoint caps it. Integer, 50 when empty, from 1 to 1,000.', no suffix), errors such as 'sql is required.' and 'limit: 5000 is above the maximum. Use 1000 or less.'. A Form | JSON contained-tab toggle edits the same arguments. Run is the primary button (⌘ Enter). A warning alert ('This call needs approval. Running it sends you an approval request, exactly like an agent would.') appears for Approval-mode tools; an Off tool blocks Run and says why. While the request waits the result says 'Not run yet: request … waits for your decision' with Review request; after the decision the page asks approval_status like an agent and that answer becomes the last run. On incident-prod ssh_run is Read only and the runbook flag decides (service-status runs at once, restart-api waits for approval). Right side: 'What the agent receives' with a meta strip (Allowed, 38 ms, 3.1 KB · 12 rows, 2 masked fields, trace id with Copy) and Structured / Text / JSON-RPC tabs. Structured is a dense table with ‹masked› values; Text is content[0].text; JSON-RPC shows the request (Mcp-Method / Mcp-Name headers, MCP-Protocol-Version 2026-07-28, traceparent in _meta, Authorization: Bearer ‹masked› — Playground calls carry this app's session instead of an endpoint token) and the response. Then a collapsed Event details section (event-detail: request, policy decision timeline, timing waterfall, filters) with Open in Activity, an empty-result state and a loading skeleton, and a History table of the last 5 runs (allowed, denied by policy, error), each row opening in Activity. A demo script provides search, tool picking, Form↔JSON sync with field errors, Run (loading → result, approval → request-sent toast, off → blocked) and ?tool= deep links.

**Compositions:** `tool-policy-list (Playground picker variant)`, `event-detail`.

**Components:** `gw-page --full`, `gw-page-header`, `gw-split --40`, `gw-section`, `gw-field`, `gw-select`, `gw-search --sm`, `gw-toolbar`, `gw-card --flush`, `gw-table-wrap`, `gw-table --interactive --dense`, `gw-badge --outline --sm`, `gw-mode`, `gw-env`, `gw-icon-tile`, `gw-tabs`, `gw-tabs --contained`, `gw-textarea --code`, `gw-input --mono`, `gw-input-group`, `gw-form__row`, `gw-alert --warning --neutral`, `gw-btn --primary --ghost --link`, `gw-btn__kbd`, `gw-btn__chevron`, `gw-kv --inline`, `gw-copy-field`, `gw-code-block --scroll`, `gw-tok-*`, `gw-code --secret`, `gw-timeline`, `gw-waterfall`, `gw-legend`, `gw-client-mark --generic`, `gw-empty --compact`, `gw-skeleton`, `gw-spinner`, `gw-toast`, `gw-divider`.

**Requirement coverage:**

- Playground: invoke a tool manually and show exactly what the agent receives.
- MCP hints and payloads: readOnlyHint/destructiveHint/idempotentHint, outputSchema and structuredContent; 2026-07-28 is sessionless with Mcp-Method/Mcp-Name and trace context in _meta.
- Modes Off/Read only/Approval/Bypass; mutations require approval unless Bypass is explicitly selected; respect row limits.
- Mask secrets and PII in results.
- Audit every call with Activity links, trace ID, and decision.
- BLUEPRINT section 3: endpoint Playground.

### Activity

[Reference screen](pages/activity.html)

The append-only call log: every agent call through the gateway, searchable and filterable. It has a structured query with an example filter applied (-tool:logs_*, from the saved view 'Without log reads'), Last 24 hours, Live, saved views, facets with counts that sum to 946 of 1,287, and an interactive decision histogram. An Events table has 16 rows with the columns Time | Decision | Tool · connection (stacked) | AI app · endpoint (stacked) | Duration (extra columns only as gw-table__cell--optional; no sideways scroll at 1440 × 900): mono literals, env markers, client marks, right-aligned duration and size, ISO time titles, Review links on waiting calls, Copy link on every row, a selected row, sticky header and Load older. Three rows open real gw-drawer event details: the allowed sql_query, the DROP TABLE on prod-replica denied by prod.sql.block_ddl, and an approved k8s_rollout_restart with its approval timeline, exact kubectl action and 'Agent's note · unverified'. A Traces tab shows agent tasks: two in progress waiting for a person, b81e44 with denied DDL, and 4f2a9c 'Fix failing order sync' with 23 calls in 18 min across Explore, Change, Wait for deploy and Verify. Duration: waits and runs to the second, 'N so far' for a waiting or running call, 'Not run' for a call the policy denied (it never ran). Also: an Export menu (CSV, JSONL — a success toast '1,287 calls exported' with the path after the core writes the file —, Send to SIEM… dialog with the destinations of Settings › Audit and retention by name and CEF unavailable), a 'Save current view…' dialog with a Name field, empty Traces and Setup changes states, an 'Unknown filter' warning in the core's words and a load-error alert, one-click 'Revoke token codex-laptop' through a danger confirm, a masking info alert, a custom-range dialog, and a no-results empty state (select the 02:00 or 04:00 bar). Setup changes (&view=changes) is the second audit log: Time | What | Change | By | Reason for the 4 changes of the day — tunnel on 08:40, ssh_exec Approval → Off 09:12, http_get Read only → Off 10:40, tunnel off 12:10 — all 'You' on the desktop. Links: ?q=<URL-encoded query> (a repeated field = or), &range=<1h|24h|7d|30d>, &view=traces, &view=changes, #event-<id> (opens the event in any range; an event the audit log no longer keeps says 'Event not in the audit log'); Copy link copies gaterook://activity/events/<id>.

**Compositions:** `log-explorer`, `event-detail`.

**Components:** `gw-shell`, `gw-page--full`, `gw-page-header`, `gw-alert--info --compact`, `gw-toolbar`, `gw-search`, `gw-menu-host / gw-menu / gw-popover`, `gw-btn (default, ghost, link, sm, icon, danger-ghost, danger, primary, aria-pressed Live)`, `gw-tag / gw-tag-list`, `gw-counter`, `gw-field (fieldset facets)`, `gw-checkbox`, `gw-radio`, `gw-select`, `gw-input`, `gw-env`, `gw-client-mark`, `gw-chart (interactive histogram)`, `gw-legend`, `gw-tabs--contained`, `gw-table-wrap / gw-table (--interactive --sticky, sort, tr[aria-selected], row-link, cell--num/--mono/--actions)`, `gw-badge (decision vocabulary §7)`, `gw-paginator (load older)`, `gw-empty`, `gw-card`, `gw-section`, `gw-stack`, `gw-kv (default, --inline, --dense)`, `gw-status`, `gw-timeline`, `gw-code`, `gw-code-block (--wrap, --scroll) + gw-tok-*`, `gw-copy-field`, `gw-waterfall`, `gw-drawer--lg`, `gw-dialog (--sm, --md, --danger)`, `gw-icon-tile`, `gw-divider`.

**Requirement coverage:**

- Administrator scenario: inspect audit and revoke access.
- Agent task lifecycle: Explore > Change > Wait for deploy > Verify, linked by traces.
- Audit event fields, append-only storage, SIEM/S3 export, UI browsing, and filters.
- Approval lifecycle: request, notification, decision, execution; exact action; timeout denies.
- MCP carries OpenTelemetry trace context.
- Security: masking, no secrets in logs, unverified agent notes, production DDL restrictions.
- Audit retention is at least one year; append-only event records.
- BLUEPRINT section 6 (Activity), section 5 (log-explorer/event-detail), and DESIGN section 8 (Activity and analysis).

### Insights

[Reference screen](pages/insights.html)

Analysis of agent calls over the selected range. It reuses log-explorer rows 1 and 2 as a compact filter bar (query, Last 24 hours, result count) and has an 'Open in Activity' link that carries range and filters. Six KPI tiles: calls 1,284 (+12%, plus 3 waiting), approved 46 (median wait 47 s), denied 17 (+5, bad), errors 8, gateway overhead p95 31 ms with a meter against the 50 ms target, and 61 agent tasks. Unusual activity has 3 plain-language anomalies with links: DDL tried on prod within one task, sql_query on prod-replica at 4x the 7-day average, cache-redis 45x slower. Cards come in three groups. Volume: calls over time stacked by decision, top tools (hbar), by AI app (hbar with client marks plus an empty state for Cursor, Antigravity and claude.ai; sentences from the aggregates' facts: '19 calls from 08:41 to 12:08, all allowed, to stage-db through stage-readonly'). Decisions: approvals split, wait distribution and slowest decisions table; denied by rule table with a link per rule. Latency and errors: gateway vs resource p95 per connection, and errors by connection with health and links. Every chart has a one-sentence takeaway as its card description, role=img with a finding-style aria-label, an sr-only data table, and a View in Activity link with the matching query and the page's range (range=24h).

**Compositions:** `log-explorer (rows 1–2 only)`.

**Components:** `gw-shell`, `gw-page`, `gw-page-header`, `gw-toolbar`, `gw-search`, `gw-menu-host / gw-menu / gw-popover`, `gw-btn (default, ghost, link, sm, icon)`, `gw-grid (--2, --3)`, `gw-stat (+ __delta data-sentiment, __chart sparkline, __meta)`, `gw-sparkline`, `gw-meter`, `gw-section`, `gw-stack`, `gw-alert (--danger, --warning, --compact)`, `gw-card (header, description, footer, footer--split)`, `gw-chart (stacked histogram, --hbar with series and tones)`, `gw-legend`, `gw-kv--inline`, `gw-table-wrap / gw-table (--dense, --interactive)`, `gw-badge`, `gw-env`, `gw-status`, `gw-client-mark`, `gw-empty--compact`, `gw-code`.

**Requirement coverage:**

- Audit browsing and filtering.
- Agent task KPIs with links to traces.
- Latency target: gateway overhead p95 <= 50 ms, shown in the KPI meter and connection breakdown.
- Policy decisions include expired approval requests and automatic denial on timeout.
- Security insights: production DDL, blocked metadata SSRF, and excessive production activity.
- Clients without calls link to Connect your AI app.

### Settings · GateRook

[Reference screen](pages/settings.html)

Every app-wide setting, split into five groups with a sticky settings nav (a 'Jump to section' select below 1280px). Workspace: General covers start at login, start minimized, automatic update checks with a busy 'Check now', the core port 7465 shown read-only with a 'Change port' warning dialog (example 7475; endpoint URLs change; clients whose config holds the URL — Codex here — are connected again; Claude Desktop's bridge asks the app for the address; no claude.ai row), and the runtime rows 'Core: Native core' and 'Plugin runtime: Bundled JRE 25, started only for Java plugins'. Appearance has theme and contrast radios wired through name=gw-theme/gw-contrast. Security: Authorization has a how-it-works strip, MCP access (default token lifetime 7 days; the token IP allowlist is a server-mode topic; an alert that inspector-debug expires in 3 days), OAuth 2.1 for claude.ai (issuer 'None yet' while the quick tunnel is off, tunnel-off warning, CIMD recommended / DCR deprecated, the Anthropic IP allowlist shown read-only with a link to Tunnel › Callers, PKCE and audience facts) and Sign-in to the UI disabled with a server-mode reason. Users & roles is a server-mode preview: a table of the 4 people, a roles list and a disabled Invite button. Approvals: Notifications: desktop on (Review and Deny), Slack connected (workspace acme, direct messages to linked approvers recommended, channel #deploy-approvals selected, last delivery 14:02:30, danger Disconnect), Telegram not set up (Connect Telegram bot dialog; once connected, its status with the chat and 'Change bot or chat'), a 'Connect Slack' dialog built the same way after Disconnect (bot token xoxb-, app-level token xapp-, Socket Mode, 'Check tokens and connect'), Slack and Telegram never offering Approve for prod, Send test notification, and a 'Preview messages' dialog with approval-notification tabs. Approval rules explains approval by default and the explicit Bypass exception, where decisions can be made, 'Require a comment on prod' (on by default) and the per-environment table linking to policies.html#<env>-approvals. Network: Tunnel has a how-it-works strip, an Off status with a Turn on warning dialog and a demo on-state with a new quick-tunnel address, the three gates (gate 1: Anthropic IP ranges reach /mcp/stage-readonly; the sign-in page opens in your own browser), Cloudflare / ngrok choice cards (a quick tunnel gets a new address each time it starts; a named tunnel keeps a stable hostname), what is and is never published, and Callers (#settings-tunnel-callers): the ONE Anthropic IP allowlist switch (on). Turning the tunnel on again always goes through the warning confirm; Undo never re-publishes. Local API has the fixed 127.0.0.1, Host / Origin / token checks always on, and allowed origins with add, validate, remove and an empty state. Data: Audit & retention has an append-only notice, retention of 1 / 2 / 3 / 5 years (default 1 year, never less), export cards for SIEM / S3 / BigQuery with the BigQuery error inside its card (the core's error text under Show error details), and Export now. Backup has daily database backups, the last backup, Back up now, and a master key reminder linking to secrets.html#master-key. Demo: the Authorization Save bar shows unsaved changes; other Save bars appear on edit. Anchors are #settings-<section> (general, appearance, authorization, users, notifications, approval-rules, tunnel, tunnel-callers, local-api, audit, backup). There are no consent previews: the sign-in request opens by itself when it arrives.

**Compositions:** `settings-layout`, `how-it-works (--vertical, x2: authorization, tunnel)`, `approval-notification (desktop mac/win, Slack, Telegram in the preview dialog)`.

**Components:** `gw-shell / gw-titlebar / gw-sidebar / gw-nav (canonical shell)`, `gw-page / gw-page-header`, `gw-section__header / gw-divider`, `gw-form (--sticky-actions, __section, __section-header, __legend, __description, __rows, __actions, __note)`, `gw-field / gw-select / gw-input (--mono, readonly) / gw-input-group (__addon)`, `gw-switch / gw-radio / gw-checkbox (with __hint) / gw-segmented / gw-choice-card`, `gw-secret (data-state=stored)`, `gw-copy-field`, `gw-alert (--info --warning --danger --neutral, --compact)`, `gw-status`, `gw-badge`, `gw-kv (default, --dense, --inline)`, `gw-table-wrap / gw-table`, `gw-avatar --sm`, `gw-tag / gw-tag-list / gw-tag__remove`, `gw-empty --compact`, `gw-card (--muted, header/body)`, `gw-stack`, `gw-grid (--2, --auto)`, `gw-tabs`, `gw-dialog (--md --warning, --sm --danger, --lg)`, `gw-code / gw-code-block (--wrap) / gw-tok-*`, `gw-env --sm`, `gw-client-mark --generic --sm / gw-icon-tile / gw-logo`, `gw-btn (--primary, default, --ghost, --danger-ghost, --danger, --link, --approve inside inert channel previews only)`, `gw-spinner (busy Check now)`, `gw-toast (via kit.js gwToast / data-gw-toast)`, `gw-kbd`.

**Requirement coverage:**

- Deployment: desktop core binds to 127.0.0.1 and is a native image; the bundled JRE is only for the Java plugin host. claude.ai tunnel publishes MCP/OAuth, leaving UI local.
- Endpoint tokens: TTL, applicable IP allowlists, and revocation. OAuth uses Protected Resource Metadata, PKCE S256, endpoint audience, and CIMD; dynamic registration is deprecated.
- Notifications via desktop, Slack, and Telegram; decisions with comments; timeout denies; mutations require approval unless an endpoint tool explicitly uses Bypass.
- Append-only audit with SIEM, S3, and BigQuery export.
- Server-mode previews: Owner/Admin/Developer/Approver/Viewer, OIDC providers, admin 2FA. Desktop has one local user.
- Security: validate Origin and Host against DNS rebinding, require loopback tokens, maintain Anthropic caller allowlists, and keep master-key backups separate.
- Stack: electron-updater, Cloudflare Tunnel or ngrok, and desktop SQLite.
- Desktop core starts with the app; audit retains at least one year and fails closed.

### Core starting · GateRook

[Reference screen](pages/core-starting.html)

The window shown while the gateway core is unavailable. It uses no gw-shell and no data-gw-nav: the status-screen element is the page's <main>. The live state is Unlock required: a neutral lock tile, an explanation of OS protection, and Quit + Unlock GateRook. The desktop's authentication metadata determines the explanation: Touch ID or the Mac password when Touch ID is available, Windows Hello with the configured face, fingerprint, or PIN when Hello is available, or the generic OS access/password prompt otherwise. Authentication starts only after Unlock GateRook and takes place entirely in the system dialog; passwords and biometric data never enter the renderer. The Safe Storage entry name is shown only when supplied for legacy macOS key access. The generic demo also covers waiting for the operating system with a busy button; the application names the selected authentication method in its accessible waiting text. The explanation says that denying access closes GateRook. Denial or cancellation closes the app without starting the core. No password field or metadata line is shown, and the core starts only after successful unlock.

Starting ('Starting the native core and the local API … about a second'): an indeterminate progress bar and vertical steps Native core done ('Native image · 0.1 s'), Master key done, Database migrations done, Local API on 127.0.0.1:7465 in progress, plus an sr-only status line. A small keyboard-accessible demo switcher at the bottom (gw-tabs--contained, arrows/Home/End) shows the other documented states. Port in use: the failed Local API step, an open 'Find the process' panel with lsof plus a Windows netstat line, then Quit and 'Use another port', which opens a warning dialog listing what changes (candidate 7475, since 7466 is the tunnel's public listener; clients with the URL in their config connect again; Claude Desktop's bridge asks the app for the new URL; a claude.ai row only for a named tunnel or an ngrok domain). Master key not found: the Master key step failed while Database migrations and Local API are done (the locked core has migrated and listens); a danger alert (the 8 secrets encrypted here become unreadable; the 3 references keep working; 11 in all, as in Secrets) with a danger-ghost 'Create a new key' that opens a danger dialog with the consequences and Cancel autofocused; Quit and 'Restore from backup' as the primary, which opens a dialog modelled on Back up master key (an optional passphrase, autofocused, then the system open dialog: 'Choose the backup file'). Stopped: exit facts in gw-kv, the last log lines collapsed, the 3 pending approvals kept, Open logs and 'Restart core'. Didn't start: any other ready:false reason, here DB_LOCKED ('Another core is using the database'), with the stopped card's facts, Open logs and 'Restart core'. A tiny script keeps <main> data-state and aria-labelledby in sync with the shown card.

**Compositions:** `status-screen`.

**Components:** `gw-logo --xl`, `gw-card (__body, __footer)`, `gw-icon-tile --lg --danger`, `gw-progress --indeterminate`, `gw-stepper --vertical (complete/current/error/upcoming, gw-spinner --sm in the current marker)`, `gw-alert --danger`, `gw-code-block / gw-code`, `gw-kv --dense`, `gw-stack --sm`, `gw-tabs --contained / gw-tabs__panel (demo switcher)`, `gw-dialog (--md --warning, --sm --danger)`, `gw-field / gw-input --mono`, `gw-env --sm`, `gw-btn (--primary, default, --ghost, --danger-ghost, --danger)`.

**Requirement coverage:**

- Deployment: Spring Boot core is a local native-image process bound to 127.0.0.1; the bundled JRE runs Java plugins only.
- Java 25, jlink plugin-host JRE, SQLite Flyway migrations, and Google Tink keysets; the core itself is a GraalVM native image.
- Desktop reliability: start the core with the app and preserve pending requests across a crash.
- Master-key loss: retain a separate copy; secrets cannot be recovered without it.
- BLUEPRINT sections 3 (native desktop core) and 6 (core-starting/status-screen).

## 7 · Shared status vocabulary

| Audit decision | Tone | Icon | Label |
| --- | --- | --- | --- |
| allowed | success | `check` | Allowed |
| approved | success | `user-check` | Approved |
| pending | warning | `hourglass` | Waiting for approval |
| denied | danger | `shield-x` | Denied by policy |
| rejected | danger | `user-x` | Rejected |
| expired | neutral | `timer-off` | Expired |
| error | danger | `circle-alert` | Error |

| Tool mode | Tone | Icon |
| --- | --- | --- |
| Off | neutral | `circle-off` |
| Read only | info | `eye` |
| Approval | warning | `user-check` |

Healthy, connected, and running use success. Slow, expiring, and needs restart use warning. Unreachable, failed, and revoked use danger. Unknown, unchecked, uninstalled, and expired tokens use neutral. Checking and starting use info with a spinner.

AI client labels are **Connected**, **Detected, not connected**, **Waiting for Claude Desktop/Cursor**, **Needs restart**, **Not installed**, and **Error**. Overview shortens the detected state to **Not connected**, with **Found on this computer** in metadata.

MCP annotations use Read only/info, Destructive/danger, Idempotent/neutral, and Open world/warning. External declarations always carry **Declared by <server>, not verified**. Undeclared external tools can only be Approval or Off. Read-only declarations require individual confirmation before enabling that mode.

Environment markers are `gw-env--dev` (teal), `--stage` (orange), and `--prod` (red), with text in every case.

| Navigation | Icon |
| --- | --- |
| Overview | `layout-dashboard` |
| Approvals | `user-check` |
| Connections | `database` |
| Connectors | `puzzle` |
| Secrets | `key-round` |
| Endpoints | `waypoints` |
| AI apps | `app-window` |
| Policies | `shield` |
| Playground | `flask-conical` |
| Activity | `activity` |
| Insights | `chart-column` |
| Settings | `settings` |

Resource icons: SQL `database`, MongoDB `leaf`, Redis `layers`, SSH `terminal`, Logs `scroll-text`, Kubernetes `ship-wheel`, GCP/AWS `cloud`, HTTP `globe`, MCP `plug`, plugin `puzzle`, template `file-code`. Check icon names with `npm run icons`.

## 8 · Writing and glossary

Use English sentence case. Buttons name verbs and objects; destructive confirmations name the exact object. Render literals with monospaced kit classes and no ligatures. Lists use relative time with absolute ISO time in `title` and details, preserving the local zone. Durations include units. Errors explain the problem and a next action.

| Term | Meaning |
| --- | --- |
| Connection | Configured resource with a type, environment, and credential source |
| Connector | Resource type and its tools: built-in, template, plugin, or MCP server |
| Endpoint | `/mcp/<slug>` URL with selected connections, tool modes, and authentication |
| Tool | Agent-callable function such as `sql_query` or `ssh_run` |
| Mode | Off, Read only, Approval, or Bypass for one endpoint/tool pair |
| AI app | MCP client invoking the gateway |
| Token | Client access credential shown once when issued manually |
| Approval request | Proposed mutation with its exact action |
| Policy | Environment guardrails, allowlists, limits, and timeouts |
| Runbook | Preset SSH command with typed parameters |
| Secret reference | Pointer to a secret held by an external store |
| Trace | Calls belonging to one task, joined by OpenTelemetry trace ID |

## 9 · Shared demo world

All names, addresses, tokens, metrics, and timestamps below are fictional reference data. They make navigation between screens consistent and are not production configuration or measured release claims.

### Time, identity, and runtime

The shared clock is **2026-10-22 at 14:30 CEST (+02:00)**. Events never occur later; only deadlines do. Two minutes ago means 14:28. Use ISO `datetime` and titles.

The core runs on `127.0.0.1:7465`, version `0.4.0`; the tunnel listener is `127.0.0.1:7466`, so an alternative core-port example is 7475. The reference packaged core is native and starts in about a second. Only Java plugins start the bundled JRE 25. The database is `~/.gaterook/gateway.db` and the master key is `~/.gaterook/master.keyset`, mode 0400 with macOS Keychain protection; its last separate backup was 12 days ago.

The trust boundary is the OS account. Explain protection against other users and copied files accurately; it does not isolate the key from programs running as the user, including terminal-capable agents.

Desktop has one user and one approver: **You**. Named users appear only in server previews: Alex Morgan (Owner), Priya Nair (Admin + Approver), Sam Lee (Developer), and Jonas Weber (Viewer). Multiple-approver examples are server previews only.

### Endpoints and access

| Endpoint | Connections and modes | Clients | Calls, 24 h |
| --- | --- | --- | --- |
| `dev` | 8 connections, 22 tool/connection pairs: 14 read only, 3 approval, 5 off | Claude Desktop and Codex | 1,157 |
| `stage-readonly` | `stage-db`, `aws-stage`, `billing-api`; read-only access | claude.ai through tunnel | 19 |
| `incident-prod` | `prod-replica`, `prod-logs`, `prod-vm`; 10 tools: 7 read only, 3 off | Claude Desktop bridge | 111 |

The local URL is `http://127.0.0.1:7465/mcp/<slug>`. `ssh_run` can be Read only for read-only runbooks, while a mutating runbook such as `restart-api` requires approval unless its endpoint tool explicitly uses Bypass.

| Connection in `dev` | Read only | Approval | Off |
| --- | --- | --- | --- |
| `dev-db` | `sql_list_schemas`, `sql_describe_table`, `sql_query` | `sql_execute` | — |
| `orders-mongo` | `mongo_find`, `mongo_aggregate` | — | — |
| `cache-redis` | `redis_get`, `redis_scan` | — | — |
| `dev-vm` | `ssh_run` for read-only runbooks | — | `ssh_exec`, changed from Approval at 09:12 |
| `app-logs` | `logs_search` | — | `logs_tail` |
| `dev-cluster` | `k8s_get` | `k8s_rollout_restart` | `k8s_logs`, `k8s_apply` |
| `aws-stage` | `aws_describe`, `aws_metrics` | — | — |
| `billing-api` | — | `http_request` | `http_get`, changed from Read only at 10:40 |
| Shared | `list_connections`, `describe_connection` | — | — |

The service tool `approval_status` remains available separately.

### Connectors and extensions

Built-in tool counts are PostgreSQL 4, MySQL 4, MongoDB 2, Redis 2, SSH 2, Logs 2, Kubernetes 4, GCP 2, AWS 2, and HTTP API 2. Logs is one connector with Loki, GCP Cloud Logging, AWS CloudWatch, and OpenSearch backends. OpenSearch is not a plugin.

The Jira Service Desk plugin is installed at 1.2.0; catalogue 1.3.0 adds read-only `jira_request_sla`. The `github-mcp` demo server is version 0.6.2 with a signed catalogue entry and lockfile, installed with `npm ci --ignore-scripts`. Its resolved argv runs Node on the installed entry file, not `npx`. Its environment contains only its own token, PATH, HOME, and required basic variables. Its 26 tools have no read-only declarations and are on no endpoint initially. They enter Approval when added and can only be Approval or Off.

### Connections

| Name | Type/environment | Example target or credential source | State / calls in 24 h |
| --- | --- | --- | --- |
| `dev-db` | PostgreSQL 16 / dev | `db.dev.internal:5432/app`, encrypted password | Healthy 12 ms / 472 |
| `stage-db` | PostgreSQL / stage | `db.stage.internal:5432/app`, `vault://kv/stage/db#password` | Healthy / 19 |
| `prod-replica` | PostgreSQL read replica / prod | `replica.prod.internal:5432/app`, Vault dynamic account | Healthy / 69 |
| `orders-mongo` | MongoDB / dev | `mongo.dev.internal:27017/orders` | Healthy / 23 |
| `cache-redis` | Redis / dev | `cache.dev.internal:6379` | Slow 1.8 s / 31 |
| `dev-vm` | SSH / dev | `10.20.0.14`, SSH CA certificate | Healthy / 103 |
| `app-logs` | Loki / dev | `loki.dev.internal:3100` | Healthy / 300 |
| `prod-logs` | GCP Cloud Logging / prod | `projects/acme-prod`, impersonated reader | Healthy / 41 |
| `dev-cluster` | Kubernetes / dev | `k8s.dev.internal:6443`, namespace `app-dev` | Healthy / 166 |
| `aws-stage` | AWS / stage | `eu-central-1`, `acme-stage`, AssumeRole `gw-readonly` | Healthy / 0 |
| `billing-api` | HTTP / stage | `https://billing.stage.acme.dev`, GCP SM token | Unreachable / 62 |
| `prod-vm` | SSH / prod | `10.40.0.21`, SSH CA; runbooks only | Healthy / 1 |

The production log identity is `gw-reader@acme-prod.iam.gserviceaccount.com`. Billing's token reference is `gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest`; its separate webhook-secret reference uses an unconnected AWS store.

### Secrets

There are 11 secret rows: eight encrypted locally and three external references.

| Locally encrypted | Version |
| --- | --- |
| `dev-db/password` | 3 |
| `orders-mongo/password` | 2 |
| `cache-redis/password` | 1 |
| `app-logs/token` | 1 |
| `dev-cluster/token` | 4 |
| `aws-stage/access-key` | 2 |
| `github-mcp/token` | 1 |
| `vault/approle-secret-id` | 5 |

References are `stage-db/password` (Vault v7), `billing-api/token` (GCP SM v4), and `billing-api/webhook-secret` (AWS SM disconnected, version unknown). Losing the key makes the eight local ciphertexts unreadable; references themselves remain usable subject to their provider authentication. Dynamic credentials are not stored-secret rows: Vault dynamic for `prod-replica`, GCP impersonation for `prod-logs`, SSH CA for both VMs, and AssumeRole for `aws-stage`.

Rotate is supported for `dev-db/password`. Other saved values retain a disabled Rotate action with the core explanation: replace the value with one issued by its own source. For Vault login, create a new value in the store console and replace it locally.

### Policies

Every SQL read runs in a READ ONLY transaction ending with ROLLBACK. Mutations require approval under `<env>.<section>.write-approval` unless the endpoint tool explicitly uses Bypass. Policy denials and limits still apply.

| Setting | dev | stage | prod |
| --- | --- | --- | --- |
| SQL row limit | 1,000 | Inherits unless overridden | 100 |
| Statement timeout | 5 s | Inherits unless overridden | 3 s |
| Approval window | 5 min | 10 min | 10 min |
| Approval comment | Optional | Optional | Required by default |
| Kubernetes verbs | get/list/describe/logs/restart/apply | Inherits unless overridden | get/list/describe/logs |
| DDL | Subject to policy/approval | Subject to policy/approval | CREATE/ALTER/DROP/TRUNCATE denied |

Dev command timeout is 30 s and tool timeout is 10 s. Logs allow up to 168 hours, 100 entries, and an eight-second tail wait. Stage inherits unset values from dev; production has independent stricter defaults. Demo summaries are **4 set on dev · base for stage**, **3 stage defaults · 6 set on stage · 32 inherited from dev**, and **24 prod defaults · 2 set on prod**.

HTTP private networks remain blocked unless an explicit range is allowed. The demo's only HTTP connection is on stage, so dev HTTP egress displays an empty-state card with read-only rules. Environment reset lists all removed overrides, including non-inherited ones. Desktop approval always needs one person.

### Clients and grants

Claude Desktop connects to `dev` and `incident-prod` through separate `.mcpb` bridge rows (`gaterook-dev.mcpb`, `gaterook-incident-prod.mcpb`), with Add endpoint, Disconnect, and Remove bridge.

Codex connects to dev through a managed `gaterook-dev` block in `~/.codex/config.toml`; `http_headers_helper` runs the stable `~/.gaterook/bin/gw-bridge headers --endpoint dev --grant <grant-id>` shim. Its revocable access is `codex-laptop`, with no token value in configuration.

Cursor is detected but disconnected. Connect offers a named token in `~/.cursor/mcp.json`, explicitly warning about plaintext, or browser sign-in. Production uses the stdio bridge instead and does not store a token. Antigravity is not installed. claude.ai uses stage-readonly.

Resolved calls by client are Codex 772, Claude Desktop 493, and claude.ai 19. Including pending requests, Activity shows 774, 494, and 19 respectively.

### Tunnel and OAuth

The Cloudflare quick tunnel ran from 08:40 to 12:10, using the fictional `https://gw-acme-7f3k.trycloudflare.com`, and is now off. claude.ai made 19 calls, last at 12:08:14. The user approved its OAuth sign-in at 08:52; it expires October 29 and remains in Signed in after stopping the tunnel.

A new publication at 14:30 uses a new quick-tunnel address, `https://gw-acme-9q4m.trycloudflare.com`, so the earlier sign-in says the public address changed. Only named tunnels or configured ngrok domains promise stable addresses.

Publishing confirms exactly `/mcp/stage-readonly`, `/oauth/*`, and `/.well-known/oauth-*`; UI, admin API, dev, and incident-prod remain local. Cancel receives initial focus. The three gates are Anthropic caller IPs on the MCP route, OAuth consent on this computer, and endpoint read-only modes. The sole caller toggle is in Settings → Tunnel → Callers and is on. The OAuth browser itself is not subject to that gate; its fictional From address is `203.0.113.24`. Consent shows a comparison code, focuses Deny, uses primary Allow access, and times out after five minutes. Undo never republishes a tunnel.

### Tokens

Default lifetime is seven days. Claude Desktop and Codex receive 15-minute tokens on demand. Their rows say **issued on the fly** rather than showing a fabricated value. `codex-laptop` is a grant; Revoke and Disconnect stop both current and future tokens.

Manual example rows are `inspector-debug` (`gwt_K9xP…`, expires in three days), `ci-smoke` (`gwt_2mZa…`, expired), and `old-claude` (`gwt_Pq4d…`, revoked). The summary is two active, one expired, one revoked, plus the bridge row. Token IP allowlists do not appear on desktop. `cursor-laptop` with prefix `gwt_Vb8r…` is an issuance example only; disconnected Cursor has no token yet.

### Requests and history

| Pending action | Client | Requested | Auto-deny | Remaining at 14:30 |
| --- | --- | --- | --- | --- |
| `k8s_rollout_restart deployment/api`, namespace `app-dev` | Claude Desktop | 14:25:48 | 14:30:48 | 0:48 |
| `sql_execute` on `dev-db` | Codex | 14:29:12 | 14:34:12 | 4:12 |
| `http_request POST /v1/refunds` on `billing-api` | Codex | 14:29:30 | 14:39:30 | 9:30 |

The SQL action is exactly `UPDATE orders SET status = 'cancelled' WHERE id = 48213;`. All three await the local user.

There are 58 historical decisions: 46 approved, nine rejected, three expired. Examples: `k8s_apply` rejected by you at 13:58:30; `deployment/order-sync` restart approved from Slack at 12:31:44; production `ssh_run restart-api` approved at 09:47:05 in the app opened from Slack, with the required comment. **From Slack** records the entry path, not permission to approve production inside a message.

Example audit calls include a 38 ms allowed SQL query, `compose-ps` on `dev-vm`, Loki `{app="api"} |= "ERROR"`, denied production DROP TABLE, rejected k8s_apply, expired HTTP, and a Redis timeout. Trace `4f2a9c…` is **Fix failing order sync**, with 23 calls over 18 minutes. There are 61 task traces.

Setup changes, all by You: tunnel enabled at 08:40; dev-vm `ssh_exec` Approval → Off at 09:12; billing-api `http_get` Read only → Off at 10:40; tunnel disabled at 12:10. The first change's reason is **Try stage-readonly from claude.ai**.

### First run, notifications, and audit

First run has no connections or endpoints. Setup has one healthy `dev-db` connection on dev, SQL list/describe/query in Read only and execute in Approval, with no clients, tokens, requests, calls, tunnel history, or sign-ins. Both use a 14:29 scan that finds Claude Desktop, Codex, and Cursor, with Antigravity absent. Saving the first connection creates that dev configuration at 14:30.

Connect examples create the bridge extension, the Codex grant, or a Cursor token expiring October 29. Claude Desktop's fallback configuration uses its normal user configuration file with a dated backup. The test prompt is **Show me the tables in dev-db**. Getting started shows **5 of 6**, with Playground remaining.

Desktop notifications are on. Slack workspace `acme` is connected, using consciously selected `#deploy-approvals` with a channel-visibility warning; latest delivery is 14:02:30. Direct messages are recommended. Telegram is not configured. Channel approval restrictions remain the same as DESIGN §8.

Audit is append-only with one/two/three/five-year retention, default/minimum one. Fictional export destinations are `siem.acme.internal:6514`, `s3://acme-audit/gaterook/`, and BigQuery `acme-prod.audit.mcp_calls`.

### Totals and links

There are **1,287 calls = 1,284 resolved + 3 pending**. Resolved counts: 1,201 allowed, 46 approved, nine rejected, 17 denied by policy, three expired, eight errors. Tool totals including pending: sql_query 512, logs_search 341, k8s_get 150, ssh_run 98, http_request 62, sql_execute 48, redis_get 31, mongo_find 23, k8s_apply 9, k8s_rollout_restart 7, ssh_exec 6. Environment totals: dev 1,095, stage 81, prod 111. The fictional p95 overhead is 31 ms against the 50 ms target; median approval wait is 47 seconds.

Use encoded Activity filters, with repeated fields meaning OR. Preserve the selected range when linking from Insights. Event anchors work independently of the active range. Copy event links as `gaterook://activity/events/<id>`. Settings, policy, connection, client, and approval anchors follow the contracts in DESIGN §8.

## 10 · Reference-page skeleton

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Approvals · GateRook</title>
  <link rel="icon" href="../assets/logo-mark.svg" type="image/svg+xml">
  <script src="../assets/theme-boot.js"></script>
  <link rel="stylesheet" href="../dist/gw-kit.css">
</head>
<body>
  <a class="gw-skip-link" href="#main">Skip to content</a>
  <div class="gw-shell"><!-- Shared structure from pages/_shell.html. --></div>
  <script src="../vendor/lucide/lucide.min.js"></script>
  <script src="../assets/kit.js"></script>
</body>
</html>
```

A composition demo uses the same head with a `main.gw-page`, one h1, and named h2 sections for states. Completion requires `npm run verify`, keyboard operation, and legible light/dark/high-contrast themes.
