# GateRook style and interaction patterns

This reference defines foundations, product patterns, content, and accessibility. See [AGENTS.md](AGENTS.md) for the contribution workflow, [BLUEPRINT.md](BLUEPRINT.md) for screen contracts, [COMPONENTS.md](COMPONENTS.md) for markup, and [the showcase](pages/showcase.html) for examples.

## 1 · Principles

1. **Make safety visible.** Show what an agent may do, what happened, and what awaits a decision. Stored secrets are never displayed; mutations require approval by default. An explicitly selected Bypass tool mode skips approval while preserving policy denials and limits.
2. **Prefer precision.** Display SQL, hosts, tool names, tokens, and other literals exactly, in a monospaced font without ligatures. Color communicates meaning.
3. **Stay compact and calm.** The reference density uses 13–14 px text, 32 px controls, and 40 px table rows, with neutral surfaces and one brand accent.
4. **Explain setup in order.** Make resources → endpoint → client visible, with a short explanation and next step at each stage.
5. **Build in accessibility.** Keyboard operation and WCAG 2.2 AA across light, dark, and high-contrast themes are completion criteria.

## 2 · Color

Components use semantic `--gw-color-<role>-<variant>` tokens. Raw values and theme differences belong in `tokens/tokens.json`.

| Group | Tokens | Purpose |
| --- | --- | --- |
| Surfaces | `bg.canvas`, `sidebar`, `surface`, `raised`, `sunken`, `field`, `overlay`, `selected`, `inverse`, `backdrop`, translucent `hover` and `active` | Window, cards, nested areas, fields, and layers |
| Text | `text.primary`, `secondary`, `muted`, `placeholder`, `disabled`, `link`, `inverse`, `on-solid` | All except disabled text meet 4.5:1 on supported surfaces |
| Borders | `border.subtle`, `default`, `strong`, `input`, `focus` | Input and focus borders meet 3:1 |
| Brand | `brand.*`, Gate violet `#6C56EE` | Primary action, navigation, selection, focus, links, and progress |
| Status | `success`, `warning`, `danger`, `info`, `neutral` × `fg`, `subtle`, `border`, `solid`, `solid-hover`, `on-solid` | Status vocabulary in Blueprint §7 |
| Environment | `env.dev`, `env.stage`, `env.prod` | Teal, orange, and red `gw-env` markers |
| Code | `code.*`, `diff.*` | Syntax and differences |
| Charts | `chart.1…6`, `chart.grid` | Categories with additional labels or tables |

Violet identifies the product interface, not a status. Green filled actions mean **Approve and run** only; green text or dots mean healthy, connected, or allowed. Amber means attention, pending approval, degradation, or approaching expiry. Red means denial, failure, irreversible action, or production. Neutral means off, unknown, or expired.

Dots, bars, and chart marks use a tone's `fg` color for non-text contrast. `solid` is a background beneath text. Register new contrast pairs in `tokens/contrast-pairs.json` and verify them in all four themes.

`assets/theme-boot.js`, loaded synchronously before CSS, sets `data-theme="light|dark"` and `data-contrast="more"` on `html`. Preferences are System / Light / Dark and System / Normal / High; System follows OS changes. The same attributes can theme a subtree. Dark surfaces increase in lightness from canvas to overlay and rely on borders for separation. High contrast strengthens secondary text, borders, and focus without replacing the brand or base backgrounds.

## 3 · Typography

Inter is the interface font, with tabular figures and `cv05` / `cv08` to distinguish lowercase l, uppercase I, and digit 1. JetBrains Mono is used for tool names, slugs, hosts, paths, SQL, commands, tokens, and JSON. Disable its ligatures so operators remain literal. Both OFL font families are bundled in `assets/fonts/` with Latin, extended Latin, and Cyrillic coverage.

Write **GateRook**, with capital G and R. Use `gaterook` in technical slugs and filenames. The canonical mark is `assets/logo-mark.svg`; the monochrome silhouette is `assets/logo-mark-mono.svg`. `gw-logo` uses live text. Exported `assets/logo.svg` contains Inter Semibold outlines generated from the shared geometry by `scripts/export-logo.mjs`.

Use `gw-type(<role>)` or `.gw-text-<role>`:

| Role | Size / line height / weight | Use |
| --- | --- | --- |
| `display` | 36 / 44 / 600 | Startup and onboarding |
| `h1` | 22 / 28 / 600 | Page heading |
| `h2` | 16 / 24 / 600 | Card or section heading |
| `h3` | 14 / 20 / 600 | Subsection |
| `body` | 14 / 20 / 400 | Default text |
| `body-read` | 14 / 22 / 400, secondary color | Explanations |
| `ui` | 13 / 20 / 500 | Controls and navigation |
| `small` | 13 / 18 / 400 | Tables and metadata |
| `caption` | 12 / 16 / 400 | Hints, timestamps, axes |
| `overline` | 11 / 16 / 600, uppercase, 0.06em tracking | Group labels |
| `code`, `code-sm` | 13 / 20, 12 / 16, monospace | Literals |
| `stat` | 28 / 36 / 600, tabular | KPIs |

Do not choose arbitrary font sizes. Align numerical columns with `gw-tabular`.

## 4 · Space, shape, and depth

Use the 4 px spacing grid: `space-1` is 4 and `space-16` is 64. Default card padding is `space-5` (20), gaps between cards and form rows use `space-4` (16), and page sections use `space-8` (32).

Controls are 28 / 32 / 40 px for small / medium / large. Table rows are 40 px, or 32 in dense mode. Pointer targets are at least 24 × 24 px. Radius tokens are xs 4, sm 6, md 8, lg 12, and full for pills or dots. Cards use borders and `shadow-sm`; menus, popovers, and toasts use `shadow-md`; dialogs and drawers use `shadow-lg` with a backdrop. Dark themes always retain a visible boundary.

## 5 · Icons

Use the local, pinned Lucide set. Static markup is `<i class="gw-icon" data-lucide="database"></i>`; `kit.js` replaces it with decorative SVG. Angular uses the matching icon package. Sizes are xs 12, sm 14, default 16, lg 20, xl 24, and 2xl 32, selected to match neighboring text.

Meaning belongs in accompanying text. An icon-only button needs an accessible name that names its object, such as **Copy endpoint URL**. Verify icon names with `npm run icons`; the kit checker rejects unknown names. See Blueprint §7 for shared icon choices.

## 6 · Motion

Use fast 120 ms, base 180 ms, and slow 280 ms motion tokens with the standard or exit easing. Wrap movement in `gw-motion`, which respects `prefers-reduced-motion`. Global reduced-motion rules collapse transitions and replace loading movement with restrained opacity changes. Animate only meaningful changes: a new layer, a changed count, or live status.

## 7 · Shell and layout

`gw-shell` contains the titlebar, sidebar, and main region. The titlebar is an Electron drag region; `--mac` reserves space for system controls. The sidebar is 240 px, collapses to 56 px, and collapses automatically below 960 px. Regions scroll independently.

`gw-page` is the only page-width container: 32 px gutters, 1440 px maximum; `--narrow` is 880 px for forms, and `--full` removes the maximum for journals and split views. `gw-page-header` contains breadcrumb, heading and actions, metadata, and tabs. Use `gw-split` for master/detail and `gw-grid` for card collections. Split panels stack below 1280 px.

Copy application navigation from `pages/_shell.html`, changing only the current item. SCSS breakpoints are sm 640, md 960, lg 1280, and xl 1600 through `gw-bp-up`.

## 8 · Product patterns

### Write-only secrets

Enter a new secret in a password input. A visibility toggle is acceptable only while first entering a new value. Stored state shows encryption, version, and update metadata, with **Replace** or **Rotate**, never reveal.

Rotate changes the value at its source and is available only for a connector that supports it; the demo uses PostgreSQL `dev-db`. Otherwise keep the action discoverable with `aria-disabled` and the core's explanation. Replacing a production secret requires a comment. External references show a monospaced URI, provider, and **Test reference**. Tool output and audit text show `‹masked›` and the number of masked fields, never the secret.

### Tokens shown once

Default named-token lifetime is six months (184 days); shorter lifetimes remain selectable. OAuth access tokens and sign-ins also last 184 days, with immediate revocation and persisted token hashes. The Claude Desktop `.mcpb` bridge and Codex `http_headers_helper` obtain short-lived, 15-minute tokens at runtime. Their client configuration stores no token; tables describe issuance through the bridge rather than showing a fictitious `gwt_` prefix. The helper command uses the stable data-directory shim, not a path inside the application bundle. Revoking a client's bridge grant invalidates its current token and prevents new issuance.

Other clients can use named tokens. The issuance dialog asks for name and TTL, then uses `gw-copy-field--secret-once`, **Shown once**, and **I've stored this token**. After closing, only a prefix, dates, usage, and Revoke remain. Cursor's file-based configuration stores its token in plaintext; Connect must disclose that and offer browser sign-in where supported. On production endpoints, Cursor and Antigravity use the bridge; token-file and browser-sign-in choices are unavailable. A token IP allowlist is a server-mode feature and is not shown on the loopback-only desktop UI.

### Approvals

An approval shows the exact action, environment-marked connection, endpoint, client/token, policy rule, and time remaining before automatic denial. The agent's explanation is a secondary quotation labeled **Agent's note · unverified**. It never replaces the action and is never sent to notification channels.

Wrap SQL, commands, HTTP text, and diff lines so a dangerous suffix cannot be hidden beyond the right edge. Display control characters, bidi markers, zero-width characters, tabs, and trailing spaces as visible code-point tokens. Frame non-ASCII look-alikes outside string literals with `gw-tok-confusable`; announce the count in a compact warning.

JSON actions may use a Pretty / Original switch built from `gw-segmented--sm` in the code block header. Pretty changes structural whitespace only; keep every literal and suspicious-character marker intact. Original shows the core's displayed text, and Copy follows the selected view. Fall back to Original when safe formatting is unavailable.

MCP Apps cards reuse the canonical card, key/value, code block, environment, status, alert, and button primitives. Show a masked caller-visible preview, status, and remaining time. **Deny** declines through the client channel; **Details** and the violet **Approve in GateRook** action open desktop review. Reserve the green **Approve and run** action for the protected exact-action review screen. A chat card must not imply that the preview itself authorizes execution.

Place **Deny** (`--danger`) on the left and **Approve and run** (`--approve`) last on the right. Comments are optional on dev/stage. Production requires a comment by default: use a visible required marker, `required` or `aria-required`, and an explained `aria-disabled` approve button until nonempty text exists. Deny stays available.

Desktop decisions are **by you** and require one approver. Multiple approvers appear only in labeled server-mode previews; a partial state says **Approved by 1 of 2**, and an existing approver cannot approve twice. Audit records include the approved action's SHA-256 and whether execution matched it.

Production connections are accessible only through production endpoints. `prod-vm` uses `ssh_run` runbooks; show the runbook, parameters, and expanded command. Timeouts deny the action: dev five minutes, stage and prod ten minutes. On expiry, mark the request Expired, explain disabled decision controls, announce once, and move it into history.

Desktop OS notifications always offer only **Review** and **Deny**. Slack may offer Approve only for non-production actions fully visible within 3,000 characters when no comment is required; Telegram uses 4,096 characters. Otherwise show **Deny** and **Open in app**, with the reason. Production can never be approved in a channel, even if its comment rule is disabled. Prefix production notifications with **PROD ·**.

Prefer direct messages to linked approvers. Choosing a channel requires disclosure that everyone there sees the exact action. MCP `input_required` returns the approval ID, a read-only exact action, and a review link; it never provides a field through which the agent can approve itself.

### Tool modes

The modes are **Off**, **Read only**, **Approval**, and **Bypass**. Bypass runs calls without approval, including mutations, while policy denials, limits, endpoint scope and secret protections still apply. It must be selected explicitly and is unavailable on read-only endpoints. A mutating tool cannot select Read only. Display MCP hints near its name.

**Temporary bypass** is available from an approval's exact-action review and the endpoint's tool list for dev and stage connections on dev and stage endpoints. Choose 15 minutes, 30 minutes, 1 hour, 3 hours, 12 hours, or 24 hours in a native select inside a warning dialog. Name the method, connection, endpoint, and original mode; focus Cancel initially. It applies to future calls by all clients of that endpoint. Existing pending requests still need a separate decision. Show the expiration time and allow early cancellation; expiration restores the original mode automatically. Off tools, permanent Bypass, read-only endpoints, core tools, and production do not offer this temporary exception.

External MCP/plugin hints are labeled **Declared by <server>, not verified**. A newly exposed external tool starts in Approval. Read only requires an explicit declaration and an individual confirmation; it cannot be enabled in bulk. An undeclared tool offers Approval, Bypass or Off, with native-disabled Read only and a visible explanation.

### Environments

Show `gw-env` next to connections in approvals, Playground, endpoint tools, and Activity. Production is red and explicitly named. Environment choice cards explain stricter production defaults: read-only transactions, 100-row limit instead of dev's 1,000, three-second statement timeout instead of five, no DDL, ten-minute approval window, and an approval comment.

### Action hierarchy and disabled behavior

Use one primary action per level: a page, card, and dialog can each have one. When an unavailable action has a reason, use `aria-disabled="true"` plus `aria-describedby`, retaining it in keyboard order. The shared capture-phase guard blocks clicks, Enter/Space, relevant menu arrows, and submission before other hooks; it also prevents checked state changes. Angular must preserve this behavior.

Read only on a mutating tool and Bypass on a read-only endpoint remain natively `disabled` with a visible reason, so these restrictions do not depend on JavaScript. Busy buttons use `aria-busy`, a spinner, retained text, `aria-disabled`, and a linked status explanation until completion.

### Links and navigation

- Activity filters: `activity.html?q=<encoded-query>`, with `connection:dev-db decision:denied tool:sql_*`; repeated fields mean OR. Preserve `range=1h|24h|7d|30d` from Insights.
- Activity views: `view=traces` or `view=changes`. Setup changes include tunnel state, mode changes, risk dismissals, and token issuance/revocation.
- Event: `activity.html#event-<id>` opens a drawer. **Copy link** uses `gaterook://activity/events/<id>` for the desktop application.
- Client: `clients.html#cl-<cd|cx|cu|ag|ai>-t`. During setup, supported anchors open the relevant Connect review or focus its row; claude.ai scrolls to its card.
- Settings: `settings.html#settings-<section>`. Policies: `policies.html#<env>` or `#<env>-<section>`. Approval: `approvals.html#ap-<id>`. Connection: `connection-new.html?edit=<name>` or `?duplicate=<name>`.

### Desktop, server previews, and time

Desktop has one local user; audit setup changes say **You**. Names, roles, and avatars for other people belong only in explicitly labeled server-mode previews. The common demo clock is 14:30 local time on 2026-10-22; events never occur later, although deadlines may.

The trust boundary is the OS account. The onboarding master-key explanation and bridge explanations must say that protection covers other users and copied files, not programs running as the same user, including terminal-capable agents. OS protection is enabled by default where supported. The packaged core is native; JRE wording applies only to the Java plugin host, started when needed.

### Dangerous actions

Use `--danger-ghost` to open an irreversible-action confirmation and `gw-dialog--danger` with an object-specific danger button to commit it. Explain the consequences. The same dialog tone applies to untrusted code: unsigned plugins and external MCP servers. Explain process isolation and, when unavailable, sandbox limitations accurately.

Use `gw-dialog--warning` for reversible risk such as tunnel publication, a port change, or broader caller access; its confirm action is normal or primary. Every dialog specifies initial focus: first input for forms, Cancel for confirmations, Deny for OAuth consent, and Close for a read-only drawer. Enter must not implicitly accept a dangerous action.

Reversible local changes may use a toast with Undo. Undo must never restore external exposure, revoked tokens, or revoked sign-ins; re-enabling those requires explicit confirmation.

### Tunnel and claude.ai

Publishing an endpoint requires a warning dialog listing the exact public routes (`/mcp/<slug>`, `/oauth/*`, `/.well-known/oauth-*`), what stays local, and the three gates: Anthropic caller ranges, OAuth consent on this computer, and endpoint modes.

The caller allowlist applies to the MCP route, not the user's browser on `/oauth/authorize`. Consent's **From** is the browser IP. The sole editable Anthropic allowlist control lives in Settings → Tunnel → Callers; other pages link to it.

A quick tunnel gets a new address on restart; stable-address wording is reserved for named tunnels or a configured ngrok domain. Consent arrives automatically, contains a comparison code, focuses Deny, and uses a primary **Allow access** button. No reply means denial. Authorized sign-ins remain visible with Revoke after the tunnel stops.

### Setup guidance

`setup-map` follows configuration order: Secrets & resources → Connections → Policies → Endpoints → AI apps. Show done, attention, or todo and a Configure link. Explain that an agent call travels in the reverse direction, with the secret injected at the connection. Its `--wiring` variant shows one endpoint's AI apps → endpoint → connections.

`how-it-works` is a collapsible, remembered explanation of three or four steps at the start of a complex section. Use at most one per Settings section.

### Activity and analysis

Every object links to a filtered Activity view. Structured filters appear as tags; histogram selection narrows the range. Open an event in a drawer without losing the list and provide a copyable deep link.

Default columns are **Time | Decision | Tool · connection | AI app · endpoint | Duration**. Place the environment marker next to the connection. Policy-denied calls say **Not run**; waiting/running calls say **N so far**. Secondary columns use `__cell--optional`; the default table does not scroll horizontally at 1440 × 900.

Show relative time in lists and absolute ISO time in details and `title`. Agent calls and setup changes share the audit interface. Retention choices are one, two, three, or five years, with one year as the minimum and default.

### Notifications and form states

Desktop notifications default on. Slack setup identifies the workspace, delivery destination, visibility, and latest delivery. Telegram setup identifies the bot and chat; its stored token is write-only.

Use `gw-empty` with an explanation and one next action, a button spinner for short work, a skeleton for section loading, and indeterminate progress for longer unknown durations. Errors explain what happened and how to proceed, with expandable technical details. Server permission states explain the required role and who can grant it.

Keep labels visible. Link hints/errors to fields, show errors after attempted submission, and focus the first invalid field. Immediate settings use switches; validated forms use Save and an Unsaved changes action bar. Test connections before saving and show detected account permissions.

## 9 · Components

Use the registered primitives in `scss/components/`: actions and identity; shell and layout; statuses and data; forms; overlays and feedback; code, tables, and charts. [COMPONENTS.md](COMPONENTS.md) contains usage and examples; `manifest.json → components` lists exact classes and variants.

## 10 · Compositions

The reusable compositions in `elements/` combine primitives using layout only. They include setup-map, how-it-works, getting-started, approval-request, approval-notification, tool-policy-list, risk-callout, token-issue, client-card, connection-form, connector-card, event-detail, log-explorer, settings-layout, status-screen, command-palette, and status-tile. Inspect their registered `composes`, examples, and reference sections before using them.

## 11 · Interface writing

Use English and sentence case for headings, controls, and navigation. Buttons name an action and object: **Create endpoint**, **Test connection**, **Approve and run**, **Revoke token**. Literals use `gw-code`. Errors state the failure and a useful next step. Include units and grouping in numbers: **1,284 calls**, **42 ms**, **2.4 s**, **1.2 MB**. Avoid exclamation marks, decorative punctuation, and repetitive pleasantries. Follow Blueprint §8 terminology consistently.

## 12 · Accessibility

Text meets 4.5:1; large text and meaningful non-text controls meet 3:1 in all four themes. Never encode state only with color. Focus uses a visible two-pixel ring and offset; use inset rings where scrolling would clip them.

Menus/popovers open on click, Enter, or Space, use arrow navigation, and close with Escape and focus return. Tabs support arrows plus Home/End. Native dialogs use `showModal()` with trapped focus and explicit initial focus. Hover content must also be reachable by keyboard.

The shared `/` hook focuses a visible search input only outside editable controls, without modifiers or an open menu. Inside a modal, only its own search qualifies. Settings' Single-key shortcuts switch controls `data-gw-single-keys="off"` on `html` and persists the preference. Pages provide `aria-keyshortcuts` and a visual hint without additional listeners. The shared command palette uses ⌘K / Ctrl+K.

Prefer native buttons, links, inputs, tables, dialogs, and ordered lists. Give every control an accessible name, with unique object names for repeated actions. Use one h1 and coherent heading levels, named navigation groups, a skip link, and main. Announce routine changes politely; announce urgent expiry once.

Charts include an accessible summary and data table inside a visually hidden wrapper, not `gw-sr-only` on the table itself. Keep zoom enabled and support 200%. Verify keyboard operation, focus, light/dark/high-contrast rendering, and screen-reader names and states with VoiceOver or NVDA where available.

## 13 · Review checklist

Verify token-only styling and explained exceptions; canonical components; correct primary/approve hierarchy; write-only secrets; one-time tokens; literal actions; explicit initial focus; explained disabled states; shared demo data; environment markers; redundant status cues; keyboard access; all relevant loading/empty/error states; and a passing `npm run verify` result.
