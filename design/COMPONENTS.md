# Components and compositions

This reference lists every registered component and reusable composition, including variants, parts, states, and accessibility contracts. It preserves the kit's markup examples in the final section. The machine-readable counterpart is [manifest.json](manifest.json); interactive examples are in [the showcase](pages/showcase.html) and each composition's demo.

Read [DESIGN.md](DESIGN.md) for product-wide behavior and [AGENTS.md](AGENTS.md) before extending the system. Use compositions first, then primitives and their existing variants. Component states use native or ARIA attributes; reserve `.is-*` for a state with no suitable semantic attribute.

## Shared implementation rules

- `gw-btn` is the action primitive. Use one primary action per level and reserve green `--approve` for an approval decision. Irreversible actions use a danger trigger and explicit confirmation.
- Unavailable controls with explanations use `aria-disabled` plus `aria-describedby` and remain focusable. The capture-phase guard blocks their activation. A tool mode that would bypass approval stays natively disabled with a visible reason.
- Copy actions have `__copy` and `__copied` faces. `kit.js` sets `data-copied` for 1.6 seconds, including inside modal dialogs. Disclosure chevrons follow `aria-expanded`.
- `aria-pressed=true` shows a tone and a two-pixel bottom bar; `__pressed` and `__unpressed` provide distinct faces. Selection never relies on color alone.
- The shell has exactly three grid children: titlebar, sidebar, and main. Put application-wide banners inside main. Use `gw-page` for all width constraints.
- Cards wrap header actions when the heading becomes narrow, stretch in grid list items, and align their footers. Split views stack below the large breakpoint.
- Exact actions wrap rather than hide a dangerous suffix. Show invisible characters as explicit code-point tokens and frame look-alikes outside literals.
- During the last 60 seconds, an approval countdown changes from warning to danger and gives textual time. At zero, emit `gw-countdown-end`, mark the request expired, hide its progress, explain disabled decisions, make its comment read-only, and announce once.
- Menus use the top layer through manual popovers, follow their trigger while scrolling, and flip above when needed. A radio popover focuses the selected radio.
- Tables show scroll shadows, hide optional columns first, and may keep the final action cell sticky. Long literals either wrap or use explicit truncation with the full value available.
- Screen-reader data tables belong inside a visually hidden wrapper, not on a table with a clipping class.
- Native dialogs define initial focus and restore the opener. Toasts and announcements are rendered inside the active modal so they remain reachable.
- Shared hooks cover pressed states, search clearing, dialog transitions/guards, conditional enabling, remembered disclosure, reveal, menu closing, countdown announcements, tab anchors, histogram keyboard navigation, and the command palette. Do not add competing per-page behavior.

## Component API inventory

### `gw-logo`

Product lockup: the mark (assets/logo-mark.svg, never redrawn) plus the product name set in the UI font.

[SCSS](scss/components/_logo.scss)

**Variants:** sm; lg; xl; mark-only.

**Parts:** __mark; __name.

**Accessibility:** The link carries the accessible name (aria-label); the mark img has alt="".

### `gw-icon / gw-icon-tile / gw-spinner`

Lucide icon wrapper (pinned in vendor/lucide), tinted icon tile with tones, in-place spinner.

[SCSS](scss/components/_icon.scss)

**Variants:** icon: xs sm md lg xl 2xl; tile: sm lg + tones brand success warning danger info neutral dev stage prod; spinner: sm lg.

**Accessibility:** Icons are aria-hidden (kit.js sets it); meaning lives in adjacent text. Spinner pulses instead of rotating under reduced motion.

### `gw-btn / gw-btn-group`

The single button primitive. Primary = the one main action of a view; approve = only the approval decision; danger = confirming an irreversible action; danger-ghost = its trigger.

[SCSS](scss/components/_button.scss)

**Variants:** primary; secondary; ghost; approve; danger; danger-ghost; link.

**Sizes:** sm; md; lg.

**Modifiers:** icon; block.

**Parts:** __kbd; __copy; __copied; __chevron; __pressed (face shown while aria-pressed=true; Live: a gw-status--live dot); __unpressed (face shown while aria-pressed is not true; Live: the radio icon).

**States and behavior:**

- :disabled (nothing to explain) / aria-disabled=true + aria-describedby (unavailable with a reason; kit.js blocks it)
- aria-busy + gw-spinner
- aria-pressed=true (toggle, data-gw-pressed flips it): brand tone AND a 2px brand bar along the bottom edge (shape, not only colour); only the matching __pressed / __unpressed face shows

**Accessibility:** Native <button type> or <a href>; icon-only needs aria-label naming the object.

### `gw-card / gw-stack`

The one container: header (title, description, actions), body, footer. gw-stack spaces stacked cards.

[SCSS](scss/components/_card.scss)

**Variants:** flush; interactive; selected; dashed; muted.

**Parts:** __header; __heading; __title; __description; __actions; __body; __footer; __footer--split; __link.

**Accessibility:** section[aria-labelledby] → the title heading. Interactive cards use one stretched link; selected pairs with aria-current/aria-checked.

### `gw-shell`

Window frame of every app page: a CSS grid with the titlebar across the top, the sidebar on the left and main on the right. The window never scrolls as a whole: the shell is 100vh/100dvh, capped by max-height: 100% of a sized parent, and the sidebar body and main each scroll on their own. Copy it verbatim from pages/_shell.html.

[SCSS](scss/components/_shell.scss)

**Variants:** (no sidebar) main spans the full width: onboarding, core status screen.

**Parts:** __main.

**States and behavior:**

- [data-collapsed] sidebar shrinks to --gw-layout-sidebar-collapsed (kit.js data-gw-sidebar-toggle, persisted in localStorage)
- below md: collapsed automatically, toggle hidden

**Accessibility:** A skip link (gw-skip-link) before the shell targets main#main[tabindex=-1]; the programmatic focus target gets no ring. Landmarks appear once each, in order: header (titlebar) → nav[aria-label=Main] → main. The grid has exactly three children: app-wide banners (gw-alert--banner) go first inside __main. The sidebar width change animates only under prefers-reduced-motion: no-preference.

### `gw-titlebar`

Electron frameless titlebar (bg canvas, subtle bottom rule, --gw-layout-titlebar-height): a centered, truncating section name, a leading drag area with Back, and trailing actions (search with ⌘K, approvals bell with an attention counter, appearance popover). The whole bar is a drag region; links, buttons, inputs, labels, [tabindex], menus and popovers inside opt out (no-drag) automatically.

[SCSS](scss/components/_shell.scss)

**Variants:** --mac (leading drag area reserves --gw-layout-titlebar-inset-mac plus --gw-space-2 for the traffic lights); Windows/Linux: end padding from env(titlebar-area-x/width); the fallbacks cancel out without Window Controls Overlay.

**Parts:** __drag (leading slot, optional Back button); __title (centered across the window, truncates); __actions (trailing controls).

**States and behavior:**

- Back is a text button with a leading arrow, native-disabled until a previous application page exists; the renderer owns navigation history. Reference pages show the initial disabled state.
- a gw-counter inside an icon button in __actions is pinned as a corner badge ringed in the canvas colour

**Accessibility:** The <header> is the banner landmark. Back uses its visible label as its accessible name and a title explaining its disabled state. Icon buttons carry aria-label ("Approvals, 3 waiting" with an aria-hidden counter; "Appearance"). The Search button's name starts with its visible text ("Search and commands") and its ⌘K kbd is aria-hidden. Overlays at the window edge use gw-menu--right / gw-popover--right. Popover titles are <p>, not headings, because they precede the page h1.

### `gw-sidebar`

Navigation column (bg.sidebar, end border): logo and collapse toggle, the scrolling nav, and a footer that always shows the local core and the tunnel (gw-status, static) plus compact mono metadata: the address below the core status and the version on its own final line, with bottom padding. Metadata uses the 11px font token and wraps long values instead of truncating them.

[SCSS](scss/components/_shell.scss)

**Parts:** __header; __toggle; __body; __footer; __meta; __update; __update-label; __update-message.

**States and behavior:**

- collapsed (.gw-shell[data-collapsed] or below md): the header stacks; the logo name, footer status labels and __meta become sr-only; status dots stay; __toggle is hidden below md
- When an update is available, place a primary small block `gw-btn` with `__update` below the version. Activation downloads the update and restarts the app. Loading uses `aria-disabled`, `aria-busy`, a spinner and retained text; errors show Retry update and a linked explanation. `__update-label` and `__update-message` become visually hidden when collapsed, preserving the button name and description while its icon stays visible.

**Accessibility:** A plain <div>, not <aside>, because primary navigation is not complementary. The toggle is a button with data-gw-sidebar-toggle, aria-controls pointing at the sidebar id and aria-expanded kept in sync by kit.js. While expanded, the footer uses gw-status with words, never a dot alone. It is never gw-status--live: an endless pulse on every screen fails WCAG 2.2.2.

### `gw-nav`

The app's information architecture (BLUEPRINT §2): Overview and Approvals (counter), then the Resources, Access and Observe groups, with Settings pinned at the bottom. Items are 32px rows: a 16px icon, a label and muted text.

[SCSS](scss/components/_shell.scss)

**Variants:** __group--end (pinned to the bottom behind a hairline).

**Parts:** __group; __group-label; __list; __item; __icon; __label; __counter.

**States and behavior:**

- aria-current="page": bg.selected, primary text, semibold, a 2px brand-fg bar at the inline start (drawn as a border, so it survives forced colours) and a brand-fg icon
- :hover wash + primary text
- :active wash
- :focus-visible inset ring
- collapsed: label and group labels sr-only, hairlines between groups. Counter becomes an 8px border-drawn dot on the icon corner: --attention warning, plain neutral, --muted sr-only. The label flies out beside the icon on :hover AND :focus-visible (tooltip look, hoverable through a bridge; anchor positioning where anchor-scope is supported, fixed static-position fallback)

**Accessibility:** Items are <a href> with exactly one aria-current="page" per page. Labelled groups use role="group" + aria-labelledby pointing at the visible __group-label (a <p>, not a heading). Counters carry sr-only words: <span class="gw-sr-only">, </span>3<span class="gw-sr-only"> waiting</span>. Collapsed links keep their accessible name. The <nav data-gw-nav aria-label="Main"> must be identical on every page (checked); demos omit data-gw-nav.

### `gw-page`

The content container inside gw-shell__main and the only way to cap a width. Gutters are --gw-layout-page-gutter (16px below sm), content max is --gw-layout-content-max, and children stack 32px apart.

[SCSS](scss/components/_page.scss)

**Variants:** --narrow (content max --gw-layout-content-narrow, for forms and settings); --full (no cap, min-height 100%; a direct gw-split child fills the remaining height of main).

**States and behavior:**

- below md: inside --full, a gw-split stops filling and the page scrolls

**Accessibility:** Holds exactly one gw-page-header, which carries the page h1. Never set max-width or margin: 0 auto on page content.

### `gw-page-header`

Top of every page: an optional breadcrumb, then a title row (heading on the left with the h1, description and meta; actions on the right), then an optional tabs slot.

[SCSS](scss/components/_page.scss)

**Parts:** __breadcrumb; __row; __heading; __title; __description; __meta; __actions; __tabs.

**States and behavior:**

- narrow: the heading keeps at least 18rem; actions drop under it and wrap (they shrink instead of overflowing)
- long literal titles break (overflow-wrap: anywhere)

**Accessibility:** __title is the page's only h1. Meta that names an environment uses gw-env with sr-only 'environment' before it. Tabs in __tabs follow gw-tabs (role=tablist). Primary action last; at most one --primary.

### `gw-breadcrumb`

Path to a nested page (endpoint-detail, connection-new): nav[aria-label=Breadcrumb] > ol > li, with a chevron-right gw-icon before every item after the first.

[SCSS](scss/components/_page.scss)

**States and behavior:**

- current item: aria-current="page" (a <span>, or a link to the page itself), secondary text, medium weight, truncates
- links: muted, :hover primary + underline, :focus-visible ring

**Accessibility:** The separator icon is decorative (kit.js sets aria-hidden). Links keep a 24px target height. Literal names (slugs, connection names) use gw-mono.

### `gw-section`

A titled block of a page without a card: __header (h2 title and one-line description on the left, actions on the right) followed by the content, 12px apart. Use a card instead when the block needs its own surface or footer.

[SCSS](scss/components/_page.scss)

**Parts:** __header; __title; __description; __actions.

**States and behavior:**

- below sm: the actions drop under the title and description

**Accessibility:** <section aria-labelledby> pointing at the __title (h2 on a page, one level below the surrounding heading elsewhere).

### `gw-toolbar`

Row of filters and actions above a table or list. __start holds search and filters and wraps; __end holds actions, stays at the end and wraps onto its own line when space runs out.

[SCSS](scss/components/_page.scss)

**Variants:** --sticky (pinned to the top of the scrolling main with a canvas fill; only for toolbars sitting on the page canvas).

**Parts:** __start; __end.

**Accessibility:** Controls inside are kit controls with visible labels or aria-labels; a result count is plain text next to the filters. Group related controls with gw-divider--vertical (aria-hidden).

### `gw-split`

Master-detail layout (Approvals, Playground): __list and __detail, each scrolling on its own once the split has a height. Inside gw-page--full it takes the rest of main; elsewhere set --gw-split-height.

[SCSS](scss/components/_page.scss)

**Variants:** (default) 1 : 2; --40 (2 : 3); --50 (1 : 1).

**Parts:** __list; __detail.

**States and behavior:**

- below lg (a window under 1280px, where the list would get under ~320px): the panels stack, the height is dropped and the page scrolls (also inside gw-page--full)

**Accessibility:** Panels keep 4px of inner room so focus rings are not clipped. A panel with nothing focusable inside gets tabindex="0" + role="region" + aria-label so the keyboard can scroll it. The current list item carries aria-current or aria-selected plus a second cue.

### `gw-grid`

Responsive grid of cards or stat tiles with a 16px gap. It works on <div>, <ul> and <dl>.

[SCSS](scss/components/_page.scss)

**Variants:** --2 (2 columns from sm); --3 (2 from sm, 3 from lg); --4 (2 from sm, 4 from lg); --auto (auto-fill, minimum column --gw-grid-min = 280px, override per use; use it inside narrow panels).

**Accessibility:** Pure layout. DOM order is reading order; never reorder visually.

### `gw-divider`

Hairline (border.default) between groups of content that share a surface. Between cards, use the gap instead.

[SCSS](scss/components/_page.scss)

**Variants:** --vertical (20px tall, between inline items such as meta, toolbar groups and counts).

**Accessibility:** Horizontal: <hr class="gw-divider"> (role separator). Vertical: a <span> with aria-hidden="true", or role="separator" aria-orientation="vertical" when the split is meaningful.

### `gw-kbd`

A key the user presses, in running text, help, menus and the command palette: one <kbd> per key, adjacent keys 2px apart, caption-size mono on surface with a strong border and a 2px bottom edge.

[SCSS](scss/components/_kbd.scss)

**Accessibility:** Keys are written as text (Esc, Enter, Shift, ⌘). Arrow and return keys are Lucide icons (arrow-up, arrow-down, corner-down-left) plus sr-only text. A decorative hint next to a labelled control is aria-hidden. Inside a button use gw-btn__kbd; inside gw-search use gw-search__kbd.

### `gw-avatar`

A person (approver, user in server mode): two uppercase initials on a neutral circle, or a photo.

[SCSS](scss/components/_avatar.scss)

**Variants:** --sm (20px: table rows, timelines); (default) 28px; --lg (40px: account, users).

**Parts:** __img.

**States and behavior:**

- with __img: surface fill, the image covers the circle

**Accessibility:** Next to a visible name: aria-hidden="true" (and alt="" on __img). Standing alone: role="img" + aria-label with the full name (or a meaningful alt on __img). The 1px border keeps the shape in forced colours.

### `gw-client-mark`

Neutral monogram tile of an MCP client (rounded square, so it never reads like a person or an env marker). It is never a vendor logo: official marks come only from vendor press kits after sign-off. Each app keeps one tile everywhere: client cards, endpoint AI apps, Activity rows.

[SCSS](scss/components/_avatar.scss)

**Variants:** --claude (Claude Desktop, CD, stage hue); --codex (Codex, Cx, neutral solid); --cursor (Cursor, Cu, info hue); --antigravity (Antigravity, Ag, dev hue); --claudeai (claude.ai, ai, brand hue); --inspector (MCP Inspector, In, inverse); --generic (any other client: dashed outline, initials or an app-window icon); --sm (24px); (default) 32px; --lg (40px, client cards).

**Accessibility:** Decorative next to a visible name (aria-hidden="true"); alone, role="img" + aria-label with the full client name. The tone is an identity hue only: status, warning, danger and prod tones are never used, and connection state is a gw-status beside the tile. Each tile has a 1px border for forced colours; text pairs are verified tone fg on tone subtle, on-solid on solid, and inverse.

### `gw-badge / gw-counter`

Badge: a 20px status pill in sentence case with an optional leading icon. It names the decision on a call, a token's lifetime, a connector's kind or an MCP annotation, using the BLUEPRINT §7 vocabulary. Counter: a bare 18px number beside a nav item, tab or button; amber (--attention) means a human is waiting.

[SCSS](scss/components/_badge.scss)

**Variants:** gw-badge--brand; gw-badge--success; gw-badge--warning; gw-badge--danger; gw-badge--info; gw-badge--neutral (default); gw-badge--dev; gw-badge--stage; gw-badge--prod; gw-badge--solid (env tones keep the tint and get a solid edge); gw-badge--outline (wins over --solid); gw-badge--wrap (long labels wrap within the available width); gw-badge--sm (18px); gw-counter (default: bg.active wash); gw-counter--attention (warning solid, dark text); gw-counter--muted.

**Parts:** gw-badge__icon (12px; any direct .gw-icon child is sized the same).

**States and behavior:**

- display only: no interactive states
- never truncates; stays on one line by default, so keep the words short
- --wrap preserves the complete label across multiple lines, including unbroken endpoint slugs and nested monospaced text

**Accessibility:** Words are always present; the icon is an aria-hidden second cue. Tone fg on tone subtle and on-solid on solid are verified 4.5:1 in all four modes. A counter needs sr-only context ('3 waiting') or a control name that says what is counted. The badge border and the counter's transparent border keep the shape in forced colors.

### `gw-status`

Dot plus label for the health of a connection, the core, the tunnel or a client (BLUEPRINT §7 health vocabulary). The words state the state; the dot is the second cue.

[SCSS](scss/components/_status.scss)

**Variants:** gw-status--success; gw-status--warning; gw-status--danger; gw-status--info; gw-status--neutral (default); gw-status--brand; gw-status--live; gw-status--checking.

**Parts:** gw-status__dot (8px tone-fg dot with a 1px tone-border outline ring, in a 12px slot); gw-status__label (tabular figures, wraps); child .gw-spinner.gw-spinner--sm for --checking (sized to 12px, info tone).

**States and behavior:**

- --live: the ring pulses inside gw-motion; under reduced motion it is a static ring; one per view
- --checking: covers checking and starting; a spinner replaces the dot (a __dot left in the markup is hidden), and the label does not shift

**Accessibility:** The dot is aria-hidden; the label says the state in words. Non-text marks use tone fg (3:1 or more). The ring is an outline, so it survives forced colors. Use role=status only on a status that changes while the user watches (Test connection), never on every table row. There are no env tones: an environment is not a health state.

### `gw-env`

Environment marker beside a connection name in every risky context. It differs from a badge by shape: a 2px env-solid bar at the start, square start corners and xs end corners, and an uppercase mono label.

[SCSS](scss/components/_status.scss)

**Variants:** gw-env--dev; gw-env--stage; gw-env--prod; gw-env--sm (16px; default is 20px).

**States and behavior:**

- display only

**Accessibility:** Write the slug in lower case in the markup (it is a literal; CSS uppercases it). Where the context does not say 'environment', put <span class=gw-sr-only>environment</span> right before the marker, outside it. Env fg on env subtle is 4.5:1; the env bar is 3:1 on surface and overlay (verified).

### `gw-mode`

Display-only tool mode, icon plus words: Off (circle-off, neutral), Read only (eye, info), Approval (user-check, warning), Bypass (shield-off, danger). Bypass runs calls without approval, within policy limits. To choose a mode, use gw-segmented.

[SCSS](scss/components/_status.scss)

**Variants:** gw-mode--off; gw-mode--read; gw-mode--approval; gw-mode--bypass.

**Parts:** direct child .gw-icon (12px, sized by the component).

**States and behavior:**

- display only

**Accessibility:** The icon is decorative; the words carry the mode. Tone fg on tone subtle is verified. The transparent border keeps the shape in forced colors.

### `gw-tag / gw-tag-list`

A value shown as a 24px token: an allowlist entry (host, IP, CIDR, schema), a scope, or an active log-explorer filter, with an optional real remove button. gw-tag-list is the wrapping list container (ul or div, 6px gap).

[SCSS](scss/components/_tag.scss)

**Variants:** gw-tag--mono; gw-tag--brand; gw-tag--success; gw-tag--warning; gw-tag--danger; gw-tag--info; gw-tag--neutral; gw-tag--dev; gw-tag--stage; gw-tag--prod.

**Parts:** gw-tag__label (truncates); gw-tag__remove (button, 24 x 24); optional leading .gw-icon (12px: circle-alert for invalid, lock for locked); gw-tag-list.

**States and behavior:**

- __remove :hover wash, :active, :focus-visible ring
- __remove [aria-disabled=true] (preferred, stays focusable) or :disabled: the entry is locked by policy, reason nearby
- :focus-within on the tag: the label wraps in full (keyboard path to a truncated value) without a height jump for one line
- invalid entry: --danger + circle-alert + a field error naming the value

**Accessibility:** The remove button is a real <button type=button> with aria-label 'Remove <value>', a 24 x 24 target and the standard focus ring; it is never revealed on hover only. The full value of a truncated label is in title (pointer), in the remove button's name (screen reader) and shows on focus-within (keyboard). After removal, move focus to the next remove button or to the input. The full border keeps the shape in forced colors.

### `gw-kv`

Key-value facts about one object on a native <dl>: connection details, an event's request block, a call's summary strip. Many objects with the same fields belong in a table.

[SCSS](scss/components/_kv.scss)

**Variants:** default (two columns, key column at most 40%, dividers); gw-kv--dense (tighter, no dividers); gw-kv--inline (pairs in a wrapping row, key above value).

**Parts:** gw-kv__row (div wrapper of one dt + dd); gw-kv__key (dt); gw-kv__value (dd).

**States and behavior:**

- display only

**Accessibility:** Native dt/dd pairs. Keys are short nouns without colons. An empty value says None or Never, not a dash. Literals use .gw-mono or gw-code and wrap anywhere. Times show relative words plus absolute ISO in <time datetime>. Keys are text.muted, values text.primary (verified pairs).

### `gw-stat`

KPI tile (Overview, Insights): label, value with unit, a change shown by icon AND words, an optional note or sparkline. A row of tiles is one <dl class=gw-grid>.

[SCSS](scss/components/_stat.scss)

**Variants:** gw-stat__delta--up (trending-up); gw-stat__delta--down (trending-down); gw-stat__delta--flat (minus, muted); data-sentiment=good (success tone) | bad (danger tone) | neutral on __delta.

**Parts:** gw-stat__label (dt); gw-stat__value; gw-stat__unit; gw-stat__delta; gw-stat__meta; gw-stat__chart (96 × 32 slot at the END OF THE VALUE ROW, a <dd>, bottom-aligned with the figures; the label spans the full width above, so values in a row of tiles line up).

**States and behavior:**

- sentiment colours the delta by meaning, independent of direction

**Accessibility:** The words carry the direction and size of the change; colour only adds the judgement, which is also written in __meta when it matters ('within target'). Icons are aria-hidden. The sparkline SVG has role=img and an aria-label stating the trend. The unit is separated by a space in the markup.

### `gw-progress`

How far a task has got (plugin upload, core start, setup checklist). The fraction 0..1 is set via style=--gw-progress on the root, which carries role=progressbar.

[SCSS](scss/components/_progress.scss)

**Variants:** gw-progress--brand (default); gw-progress--success; gw-progress--warning; gw-progress--danger; gw-progress--info; gw-progress--neutral; gw-progress--sm (4px); md (6px); gw-progress--lg (8px); gw-progress--indeterminate.

**Parts:** gw-progress__bar.

**States and behavior:**

- determinate: fraction clamped 0..1, width transition (collapsed globally under reduced motion)
- indeterminate: a 40% segment sweeps inside gw-motion; under reduced motion the whole bar pulses its opacity
- failed: bar stops where it stopped, --danger, words in the label
- inside [data-gw-countdown][data-urgent] (last minute before auto-deny): the markup swaps --warning for --danger; the time is also written as text

**Accessibility:** role=progressbar with a name (aria-labelledby or aria-label) plus aria-valuemin/max/now (no valuenow when indeterminate), and aria-valuetext for friendlier words. The bar is tone fg on the raised track (verified 3:1 or more). The track hairline is a border-colour inset, not a shadow. Transparent outlines keep track and fill visible in forced colors.

### `gw-meter`

A measured amount against a limit (rate limit, response size, rows returned). The page decides the level from its thresholds; the fraction is set via style=--gw-meter.

[SCSS](scss/components/_progress.scss)

**Variants:** data-level=ok (success); data-level=warn (warning); data-level=critical (danger); no data-level (brand, plain measurement).

**Parts:** gw-meter__label (truncates); gw-meter__value (tabular, optional leading 14px icon); gw-meter__track; gw-meter__fill.

**States and behavior:**

- warn and critical: the value takes the tone fg, medium weight and a leading icon (triangle-alert, circle-alert)

**Accessibility:** role=meter, named by aria-labelledby, with aria-valuemin/max/now and an aria-valuetext that includes the judgement ('near the limit'), because the children are presentational. The level never rests on colour alone.

### `gw-empty`

Empty state: what will appear here plus one action. Covers first run, no results and all clear. Not for errors (use gw-alert--danger).

[SCSS](scss/components/_empty.scss)

**Variants:** default (40px tile with 20px icon, h2-size title); gw-empty--compact (32px tile with 16px icon, h3-size title, small text); tile tone via gw-icon-tile--<tone> on __icon (success for all clear).

**Parts:** gw-empty__icon; gw-empty__title; gw-empty__text; gw-empty__actions.

**States and behavior:**

- none of its own; situations differ by words and icon

**Accessibility:** The title is a real heading at the right outline level, or a <p> when the container already has the heading. The icon is decorative and the tile border survives forced colors. Inherited --gw-tone-* is reset so a toned container never tints the tile. When results empty out after filtering, announce it with role=status on the list.

### `gw-field`

Wrapper for one labelled control: a visible label above the control, then error, hint and character counter, tied to the control by aria-describedby. As fieldset.gw-field with legend.gw-field__label it groups checkboxes, radios, segmented controls and choice cards.

[SCSS](scss/components/_field.scss)

**Variants:** --inline (label in a left 1/3 column from md, stacked below; everything else goes in __control); --nested (kit addition: dependent choices indented under their parent choice, role=group + name).

**Parts:** __label; __required; __optional; __hint; __error; __counter; __control.

**States and behavior:**

- control [aria-invalid=true] + __error (circle-alert icon + text)
- __counter[data-over] (danger, semibold, the text says how much over)
- a hint followed by a counter shares the row; otherwise the hint is full width
- fieldset.gw-field + floated legend.gw-field__label (grid row that still names the group)

**Accessibility:** A real <label for> (or <legend> for groups). Hint and error are referenced by aria-describedby. Required is programmatic (required / aria-required); 'Optional' and 'Required' are words, and an asterisk is aria-hidden with sr-only text. An error is a red border plus an icon plus text, never colour alone.

### `gw-input`

Native single-line text input on gw-input-base: 32px, bg.field, border.input (3:1), focus ring at offset 0, red border on aria-invalid that stays while hovered.

[SCSS](scss/components/_input.scss)

**Variants:** --mono (literal values: hosts, ports, paths, slugs); --sm (28px); --lg (40px).

**States and behavior:**

- :disabled
- [readonly] (sunken, focusable, copyable)
- [aria-invalid=true]
- :placeholder-shown
- :focus-visible ring

**Accessibility:** Always inside gw-field with a visible label. A placeholder is an example, never the label. Ports and numbers use type=text inputmode=numeric.

### `gw-textarea`

Native multi-line input on the same base; vertical resize.

[SCSS](scss/components/_input.scss)

**Variants:** --code (mono, no wrapping, tab-size 2: SQL templates, JSON arguments); --wrap (soft wrap, pre-wrap: prose such as server instructions; with --code, a statement read in full, e.g. a Playground SQL argument).

**States and behavior:**

- :disabled
- [readonly]
- [aria-invalid=true]

**Accessibility:** A counter (gw-field__counter) goes in aria-describedby. For code, add spellcheck=false and autocapitalize=off.

### `gw-select`

Native <select> on the same base with a stroked chevron painted in currentColor from two gradients, so no raw colours are needed and it follows the disabled colour. Under forced colours the OS arrow is restored.

[SCSS](scss/components/_input.scss)

**Variants:** --sm (28px); --lg (40px).

**States and behavior:**

- :disabled (chevron repainted)
- [aria-invalid=true]
- forced-colors: native arrow

**Accessibility:** Native keyboard, type-ahead and OS picker. More than four options, or options that change often, go here rather than in gw-segmented.

### `gw-input-group`

One bordered field made of parts: a literal prefix or suffix (https://, ms, rows) and an in-field button (copy, generate, reveal a new value). The group draws the frame, focus ring, invalid edge and disabled look of its gw-input.

[SCSS](scss/components/_input.scss)

**Parts:** __affix; __addon.

**States and behavior:**

- :has(input:focus-visible) ring on the group
- invalid input → danger border (kept on hover)
- readonly → sunken fill
- disabled → raised fill
- __addon[aria-pressed=true] (brand tint, second icon)
- __addon[data-copied] (kit.js copy, success colour, second icon)

**Accessibility:** An icon-only addon needs an aria-label with its object ('Copy base URL'). Its second .gw-icon is the pressed or copied icon. The copy addon uses data-gw-copy or data-gw-copy-from and kit.js announces 'Copied to clipboard'.

### `gw-checkbox`

Native checkbox with a drawn 16px box. The transparent input covers the whole choice, so box, label and hint form one target at least 24px tall. Use it for values saved with a form, independent options and select all.

[SCSS](scss/components/_choice.scss)

**Variants:** no-label (only __input + __control, name on the input): a centred 24×24 target for table selection.

**Parts:** __input; __control; __label; __hint.

**States and behavior:**

- :checked (brand solid + on-solid check)
- :indeterminate (bar; IDL property set from script)
- :disabled (raised box, muted label)
- [aria-invalid=true] (danger box edge, kept on hover)
- :focus-visible (ring on __control)
- :hover

**Accessibility:** Native semantics. When there is a hint, use a div root with label for and aria-describedby, so the hint is not part of the name. No interactive content inside. Marks are drawn with borders so they survive forced colours.

### `gw-radio`

Native radio with a drawn 16px ring and a 6px dot, for one of 2–5 options that each need a sentence.

[SCSS](scss/components/_choice.scss)

**Parts:** __input; __control; __label; __hint.

**States and behavior:**

- :checked
- :disabled
- [aria-invalid=true]
- :focus-visible
- :hover

**Accessibility:** Group in fieldset.gw-field with a legend. Arrow keys move and select natively. The dot is a border disc, visible in forced colours.

### `gw-switch`

A checkbox with role=switch drawn as a 32×18 track with a 14px thumb, for settings that apply immediately with no Save.

[SCSS](scss/components/_choice.scss)

**Parts:** __input; __control; __label; __hint.

**States and behavior:**

- :checked (brand solid track, thumb moves to the end; travel only under no-preference motion)
- :disabled
- :disabled:checked (brand border track)
- :focus-visible
- :hover

**Accessibility:** role=switch is announced as on / off. Thumb position is the second cue. The track has a transparent outline and the thumb is a border disc, so both show in forced colours. Never put a switch inside a form that has a Save button.

### `gw-segmented`

Radio group drawn as joined segments, 32px (28px with --sm). The main case is the tool mode Off / Read only / Approval / Bypass. The checked option is painted in its tone: tone subtle fill, tone fg text and edge, semibold, with its own icon.

[SCSS](scss/components/_segmented.scss)

**Variants:** --sm (28px); --block (full width, equal segments); data-tone on __option: off (neutral) | read (info) | approval (warning) | bypass (danger) | brand success warning danger info neutral dev stage prod; none = brand.

**Parts:** __option; __input; __label.

**States and behavior:**

- :checked (tone subtle + tone fg text + tone fg edge + semibold, raised above neighbours; transparent inner outline becomes a second ring in forced colours)
- :disabled / [aria-disabled=true] (raised fill, disabled text, not-allowed)
- :checked:disabled (muted text, border.input edge, keeps weight)
- :hover (unchecked, enabled)
- :focus-visible (ring above neighbours)

**Accessibility:** fieldset + sr-only legend naming the object ('Mode for sql_execute'), or role=radiogroup + aria-label. Native Tab and arrow behaviour, no script. The reason for a disabled option is visible text referenced by the group's and the option's aria-describedby. aria-disabled keeps the option in the arrow order but the app must refuse the change. Selection is never colour alone (weight + edge + icon).

### `gw-secret`

Write-only secret slot of a gw-field. The frame stays 32px in every state, and there is never a show button for a stored value.

[SCSS](scss/components/_secret.scss)

**Variants:** data-state=empty (input; a reveal toggle is allowed only here, as an input-group addon with aria-pressed); data-state=editing (password input + Cancel + Save, no reveal); data-state=stored (sunken well: lock-keyhole 'Encrypted', 'version 3 · updated 2 days ago', Replace / Rotate); data-state=reference (sunken well: provider + reference in mono + Test reference).

**Parts:** __status; __meta; __ref; __actions.

**States and behavior:**

- data-state=empty|editing|stored|reference
- a narrow well wraps in 28px rows: __meta truncates, __ref wraps inside its row and is never truncated

**Accessibility:** stored and reference use role=group + aria-labelledby pointing at the field label. Button names carry the object ('Rotate password for dev-db'). Inputs use type=password autocomplete=new-password. Announce 'Password saved, version 4' after Save.

### `gw-choice-card`

Radio or checkbox drawn as a card, for choices that need an icon and a sentence: connection type, secret source, environment (with gw-env in the title).

[SCSS](scss/components/_choice-card.scss)

**Variants:** --compact (tighter padding, 24px icon, ui title).

**Parts:** __input; __icon; __title; __description; __meta.

**States and behavior:**

- :checked (2px brand edge + bg.selected tint + filled indicator with check + brand icon)
- :focus-visible (ring around the card via :has)
- :disabled (raised, muted)
- :disabled:checked
- [aria-invalid=true] (danger edge, kept on hover)
- :hover (enabled, unchecked, valid)

**Accessibility:** The native input covers the card, so arrow keys move between radio cards. Name it with aria-labelledby (title) and aria-describedby (description) when the card has more than a title. No buttons or links inside. The check mark is border-drawn and survives forced colours.

### `gw-search`

Search field: a search icon at the start, a clear button once it has a value, and a key hint ('/') while it is empty and not focused.

[SCSS](scss/components/_search.scss)

**Variants:** --sm (kit addition: 28px for dense toolbars).

**Parts:** __icon; __input; __clear; __kbd.

**States and behavior:**

- :placeholder-shown (kbd shown, clear hidden)
- has value (clear shown)
- :focus (kbd hidden)
- :disabled (neither clear nor kbd)
- inside gw-toolbar__start: 240px basis that grows
- aria-keyshortcuts="/" on the input: the ONE kit.js "/" hook focuses it, only when focus is not in an editable field (input, textarea, select, contenteditable), no modifier is pressed and no menu is open; inside an open modal only its own field counts
- single-key shortcuts off: <html data-gw-single-keys="off"> (set by input[data-gw-single-keys-toggle], Settings › General, default on, remembered) and the "/" hook does nothing

**Accessibility:** The input is named with aria-label or an sr-only label. aria-keyshortcuts="/" and the __kbd hint go only on a field the shared kit.js "/" hook serves (WCAG 2.1.4: it never fires while typing in a field and can be turned off); pages write no keydown handler of their own. The clear button is a 24×24 target named 'Clear search'. kbd is aria-hidden. Use <search> or role=search at page level and send result counts to a status region.

### `gw-file-drop`

Upload zone (PF4J plugin JAR, OpenAPI, kubeconfig): a <label> around a transparent input type=file, so click, keyboard and drop work without script. The app sets data-state.

[SCSS](scss/components/_file-drop.scss)

**Parts:** __input; __icon; __title; __hint.

**States and behavior:**

- data-state=idle (dashed strong border, neutral tile)
- data-state=dragover (solid brand-fg border, bg.selected tint, brand tile)
- data-state=uploaded (solid default border, success tile, mono file name)
- data-state=error (danger border and hint, danger tile; aria-invalid on the input)
- :disabled (raised, muted)
- keyboard focus: ring via :has(input:focus-visible)

**Accessibility:** The label text names the input and the hint describes it. Remove and replace actions go outside the label. Announce upload results in a status region. State changes icon and text as well as colour.

### `gw-form`

Form layout: fieldset sections with a legend and description. Single column below lg; from lg the description sits on the left third and the rows on the right, except inside a dialog or drawer. Optional sticky action bar.

[SCSS](scss/components/_form.scss)

**Variants:** --sticky-actions (__actions sticks to the bottom of the scrolling main as an overlay bar with 'Unsaved changes' + Discard + Save).

**Parts:** __section; __section-header; __legend; __description; __rows; __row (kit addition); __actions; __note (kit addition).

**States and behavior:**

- sections divided by a subtle rule with 32px on both sides
- stacked inside gw-dialog / gw-drawer at any width

**Accessibility:** One legend per fieldset, or <section aria-labelledby> with a heading in __section-header. On submit, focus the first invalid field. The primary action is type=submit. __note has role=status and is rendered only while the form is dirty.

### `gw-tabs`

Sibling views of one object on one page (endpoint: Tools & policies / Tokens / AI apps / Settings; connectors: Installed / Templates / MCP servers). --contained switches the view of the same data in a toolbar (Activity: Events / Traces). Not for flow steps (gw-stepper), page navigation (gw-nav) or 2–3 value settings (gw-segmented).

[SCSS](scss/components/_tabs.scss)

**Variants:** (default) underline tabs, 40px strip on a 1px rule; --contained: segmented-style tabs in a bordered raised container, 32px overall (26px tabs), hugs its content.

**Parts:** __list (role=tablist, scrolls horizontally, never wraps); __tab (role=tab <button>, optional leading gw-icon); __count (gw-counter metrics: 18px pill, tabular, bg.active; brand tint when its tab is selected; sr-only words); __panel (role=tabpanel, tabindex=0, [hidden] when not selected).

**States and behavior:**

- [aria-selected=true]: 2px brand-fg bottom border + primary text + semibold (contained: overlay pill + border-strong + shadow-sm + semibold)
- :hover: primary text + border-strong bar hint (contained: bg.hover)
- :disabled (kit.js skips it); [aria-disabled=true] styled for parity
- :focus-visible: inset ring
- panel [hidden]

**Accessibility:** role=tablist/tab/tabpanel, aria-controls ↔ aria-labelledby, tablist named after its object. kit.js: ←/→ Home End with automatic activation, roving tabindex, skips native disabled tabs. Counts carry sr-only words ('14 tools'). Selection has three cues (bar, weight, text colour); the bar is a border so it survives forced colours.

### `gw-menu-host / gw-menu / gw-popover`

The kit's only dropdown. role=menu holds actions on one object; role=listbox picks one value with a check (sort, time range); gw-popover (role=dialog) holds a small form next to its trigger (add filter, columns, appearance). Opens on click / Enter / Space only.

[SCSS](scss/components/_menu.scss)

**Variants:** gw-menu--right (align to the trigger's end edge); gw-menu--wide (320px, two-line items); gw-menu__item--danger (opens a gw-dialog--danger, never acts); gw-popover--right (align to the trigger's end edge); gw-menu--up (opens above the trigger; state class set by kit.js when there is more room above); gw-popover--up (the same for a popover); gw-menu__item--active / [data-active] (the option an aria-activedescendant listbox points at: hover wash + inset ring; command palette).

**Parts:** gw-menu-host (position: relative; trigger must be its direct child); gw-menu: __group __item __icon __label __hint (block inside __label, or truncating inline value at row end) __check __kbd __divider __note; gw-popover: __header __title __body __footer.

**States and behavior:**

- [hidden] closed
- :hover / :focus-visible: bg.hover wash + inset ring; hint and kbd lift to text.secondary (danger: danger.subtle wash)
- :disabled / [aria-disabled=true]: disabled text, no wash
- [aria-selected=true] / [aria-checked=true] shows __check (the space is kept otherwise)
- trigger [aria-expanded=true]
- __item--active / [data-active] (aria-activedescendant listboxes)
- open: TOP LAYER (popover="manual" + showPopover()), position: fixed from the trigger's rect, at least 4px from the viewport edge, flipped --up when there is more room above, follows scrolling and resizing; a gw-table-wrap or a dialog body never clips it
- no Popover API: the panel stays absolute inside its host (--up still flips it)

**Accessibility:** Trigger: <button aria-haspopup=menu|listbox|dialog aria-expanded aria-controls> as a direct child of .gw-menu-host. Items tabindex=-1; kit.js handles arrows, Home / End, Esc (returns focus), and closes on Tab / outside click; one panel open at a time; option / menuitemradio update aria-selected / aria-checked and [data-gw-menu-value]. Popover: role=dialog + aria-labelledby → __title; focus goes to the first field and leaving closes it. __kbd is aria-hidden (aria-keyshortcuts only for real shortcuts); group headings are __note aria-hidden referenced by role=group aria-labelledby. z --gw-z-popover, overlay surface, entry drop only inside gw-motion.

### `gw-tooltip`

CSS-only short hint next to its trigger: the name of an icon-only button, the absolute time behind a relative one, why a control is unavailable. Never interactive content (use gw-popover).

[SCSS](scss/components/_tooltip.scss)

**Variants:** (default) above, centred; --bottom (below); --start (aligned to the trigger's start edge); --end (aligned to the trigger's end edge); side and alignment compose.

**Parts:** __bubble (role=tooltip; bg.inverse / text.inverse, caption type, radius sm, max 280px, transparent border for forced colours).

**States and behavior:**

- shown on .gw-tooltip:hover (after duration-base) and :has(:focus-visible) (at once)
- stays while the pointer moves onto the bubble (gap bridge)
- hidden while the wrapped trigger has [aria-expanded=true]
- plain-text trigger child with tabindex=0: dotted underline + focus ring

**Accessibility:** aria-labelledby → bubble when it is the name, aria-describedby when it adds information. Hoverable (WCAG 1.4.13). Use aria-disabled (not disabled) so keyboard users reach the reason. Esc-to-dismiss is not provided by kit.js (gap). RTL-safe centring.

### `gw-dialog / gw-drawer`

Native <dialog> opened with showModal(). gw-dialog is centred and holds one question or one short task (revoke a token, create an endpoint). gw-drawer is pinned to the inline-end edge at full height, for reading beside the list (event detail, trace).

[SCSS](scss/components/_dialog.scss)

**Variants:** gw-dialog--sm (400) / --md (560, default) / --lg (720); gw-dialog--danger (irreversible actions AND untrusted code: unsigned plugin install, external MCP server review; header icon tile in the danger tone; confirm is gw-btn--danger and names the object; Cancel has autofocus); gw-drawer--md (560, default) / --lg (720), never wider than the viewport; gw-dialog--warning (risky but reversible confirm: publish the tunnel, change the port, accept calls from any address; amber icon tile; the confirm is a normal or primary button, never --danger; Cancel has autofocus; an Undo toast never brings the exposure back).

**Parts:** __header (grid: [gw-icon-tile] title + description [close]; drawer: fixed bar with a bottom rule); __title (h2, referenced by aria-labelledby); __description (aria-describedby); __close (gw-btn ghost icon sm, aria-label Close, data-gw-dialog-close); __body (the only scroller; 4px top room for focus rings); __footer (end-aligned actions: secondary first, primary / danger last); <form method=dialog> wrapper is display: contents.

**States and behavior:**

- [open] (native; closed = UA display: none)
- ::backdrop = bg.backdrop
- data-gw-static: backdrop click does not close (forms with input)

**Accessibility:** Focus trap, Esc, inert page and focus return come from showModal(). aria-labelledby → title, aria-describedby → description. EVERY gw-dialog names its initial focus in the markup: autofocus on the first field (or the first control when there are no fields), or on Cancel in a confirmation (Deny in the OAuth sign-in consent). Motion: the dialog fades, rises and scales; the drawer slides from the inline-end edge (RTL-aware). Reduced motion: fade only.

### `gw-toast-region / gw-toast`

Short-lived feedback in the bottom-end corner: the result of an action (Token revoked, with Undo), or an event that needs no answer now. Never the only place a decision or an error lives.

[SCSS](scss/components/_toast.scss)

**Variants:** gw-toast--brand / --success / --warning / --danger / --info / --neutral (default).

**Parts:** gw-toast-region (fixed bottom-end, z --gw-z-toast, width --gw-layout-toast-width, max viewport − 32px, clicks pass through gaps); __icon (tone fg); __content; __title; __body; __actions; __close (gw-btn ghost icon sm, 'Dismiss notification'); 3px inline-start bar in tone fg (::before).

**States and behavior:**

- enter: fade; rise inside gw-motion
- auto-dismiss after 6 s (kit.js; timeout: 0 keeps it)

**Accessibility:** Region <section aria-label="Notifications"> (kit.js creates one if missing and appends to the first one). Toasts are role=status, role=alert only for danger. Tone is backed by an icon and words. Do not auto-dismiss a toast whose actions the user must reach.

### `gw-alert`

A message about a state that holds while it is true: inline in a section or form, as a --banner above the page for app-wide state (Core stopped, Tunnel is public), or --compact on one line in a card, row or form. Says what happened and what to do.

[SCSS](scss/components/_alert.scss)

**Variants:** --info / --success / --warning / --danger / --neutral (default) / --brand; --banner (full width, no radius, bottom rule only, page-gutter padding; actions move under the text below sm); --compact (one line, 32px min, title and body inline and wrapping; link actions get a 24px target).

**Parts:** __icon (tone fg); __content; __title; __body; __actions (inside __content: under the text; a direct child: at the end of the row).

**States and behavior:**

- none (display only); role=status / role=alert once when it appears in response to something

**Accessibility:** Tone subtle fill + tone border + tone icon; text stays primary / secondary (passes 4.5:1 on every subtle fill in all four themes). Never colour alone: icon + title words. A static alert has no role.

### `gw-stepper`

Position in a short linear flow of 3–5 steps (Add connection: Choose type, Configure, Test, Add to endpoint; onboarding). Shows progress; not navigation, not tabs, not a checklist. --guide is the short first-run guide whose steps lead to the pages where the work is done.

[SCSS](scss/components/_stepper.scss)

**Variants:** (default) horizontal from md (960px) with connectors filling the row, vertical below md; --vertical (always vertical: side rail, onboarding); --guide (first-run guide on Overview: always vertical, the label is an h3, __content holds the step's actions; actions of an upcoming step are aria-disabled with a reason).

**Parts:** __step (<li>, carries data-state); __marker (24px circle, aria-hidden); __label; __description (choice made, or what failed); __content (--guide); __actions (--guide).

**States and behavior:**

- data-state=complete: brand solid marker + check icon, brand connector after it, sr-only ', completed'
- data-state=current: 2px brand ring + brand-subtle fill + brand number + semibold label, aria-current=step
- data-state=upcoming: border-strong ring + muted number + secondary label
- data-state=error: 2px danger ring + danger-subtle fill + x icon, danger description, sr-only ', failed', aria-current=step

**Accessibility:** <ol aria-label="<flow name>">; markers are decorative; complete / failed are spoken through sr-only text and current through aria-current=step; never colour alone (icon, ring, weight). --guide: the label is a heading one level below the enclosing heading (h3 under a card h2); an upcoming step's actions are aria-disabled + aria-describedby → a visible reason.

### `gw-paginator`

Moving through long lists. Compact: range + prev / next under server-sorted tables. Numbered: page buttons for short browsable lists (connector catalog). Load older: a cursor button for the audit log (never page numbers).

[SCSS](scss/components/_paginator.scss)

**Variants:** compact (__info + gw-btn ghost icon sm prev / next); numbered (ul.__controls of __page buttons, aria-current=page, ellipsis gap); load older (gw-btn sm, aria-busy + gw-spinner while loading, end-of-log text).

**Parts:** __info (muted, tabular); __controls (div, or ul of li); __page (gw-pressable sm, 28px; <span> = gap marker inside an aria-hidden li).

**States and behavior:**

- __page[aria-current=page]: bg.selected + brand border + brand text + semibold
- :hover wash, :focus-visible ring
- :disabled prev / next at the ends
- [aria-busy=true] on Load older

**Accessibility:** <nav aria-label="… pages"> when it navigates; page buttons are named 'Page 3' and prev / next 'Previous page' / 'Next page'; announce the new range with window.gwAnnounce after a change; targets ≥ 28px.

### `gw-skeleton`

Placeholder in the shape of content that is about to arrive (first render of a list, card, chart or table when the wait passes about 300 ms). Not for in-place actions (spinner), long jobs (gw-progress) or failures (gw-alert--danger).

[SCSS](scss/components/_skeleton.scss)

**Variants:** (base) 16px bar, full width; --text (12px bar in a 20px line box; the last of several lines is 64%); --block (64px panel; height may be overridden); --circle (32px disc).

**States and behavior:**

- calm opacity pulse 1.6 s; with reduced motion 3.2 s, opacity only, never a shimmer

**Accessibility:** Blocks (or their row / wrapper) are aria-hidden. The loading region carries aria-busy=true plus an sr-only role=status naming what loads; remove both when content arrives. Fill bg.active reads on surface, raised and canvas in every theme.

### `gw-code / gw-code-block / gw-tok-*`

Literals shown verbatim in mono with ligatures off. gw-code is an inline chip for one literal (tool name, slug, host, path, token prefix). gw-code-block is a multi-line block for SQL, commands, JSON, TOML and command output, with copy via data-gw-copy-from. gw-tok-* are the syntax token colours from --gw-color-code-*.

[SCSS](scss/components/_code.scss)

**Variants:** gw-code--secret (masked placeholder ‹masked›: secret colour, dashed edge; the guillemets are the non-colour cue); gw-code-block--numbered (CSS-counter line-number gutter, sticky on horizontal scroll, not copied, not read aloud; --gw-code-gutter: 4ch for 1000+ lines); gw-code-block--wrap (pre-wrap, overflow-wrap anywhere, hanging indent); gw-code-block--scroll (max-height 320px, override with --gw-code-block-max-height); gw-tok-keyword; gw-tok-string; gw-tok-number; gw-tok-comment; gw-tok-function; gw-tok-property; gw-tok-punctuation; gw-tok-secret; gw-tok-invisible (a visible escape such as ‹U+202E›, ‹U+200B›, ‹TAB›, ‹SP› written INSTEAD of an invisible, bidi, zero-width or control character, a tab or a trailing space; dashed frame + bold, code point and name in title; required in approval requests); gw-tok-confusable (a real non-ASCII look-alike letter outside a string literal, such as U+043E in an identifier; framed, with the code point in title).

**Parts:** gw-code-block__header (36px); gw-code-block__lang (overline, muted); gw-code-block__filename (mono, truncates); gw-code-block__actions (end, ghost sm button 4px from the edge); gw-code-block__copy; gw-code-block__copied; gw-code-block__body (scroll region); gw-code-block__pre; gw-code-block__line.

**States and behavior:**

- [data-copied] on the copy button (kit.js, 1.6 s) swaps __copy for __copied
- __body:focus-visible (inset ring)
- JSON actions may compose `gw-segmented--sm` in __actions for Pretty / Original views. Pretty adds structural whitespace without changing literal tokens or safety markers; Original restores the source segments. Copy follows the selected view. Hide the switch when formatting is unavailable or would leave the text unchanged.

**Accessibility:** __body is a keyboard-scrollable region: tabindex=0 + role=region + aria-label. The copy button has visible text plus a gw-sr-only object ('Copy SQL query'). The copied face changes the accessible name to 'Copied', and kit.js announces it. Line numbers use content: counter() / "", so they are neither read nor copied. With line spans present, the line container becomes a grid, so the copy stays exact. Every token colour is 4.5:1 or more on code.bg in all four modes.

### `gw-diff`

Unified diff of the exact change a human confirms: what Connect writes into a client config (~/.codex/config.toml), what k8s_apply changes in a manifest.

[SCSS](scss/components/_diff.scss)

**Variants:** gw-diff__line--add; gw-diff__line--del; gw-diff__line--context; gw-diff__line--hunk; gw-diff--wrap (soft-wraps long lines instead of scrolling sideways; a wrapped line hangs 2ch under its text, gutters and marker stay on the first line; always on inside approval-request).

**Parts:** gw-diff__header; gw-diff__filename; gw-diff__summary; gw-diff__body; gw-diff__line; gw-diff__gutter; gw-diff__marker; gw-diff__text.

**States and behavior:**

- __body:focus-visible (inset ring)

**Accessibility:** The + and − (U+2212) markers are text, so the change survives greyscale, high contrast and forced colours. Markers and gutters are aria-hidden; each changed line carries a gw-sr-only prefix ('Added: ' / 'Removed: ') that is excluded from text selection. __body is a named, focusable scroll region. All rows share one subgrid, so gutters line up and washes span the full width. Gutter and marker use diff fg on their wash (4.5:1); line text stays code.text.

### `gw-copy-field`

Read-only value plus a Copy button: endpoint URL, CLI command, and a token at the one moment it is visible.

[SCSS](scss/components/_copy-field.scss)

**Variants:** gw-copy-field--secret-once (warning frame; the <code> value wraps and stays fully visible; __hint 'Shown once. Store it in your password manager.').

**Parts:** gw-copy-field__value (<code>, or <input readonly> inside a gw-field); gw-copy-field__button (gw-btn gw-btn--sm); gw-copy-field__copy; gw-copy-field__copied; gw-copy-field__hint.

**States and behavior:**

- [data-copied] on __button swaps the faces (kit.js)
- focus ring on the whole field while an <input> value is :focus-visible

**Accessibility:** Name the field: role=group + aria-labelledby around a <code> value, or <label for> on an <input readonly> value (focusable; Ctrl+A selects it). The button text includes a gw-sr-only object ('Copy endpoint URL'). In --secret-once the button's aria-describedby points to the hint. The field is 36px tall: a 28px button inset by 4px.

### `gw-table-wrap / gw-table`

Native <table> for dense, sortable lists of objects with the same fields (Activity calls, connections, tokens, secrets). The wrap is the framed scroller.

[SCSS](scss/components/_table.scss)

**Variants:** gw-table--dense (32px rows and header); gw-table--interactive (each row has a __row-link; pointer over the row); gw-table--sticky (sticky thead inside a wrap with --gw-table-max-height); gw-table__cell--num; gw-table__cell--mono (body cells only); gw-table__cell--actions; gw-table__cell--select; gw-table__cell--optional (a secondary column: Type, Credentials, Version, AI app, Duration; hidden below lg; on the th AND every td; never the row key, a status or the actions); gw-table__cell--sticky-end (the action cell of a full-width alert row, e.g. Test again of an unreachable billing-api: pinned to the end edge while the wrap scrolls, keeps the row's hover / selected fill).

**Parts:** gw-table__sort; gw-table__sort-icon (--none --asc --desc); gw-table__row-link; gw-table__empty; __cell--wrap; __cell--truncate (--gw-cell-max).

**States and behavior:**

- th[aria-sort=none|ascending|descending] shows the matching icon; a sorted header turns primary (kit.js cycles and announces)
- tr[aria-selected=true]: selected fill + 2px brand bar (kit.js mirrors data-gw-select-row / data-gw-select-all)
- row-link :focus-visible: ring drawn on the row
- __sort and wrap :focus-visible
- inside gw-card--flush: frameless, card inner corners, inset ring
- gw-table-wrap scroll shadows: an edge with more columns behind it shows a soft shade on that side (pure CSS, no script)
- Activity events: Time | Decision | Tool · connection (stacked) | AI app · endpoint (stacked) | Duration; extra columns only with __cell--optional; never scrolls sideways at 1440 × 900

**Accessibility:** A real table with <caption> (gw-sr-only when a heading names it); the wrap is role=region + aria-labelledby (the caption) + tabindex=0. Sorting uses a button inside th[aria-sort]. Row checkboxes have aria-labels that name the row, and the checkbox carries the selection state. The row link's text plus a gw-sr-only phrase names the row. Row actions are always visible, sit above the stretched link and have targets of 24px or more. A hidden data table (the numbers behind a chart) is not a gw-table: a plain <table> inside <div class="gw-sr-only">.

### `gw-chart / gw-legend / gw-sparkline`

Library-free charts in HTML, CSS and SVG: a stacked histogram of calls over time by decision, a horizontal bar list (top tools, share of a whole) and a sparkline. Data enters as unitless --v custom properties.

[SCSS](scss/components/_chart.scss)

**Variants:** gw-chart--hbar; gw-chart__segment--success / --warning / --danger / --info / --neutral / --brand (tone fg); gw-chart__segment--series-1 … --series-6 (chart.1–6; no modifier = chart.1); gw-legend__swatch (same modifiers) + --hatch; gw-sparkline--brand / --success / --warning / --danger / --info / --neutral (default brand fg); interactive histogram: __bar as <button> with aria-pressed.

**Parts:** gw-chart__plot; gw-chart__gridline; gw-chart__bar; gw-chart__segment; gw-chart__axis; gw-chart__label; gw-chart__value; gw-legend__item; gw-legend__swatch; gw-legend__value.

**States and behavior:**

- __bar[aria-pressed=true] (interactive): selected fill + 2px brand bar on top
- __bar:focus-visible ring, hover wash

**Accessibility:** The chart root is role=img with an aria-label stating the finding, and the same numbers sit beside it in a <table> with a caption inside <div class="gw-sr-only"> (the class goes on the wrapper, never on the table). In the interactive histogram the root is role=group and each bar button's aria-label carries its numbers. Series differ by legend words, fixed stack order and 1px gaps, not colour alone. Marks paint with tone fg or chart tokens (3:1 or more), never solids, and keep their fills in forced colours. The sparkline is aria-hidden next to a number, otherwise role=img + aria-label.

### `gw-waterfall`

Where one call's time went (event-detail Timing): gateway work vs time in the resource vs the idle wait for a human decision.

[SCSS](scss/components/_waterfall.scss)

**Variants:** gw-waterfall__span--gateway (brand); gw-waterfall__span--resource (info); gw-waterfall__span--approval (warning, hatched, outlined).

**Parts:** gw-waterfall__row; gw-waterfall__label; gw-waterfall__track; gw-waterfall__span; gw-waterfall__value.

**Accessibility:** An <ol> named by aria-label or aria-labelledby (tool, connection, total). Tracks are aria-hidden: each row reads 'phase, duration'. Spans are placed with --start/--len (0..1) and are never thinner than 2px. The approval wait is hatched, which says 'idle' without colour; the legend uses gw-legend__swatch--hatch for it. Rows share a subgrid, and labels truncate at narrow widths.

### `gw-timeline`

Vertical feed of what happened, in order: the approval path (requested → notified → approved → executed), the steps of an agent trace.

[SCSS](scss/components/_timeline.scss)

**Variants:** gw-timeline__marker--brand / --success / --warning / --danger / --info / --neutral (default); empty marker renders a dot (dense trace steps).

**Parts:** gw-timeline__item; gw-timeline__marker; gw-timeline__content; gw-timeline__title; gw-timeline__meta; gw-timeline__comment; gw-timeline__time.

**States and behavior:**

- li[aria-current=step]: marker filled with the tone solid (icon in on-solid) + semibold title

**Accessibility:** An <ol> with an aria-label. The title says what happened in words; the tone and icon (aria-hidden) are extra cues. Times are <time datetime> with the full ISO time in title. The current step is shown by fill plus weight, not colour alone, and the comment corner radii are logical, so RTL is correct.

## Reusable compositions

A composition combines existing primitives and adds layout only. Its demo demonstrates the states listed here. Preserve the shared product rules for secrets, approvals, environment boundaries, client grants, and audit links when implementing it in Angular.

### `setup-map`

Light visualisation of what to configure and how it connects, in SETUP order: Secrets & resources → Connections (where tools run; the secret is injected here, and a side note says so) → Policies (decides each call: mode, limits, approval) → Endpoints (one MCP URL, token or OAuth) → AI apps, left to right (top to bottom when stacked), matching getting-started and the onboarding stepper. A caption under the map (aria-hidden) and the list's aria-describedby sentence say: "Set it up left to right. An agent's call travels the other way: AI app → endpoint → policy → connection, where the secret is injected." Each node shows its icon tile, title, what it does, a one-line status with counts (a todo status says what it waits for or 'start here'), a state badge (done / attention / todo) and a Configure link to its page. Decorative arrows join the nodes and take their state from the two nodes they connect. The --wiring variant for endpoint-detail is unchanged: the AI apps that call the endpoint (client marks + status) → the endpoint (URL copy field, sign-in badges, mode summary '6 read only · 3 approval · 5 off', policy) → its connections (type tile, name + env marker, health).

[SCSS](elements/_setup-map.scss) · [Interactive demo](elements/setup-map.html)

**Composes:**

- gw-card
- gw-icon-tile
- gw-icon
- gw-badge
- gw-btn
- gw-client-mark
- gw-status
- gw-env
- gw-copy-field
- gw-kv
- gw-mode
- gw-code
- gw-text-* / gw-fg-* / gw-mono utilities

**States and behavior:**

- step data-state=done: gw-badge--success circle-check 'Done', tile --brand
- step data-state=attention: gw-badge--warning triangle-alert 'Needs attention', tile --warning; the status names the problem ('billing-api unreachable')
- step data-state=todo: gw-badge--neutral circle-dashed 'Not set up', tile --neutral, gw-card--dashed; the status says what it waits for ('needs a connection first') or 'start here'
- connector done→done: brand-fg solid; a todo on either side: border-strong dashed; otherwise: border-strong solid; arrows centred between the cards
- layout: compact vertical below md, one row per node md–lg, horizontal chain from lg (equal columns, arrows in the gap, the note in a shared second row)
- --vertical: always stacked with compact nodes, for narrow columns
- --wiring: AI apps → endpoint → Connections (endpoint-detail); names truncate with title, the status column is capped at 128px and wraps
- demo: all set up, first run (only the master key done), needs attention (billing-api unreachable, Cursor needs restart), wiring, narrow column
- setup order: Secrets & resources → Connections → Policies → Endpoints → AI apps; 'start here' sits on the first todo node (usually Secrets & resources)
- caption + aria-describedby: 'Set it up left to right. An agent's call travels the other way: AI app → endpoint → policy → connection, where the secret is injected.'

**Reference screens:**

- [pages/onboarding.html](pages/onboarding.html)
- [pages/endpoint-detail.html](pages/endpoint-detail.html)

### `how-it-works`

Compact, collapsible strip of 3–4 numbered steps that explains a mechanism before the user configures it: what happens to a secret, how an AI app is authorized, what one-click Connect writes, three ways to extend connectors. Each step has a number (gw-counter, aria-hidden; the <ol> gives the position), an icon tile, a short bold title and one line of explanation. An optional Learn more link sits under the steps. The header keeps the title and a one-line summary visible when the strip is collapsed; the disclosure is a ghost icon button with data-gw-toggle, aria-expanded and aria-controls.

[SCSS](elements/_how-it-works.scss) · [Interactive demo](elements/how-it-works.html)

**Composes:**

- gw-card
- gw-btn
- gw-icon-tile
- gw-icon
- gw-counter
- gw-link
- gw-code
- gw-text-* / gw-fg-* utilities

**States and behavior:**

- expanded: button aria-expanded=true, panel visible, chevron-up
- collapsed: aria-expanded=false + hidden on the panel, chevron-down; the title and summary stay
- layout: a list below lg (the title line centred on the tile); from lg a strip of 3 or 4 equal columns with a muted chevron centred in the gap, level with the tiles
- --vertical: always a list, for the settings column
- demo: Secrets (4 steps, expanded, Learn more), Connectors (3 steps, no Learn more), Authorization (--vertical), AI apps (collapsed)

**Reference screens:**

- [pages/secrets.html](pages/secrets.html)
- [pages/clients.html](pages/clients.html)
- [pages/connectors.html](pages/connectors.html)
- [pages/settings.html](pages/settings.html)

### `getting-started`

First-run checklist card with a progress summary ('5 of 6 done' + gw-progress) and six ordered steps: Protect your master key, Add a connection, Create an endpoint, Connect your AI app, Try a tool in Playground, Set up approval notifications. Each step has a status icon, a title, one line on why it matters and one action. Only the current step gets the primary button; the others are links, so steps can be done out of order. Done steps fold to one line with what was done. A ghost button dismisses the checklist; that can be undone, so the app shows a toast with Undo. When all steps are done the card becomes a calm summary with the steps behind a disclosure.

[SCSS](elements/_getting-started.scss) · [Interactive demo](elements/getting-started.html)

**Composes:**

- gw-card
- gw-icon-tile
- gw-icon
- gw-progress
- gw-btn
- gw-link
- gw-code
- gw-text-* / gw-fg-* / gw-mono utilities

**States and behavior:**

- step data-state=done: circle-check in a gw-fg-success __icon span; one line of title + meta; sr-only ', done'
- step data-state=current: circle-dot in gw-fg-brand; semibold title; aria-current=step; the only gw-btn--primary
- step data-state=todo: circle-dashed in gw-fg-muted; gw-btn--link action; sr-only ', not done yet'
- fresh install (0 of 6, step 1 current)
- in progress (5 of 6, the demo world as on Overview: done rows folded, Try a tool in Playground current while the later notifications step is already done)
- complete (gw-getting-started--complete: success tile summary, steps behind a data-gw-toggle disclosure, no progress bar, no confetti)
- dismissed (card removed; toast with Undo in the app)
- action / meta column capped at 256px; below sm the action drops under the text

### `approval-request`

The decision UI for one mutation an agent asked for. It shows the exact action verbatim (gw-code-block for SQL, command or HTTP; gw-diff for k8s_apply; always wrapped, invisible characters as gw-tok-invisible escapes, look-alike letters as gw-tok-confusable), with the connection and its gw-env (__target keeps them on one line), endpoint, AI app and token, and a countdown to auto-deny. Below that: why approval is needed (the policy rule in words plus its id) and the impact, then the agent's note marked unverified. Pending requests end with a comment (optional on dev and stage, REQUIRED on prod by default) and Deny / Approve and run. After a decision the footer is a read-only history (gw-timeline). The --compact row for lists has Review only, never Approve. On the desktop there is one approver, you ('by you'); multi-approver partial is a server-mode state. Through the protocol (MCP 2026-07-28 input_required) a client gets the request id, the verbatim action (read-only) and a link to approvals.html#ap-<id>, never a field it could fill to approve.

[SCSS](elements/_approval-request.scss) · [Interactive demo](elements/approval-request.html)

**Parts:** __header __heading __title (tabindex=-1, focus target of Review links); __target (connection gw-code + sr-only 'environment' + gw-env: kept on one line); __meta (gw-kv--inline: Mode · Endpoint · AI app · Token · Requested); __client; __status __countdown __countdown-text (role=timer); __body __section __section-header __facts __rule __note; __footer __decision (role=group) __decision-row __buttons; __history __result __toggle __when-closed __when-open; --compact: __summary __from (client, wraps whole) __preview __end.

**Composes:**

- gw-card
- gw-card--muted
- gw-code
- gw-env
- gw-env--sm
- gw-mode
- gw-kv
- gw-kv--inline
- gw-kv--dense
- gw-client-mark
- gw-badge
- gw-progress
- gw-code-block
- gw-code-block--wrap
- gw-tok-*
- gw-diff
- gw-alert
- gw-alert--compact
- gw-tooltip
- gw-btn
- gw-field
- gw-textarea
- gw-timeline
- gw-status
- gw-link
- gw-stack
- gw-section
- gw-icon
- gw-text-*
- gw-fg-*
- gw-tabular
- gw-truncate
- gw-mono
- gw-sr-only

**States and behavior:**

- data-state=pending (default): badge warning hourglass 'Waiting for approval', countdown 'Auto-deny in 4:12' (data-gw-countdown + data-gw-countdown-text, gw-progress--sm --warning fed by --gw-countdown), decision group: comment, Deny (--danger), Approve and run (--approve, last)
- pending, urgent: kit.js sets [data-urgent] at 60 s or less; the markup swaps gw-progress--warning for --danger and puts gw-fg-danger on the whole timer line; the app announces once via role=alert
- prod: gw-env--prod in the title plus a gw-alert--danger 'This runs on production' first in the body; comment required (below); prod window 10 min; prod connections only through incident-prod, prod-vm only through runbooks
- data-state=approved: badge success user-check 'Approved'; the footer is the history, current step (aria-current=step) is the run
- data-state=rejected: badge danger user-x 'Rejected'; history with the approver's comment (the contract's 'denied': a human Deny is recorded as Rejected; policy denials never create a request)
- data-state=expired: badge neutral timer-off 'Expired', gw-alert--neutral --compact 'Nothing ran', history; read-only
- data-state=executed: badge 'Approved' plus gw-alert--success 'Executed in 38 ms', '1 row affected', View event, history
- data-state=failed: badge danger circle-alert 'Error' plus gw-alert--danger (what happened, what to do), output under a data-gw-toggle disclosure (chevron follows aria-expanded), View event, history
- --compact: one row: client mark, title with env and client, first line of the action truncated (full in title), countdown, 'Review' link to the request title; never Approve or Deny
- narrow container (< sm 640px, e.g. the Approvals split at a 960px window): the status column drops under the heading, start-aligned; Why and Impact stack below 2 × 256px
- comment required (prod by default; Settings › Approval rules 'Require a comment on prod'): no '(optional)', a gw-field__required marker, required on the textarea; Approve and run is aria-disabled=true with aria-describedby 'Add a comment to approve on prod' and data-gw-enable-when="#<comment>" (kit.js lifts it once the field holds non-blank text); Deny always works
- expiring (at 0:00, kit.js, no app round trip): data-state=expired, badge neutral 'Expired', timer 'Expired, denied automatically' (bar hidden), caption says nothing ran, Deny and Approve and run aria-disabled (described by the caption), comment read-only, gw-countdown-end bubbles, one announcement
- partial (SERVER MODE ONLY; desktop has one approver): badge warning user-check 'Approved by 1 of 2' + countdown; 'Approvals so far' history above the decision; 'Approve (2 of 2) and run' for the last approver; a person who already approved sees no buttons
- env mismatch: gw-alert--danger 'Prod connection on a dev endpoint' first in the body
- temporary bypass: dev/stage connection requests offer a separate secondary action for future calls to the same method, connection, and endpoint. The warning dialog uses `gw-select` for 15/30 minutes or 1/3/12/24 hours, focuses Cancel, and checks current eligibility before enabling. Pending requests retain their separate decision controls. Show the resulting expiry and offer management from the same action.

**Reference screens:**

- [pages/approvals.html](pages/approvals.html)
- [pages/overview.html](pages/overview.html)
- [pages/activity.html](pages/activity.html)

### `approval-notification`

Previews of how the same approval request appears outside the window. (1) The native desktop notification (macOS and Windows): ALWAYS Review and Deny only, never a blind approve. (2) A Slack message in Block Kit layout: header, fields, full statement, context, and Approve / Deny / Open in app, with Approve for linked approvers only. (3) A Telegram bot message with an inline keyboard. (4) The in-app toast: static anatomy plus a live gwToast with a Review action. Approve appears in a channel only when the request isn't on prod, the whole action is visible (Slack ≤ 3,000, Telegram ≤ 4,096 characters) and the policy does not require a comment; otherwise Deny + Open in app, and the message says why. On prod a channel never offers Approve, whatever the comment rule ('Runs on production: approve in the app.'). Each mock sits in a gw-card frame labelled with its channel (gw-client-mark --generic S / T, no brand logos). Mocks are inert and described by a gw-sr-only figcaption; every channel shows the environment and a fixed auto-deny time ('Auto-deny at 14:34:12').

[SCSS](elements/_approval-notification.scss) · [Interactive demo](elements/approval-notification.html)

**Composes:**

- gw-card
- gw-client-mark
- gw-client-mark--generic
- gw-icon-tile
- gw-logo
- gw-badge
- gw-env
- gw-env--sm
- gw-code
- gw-kv
- gw-kv--inline
- gw-code-block
- gw-code-block--wrap
- gw-tok-*
- gw-btn
- gw-toast
- gw-alert
- gw-alert--compact
- gw-grid
- gw-section
- gw-icon
- gw-text-*
- gw-fg-*
- gw-mono
- gw-truncate
- gw-tabular
- gw-sr-only

**States and behavior:**

- pending: Desktop: Review / Deny (always). Slack and Telegram: Approve / Deny / Open in app when the request isn't on prod, the whole action fits and no comment is required; otherwise Deny / Open in app. Toast: Review only
- decided: the channel message is edited in place; the buttons are replaced by a gw-badge with the outcome plus who and where ('Approved by you in GateRook · executed in 38 ms'; named people only in server-mode previews)
- expired: neutral timer-off 'Expired' badge, keyboard removed, 'Denied automatically at 13:10:40'
- too long for the channel (Slack 3,000 / Telegram 4,096 characters): the statement is never cut; Approve is dropped and Open in app remains
- live toast: window.gwToast tone warning with a Review action; it stays until used or dismissed and is withdrawn by the app when the request closes
- prod: Slack and Telegram never offer Approve, whatever the comment rule; they keep Deny + Open in app with the core's reason 'Runs on production: approve in the app.'; the decision (with the comment prod requires) is made in the app opened from the message
- comment required (off prod, when a policy requires it): Slack and Telegram drop Approve, keep Deny + Open in app and say a comment is required
- Slack defaults: direct messages to linked approvers recommended; a channel (#deploy-approvals in workspace acme in the demo set) is a deliberate choice with 'Everyone in the channel sees the exact action'

**Reference screens:**

- [pages/approvals.html](pages/approvals.html)
- [pages/settings.html](pages/settings.html)

### `tool-policy-list`

What agents may call on one endpoint and how. Every tool is grouped by its connection: connector icon tile, name, gw-env, connector type, tool and mode counts, and a 'Set all to…' menu. Each tool row shows MCP annotation badges and the Off · Read only · Approval · Bypass picker. Read only is disabled with a visible reason for destructive tools, there is a note where a tool has a read-only subset, and the picker is locked when the endpoint is read only. A 'Limits' disclosure row holds per-tool overrides, which can only be stricter than the environment policy and show what is inherited. A toolbar above has search, a mode filter and a live summary of counts per mode. A picker variant for Playground shows gw-mode and row links instead of the picker.

[SCSS](elements/_tool-policy-list.scss) · [Interactive demo](elements/tool-policy-list.html)

**Composes:**

- gw-toolbar
- gw-search
- gw-menu-host
- gw-menu
- gw-mode
- gw-card
- gw-icon-tile
- gw-icon
- gw-env
- gw-table-wrap
- gw-table
- gw-code
- gw-badge
- gw-segmented
- gw-btn
- gw-counter
- gw-field
- gw-input-group
- gw-input
- gw-link
- gw-empty

**States and behavior:**

- default: endpoint dev, 14 tools in 5 connection groups, summary '14 tools · 6 read only · 3 approval · 5 off'
- destructive tool: Read only <input disabled> + __mode-note 'Mutates data: approval, bypass or off' (lock icon, aria-describedby on the fieldset and the option)
- read-only subset: ssh_run keeps Read only, __mode-note 'Read only runs runbooks marked read only' (info icon)
- locked by the endpoint: incident-prod (prod-replica + prod-logs, prod), sql_execute <fieldset disabled> + note naming the read-only endpoint
- limits collapsed / expanded (data-gw-toggle, aria-expanded ↔ tr[hidden], chevron pair)
- limit overridden (filled input, 'Overrides dev policy: 1,000 rows', gw-counter on the toggle, Reset enabled) vs inherited (empty input, policy value as placeholder, 'Inherited from dev policy: …', Reset disabled); overrides only stricter
- filtered by mode: Mode: Approval (listbox aria-selected), '3 of 14 tools match', groups '1 of 4 tools shown', groups without a match omitted
- no match: summary '0 of 14 tools match' + gw-empty--compact in a flush card with Clear search
- picker (Playground): gw-table--interactive, __row-link names, gw-mode, env markers, selected tool tr[aria-selected] + aria-current
- tool of an external MCP server or plugin: 'Declared by <server>, not verified' (gw-text-caption gw-fg-muted) after the annotation badges; starts as Approval when added to an endpoint; Read only only for a tool the server declared read only and only after an explicit per-tool confirmation (jira_search_requests: option aria-disabled + 'Read only needs your confirmation for this tool', 'Confirm read only' → gw-dialog--sm --warning 'Let jira_search_requests run without approval?'); a tool not declared read only is approval, bypass or off (Read only natively disabled, 'Not declared read only: approval, bypass or off'): all 26 tools of github-mcp, which declares no annotations; 'Set all to… Read only' skips them and says so
- temporary bypass: an eligible dev/stage method offers a secondary action opening the same warning dialog as approval review. Display its expiry and original mode while active, with early cancellation. Production, core tools, read-only endpoints, Off tools, and permanent Bypass do not offer a grant. The original mode picker continues to edit the base mode and clears any temporary bypass when saved.

**Reference screens:**

- [pages/endpoint-detail.html](pages/endpoint-detail.html)
- [pages/policies.html](pages/policies.html)
- [pages/playground.html](pages/playground.html)

### `risk-callout`

Warns about the dangerous combination inside one endpoint: private data + untrusted content + external channel. The three ingredients appear as a small A + B + C formula of badges in a named list. Each is marked present or absent, and present ones name the tools behind them; only tools that are not Off count. One sentence explains the risk, followed by Review tools, Split endpoint and Dismiss for this endpoint (reason required, kept in the audit log). Built on gw-alert--warning. --compact is one line for endpoint lists; a list cell explains when there is no warning.

[SCSS](elements/_risk-callout.scss) · [Interactive demo](elements/risk-callout.html)

**Composes:**

- gw-alert
- gw-badge
- gw-icon
- gw-code
- gw-btn
- gw-dialog
- gw-icon-tile
- gw-field
- gw-textarea

**States and behavior:**

- present (data-state=present, 3 of 3, endpoints dev and stage-readonly, the latter published): ingredient badges --danger, tools named (sql_query reads dev-db · logs_search · http_request can POST to billing-api)
- partial (data-state=partial, 2 of 3, e.g. a draft endpoint with aws_describe + http_request): present badges --warning, absent ingredient neutral with check icon and 'No untrusted content'
- --compact: one line 'Risky combination: private data + external channel' in an endpoint table cell (data-state present / partial); no external channel → 'None: no external channel'
- dismissed (data-state=dismissed): gw-alert--neutral --compact 'Risk warning dismissed for dev' with who, when (relative + ISO title) and the reason, plus 'Show warning'
- dismiss dialog: gw-dialog--sm data-gw-static, required reason with focus (native validation), Cancel / Dismiss warning
- limited (data-state=limited, incident-prod): gw-alert--info + shield-check 'Contained combination', channel badge --warning 'External channel · limited' naming the fixed runbooks and the condition that would break it; never 'nothing can send data out'
- Split endpoint dialog (endpoint-detail #ep-dlg-split, opened by the callout's Split button): gw-dialog--md data-gw-static, choice of what moves (gw-choice-card--compact radios, the external channel pre-selected and 'Recommended · removes the risk'), new slug in a /mcp/ input group, before / after ingredient badges for both endpoints, AI apps to connect (checkboxes), primary 'Create <slug>'; initial focus on the checked radio

**Reference screens:**

- [pages/endpoint-detail.html](pages/endpoint-detail.html)
- [pages/endpoints.html](pages/endpoints.html)
- [pages/policies.html](pages/policies.html)

### `token-issue`

Access tokens of one endpoint. The tokens table lists each token by name and prefix only, with created, expires (warning badge under 7 days), last used (links to Activity filtered by the token) and status; Claude Desktop's bridge and Codex's helper grant (codex-laptop) are 'issued on the fly' rows with no gwt_… value or prefix. The 'Issue token' dialog asks for a name and an expiry (6 months / 184 days by default, with shorter lifetimes available) and shows the endpoint slug read-only; the IP allowlist (field and column) exists only in server mode, since the desktop core listens on 127.0.0.1 only. Its one-time result step has no close button and shows the full value in gw-copy-field--secret-once, the warning 'This is the only time you can see this token', the endpoint URL and a required 'I've stored this token' before Done (a token for a config file says it is stored there in plain text). Revoke opens a danger confirmation that names the token, says what stops working and confirms with a toast.

[SCSS](elements/_token-issue.scss) · [Interactive demo](elements/token-issue.html)

**Composes:**

- gw-card
- gw-table-wrap
- gw-table
- gw-badge
- gw-link
- gw-dialog
- gw-icon-tile
- gw-icon
- gw-field
- gw-input
- gw-select
- gw-tag-list
- gw-tag
- gw-btn
- gw-alert
- gw-copy-field
- gw-checkbox
- gw-kv
- gw-empty
- gw-toolbar
- gw-toast

**States and behavior:**

- table row Active (success, check)
- table row Expiring (warning, hourglass) + expiry badge 'In 3 days'
- table row Expired (neutral, timer-off), no action
- table row Revoked (danger, ban), no action
- issue form: idle (data-step=form, name autofocus, 6 months (184 days) selected, 'On Apr 24, 2027')
- issue form: invalid (aria-invalid + gw-field__error on the taken name; server mode only: gw-tag--danger + circle-alert + field error naming a bad IP such as 10.20.0.300)
- result (data-step=result): token shown once, no close button, Done blocked by the required checkbox
- revoke confirm: gw-dialog--sm --danger, Cancel autofocus, 'Revoke token codex-laptop', success toast
- empty: gw-empty--compact 'No tokens for this endpoint' + primary Issue token opening the form for incident-prod
- helper rows: 'Bridge (.mcpb) · issued on the fly' and codex-laptop 'gw-bridge headers helper, issued on the fly' (http_headers_helper is one command string running the shim ~/.gaterook/bin/gw-bridge headers …), expires 'After 15 min', no gwt_… value
- server mode only: Allowed IPs column and the IP allowlist field (tags + entry)

**Reference screens:**

- [pages/endpoint-detail.html](pages/endpoint-detail.html)
- [pages/settings.html](pages/settings.html)

### `client-card`

One MCP client (Claude Desktop, Codex, Cursor, Antigravity, claude.ai): whether it is installed and connected, which endpoints it uses, how it connects (bridge extension, config file, public URL via tunnel), how it authenticates, and the next step. Connect shows the endpoint choice, for Cursor and Antigravity the sign-in choice (token in the file, written in plain text to ~/.cursor/mcp.json, or sign-in through the browser; on a prod endpoint both stay in place unavailable with 'Not on prod endpoints' and the third way 'Through the bridge' is chosen) and the token step (name, lifetime). For file-based setup, 'What will change' starts collapsed; expand it to review the exact gw-diff of the config file (Codex: the core's managed block with http_headers_helper = "~/.gaterook/bin/gw-bridge headers …"), the backup path and the core's notes before anything is written. The auth note and any restart needed stay visible outside the disclosure. A client on several endpoints (Claude Desktop on dev and incident-prod, one .mcpb extension per endpoint) lists one row per endpoint with Disconnect, plus Add endpoint and Remove bridge. Cards whose client gets its token on the fly carry the trust boundary: 'Protects against other users and copied files, not against programs you run, including AI agents with a terminal.' The claude.ai card shows what is published, the three gates (Anthropic IP ranges reach /mcp/<slug> — read-only here, the one switch lives in Settings › Tunnel › Callers — while the sign-in page opens in your own browser, OAuth sign-in with a consent screen on this computer, the endpoint's own modes), the tunnel history, the 'Signed in' list with Revoke, and Publish through a warning confirm. --compact is a one-row variant for an endpoint's client list.

The AI apps page starts with compact rows: app mark, name, connection status and a disclosure chevron. Click a row or press Enter or Space to reveal its existing setup, endpoint, authentication and activity details in place. Rows stay collapsed on a normal visit; an app's hash link opens that row before focusing its disclosure button. Closing a row preserves its form choices. How it works and Manual setup also start collapsed.

Cursor browser sign-in enables OAuth for the selected local endpoint and opens its Add to Cursor link in the browser. The link carries only the endpoint URL. GateRook does not parse, back up or write Cursor's config, and the flow requires no config diff confirmation. Open in browser is the visible link action with an adjacent, named icon-only Copy action. After Cursor exchanges its authorization code, show Connected even before a tool call; keep Last call empty until a tool is called. An observed browser sign-in uses Manage sign-in to reach the endpoint's OAuth sign-ins and Revoke, without claiming ownership of Cursor's file.

[SCSS](elements/_client-card.scss) · [Interactive demo](elements/client-card.html)

**Composes:**

- gw-card
- gw-stack
- gw-grid
- gw-client-mark
- gw-status
- gw-kv
- gw-btn
- gw-alert
- gw-dialog
- gw-icon-tile
- gw-icon
- gw-field
- gw-select
- gw-diff
- gw-copy-field
- gw-switch
- gw-code-block
- gw-code
- gw-toast

**States and behavior:**

- connected (View activity, Open config and Disconnect for managed file entries; Manage sign-in for browser-installed OAuth access)
- detected, not connected (Open config + Connect)
- not installed (How to install, external + Check again)
- needs restart (full warning alert, clears on first call)
- error: config not writable (nothing written, fix command with Copy, Try again)
- claude.ai tunnel off
- claude.ai tunnel on (public URL readonly copy field; the IP allowlist shown read-only with a link to Settings › Tunnel › Callers, never a switch here)
- connect dialog (gw-dialog--lg data-gw-static: endpoint, default-collapsed 'What will change' disclosure with the diff and backup, visible auth note, Confirm and connect)
- connect flow for Codex (the core's managed block, http_headers_helper as one command string running ~/.gaterook/bin/gw-bridge, token not in the file, the core's notes, restart after connecting)
- disconnect confirmation (gw-dialog--sm --danger naming the client, Cancel autofocus)
- Connect secondary in a grid / primary when the card stands alone
- compact row (endpoint-detail · AI apps)
- compact disclosure row (AI apps): h3.gw-card__title contains button.gw-client-card__row with the mark, name, status and chevron; data-gw-toggle, aria-expanded and aria-controls reveal the existing body and footer; details start hidden, stay mounted on collapse, and open before focus on hash navigation
- claude.ai: what gets published (/mcp/stage-readonly, /oauth/*, /.well-known/oauth-*; UI, admin API, dev and incident-prod stay local), three gates, tunnel history ('Off since 12:10 · public today 08:40 to 12:10'), last call 12:08
- claude.ai 'Signed in' table (OAuth grants: AI app, endpoint, allowed by you, last used, expires) with Revoke → gw-dialog--sm --danger
- publish confirm: gw-dialog--md --warning listing exactly what becomes public and the three gates, Cancel autofocus; Undo never re-publishes
- sign-in consent: gw-dialog--md --warning with a code to match, Returns to, From = the IP of the browser that opened the sign-in page, Deny autofocus (Esc and no answer in 5 min deny), 'Allow access' primary (never --approve); it opens by itself when a request arrives (the demo trigger is in its own 'Sign-in request' section)
- several endpoints (Claude Desktop): gw-table--dense, one row per endpoint with View activity + Disconnect; footer Add endpoint + Remove bridge (danger confirm listing every endpoint)
- Cursor connect: sign-in choice (token in the file / sign in through the browser); token mode shows the token step (name cursor-laptop, expires 184 days), the plain-text warning, permissions and notes; browser mode hides the config diff and backup and opens Add to Cursor from Connect
- Connect on a prod endpoint: token and browser choices aria-disabled with 'Not on prod endpoints', 'Through the bridge' chosen, the stdio entry in the diff, the core's prod warning
- inline review with one endpoint: What <app> can use in words, no select
- Claude Desktop .mcpb review: default-collapsed 'What will change' disclosure with Installs, Runs, Config files none, Token none stored; Claude Desktop asks you to confirm; config-file fallback with diff, backup and restart behind a disclosure
- waiting for the app (Claude Desktop's install prompt): gw-status--checking 'Waiting for Claude Desktop', the primary keeps its label, aria-busy + aria-disabled, described by what to do in the app; the config-file fallback stays available
- waiting for sign-in (Cursor, browser sign-in): gw-status--checking 'Waiting for Cursor'; show 'Continue in Cursor', Open in browser to retry, and Manage sign-in; GateRook has not written a config entry or created a managed file link
- connected in the inline review: the card stays and ends with Check that it works (prompt in a gw-copy-field, Last call 'Waiting for the first request'); focus on the card's h3
- compact row with an aria-disabled Connect and a visible reason under the list (first run); the row name is an h3.gw-text-ui hash target

**Reference screens:**

- [pages/clients.html](pages/clients.html)
- [pages/endpoint-detail.html](pages/endpoint-detail.html)
- [pages/onboarding.html](pages/onboarding.html)

### `connection-form`

The schema-driven Configure step of Add connection (PostgreSQL demo), also the form of connection-new ?duplicate=<name> (settings copied, credentials never) and ?edit=<name> (secret stored: Replace / Rotate, or a reference). It has four gw-form sections: Connection (name slug, host, port, database, SSL mode); Credentials (User, plus a secret source picker of four gw-choice-cards that switches the gw-secret control: Encrypted here, Vault or OpenBao, GCP SM, AWS SM); Environment and safety (dev / stage / prod cards with gw-env, where prod shows the stricter defaults: read-only transactions, row limit 100, statement timeout 3 s, no DDL, approval timeout 10 min, a comment required to approve); and Advanced (a disclosure with an optional statement timeout — empty means the environment's one policy value —, pool size and a schema allowlist as tags). A Test connection panel follows with idle, testing, success (detected permissions, write grant flagged) and failure states.

MongoDB starts with Host and port (27017 by default). Connect using offers Connection string as an alternative, including SRV and multiple hosts; existing URI connections open in that mode. Connection type is a native select for Without TLS or TLS, with URI TLS settings still applying in connection string mode. User and Password stay in Credentials. Auth database and Mechanism belong to the initially collapsed Advanced section, alongside certificate, read preference and query settings. Switching address modes preserves entered values while submitting only the active mode's fields.

[SCSS](elements/_connection-form.scss) · [Interactive demo](elements/connection-form.html)

**Composes:**

- gw-form
- gw-field
- gw-input
- gw-select
- gw-input-group
- gw-menu-host / gw-menu
- gw-secret
- gw-choice-card
- gw-env
- gw-grid
- gw-alert
- gw-tag
- gw-tag-list
- gw-btn
- gw-spinner
- gw-card
- gw-status
- gw-kv
- gw-badge
- gw-code-block
- gw-code
- gw-icon

**States and behavior:**

- new connection, Encrypted here (gw-secret empty, reveal only while typing a new value)
- reference source, new (mono reference input + Test reference); Vault loads accessible paths and key names automatically into one autocomplete. Typing filters complete path#key suggestions, and choosing one inserts the reference. Click or ArrowDown opens suggestions; arrow keys move, Enter chooses, and Escape or Tab closes while focus stays in the input. Loading, empty, and partial-permission states stay beside the field; Refresh list retries discovery. Values are never shown and manual references remain available.
- secret reference, tested (gw-secret reference well, Vault KV, stage-db)
- stored password in the edit view (gw-secret stored: Encrypted · version 3, Replace / Rotate, no reveal)
- Rotate unavailable (a connector that can't set a value at the source, cache-redis): aria-disabled with the reason under the field
- replacing a stored password (gw-secret editing: Cancel / Save)
- environment dev / stage
- environment prod with the stricter defaults alert (Row limit 100 · Statement timeout 3 s · No DDL · Approval timeout 10 min, comment required)
- advanced collapsed / expanded
- test idle (Not tested yet)
- testing (aria-busy + aria-disabled button with spinner)
- test success with the write grant flagged (Can write badge + hint)
- test failure with details under a disclosure
- GCP or Google Cloud Logging test failure with local ADC selected: **Sign in with Google** runs the installed Google Cloud CLI in the desktop app; its explanation names the command and the shared local sign-in. While sign-in runs, both sign-in and test buttons stay focusable with `aria-disabled`, a spinner and an associated status. Successful sign-in tests the unchanged connection again. A missing CLI or failed sign-in keeps the form and shows an actionable error; browser-only mode explains the terminal command.
- validation error after submit
- duplicate (?duplicate=<name>): wizard from Configure, settings copied, name <name>-copy, credentials empty + info alert 'not copied'
- edit (?edit=<name>): health stat with sparkline, settings with gw-secret stored / reference, 'Used by' table (tool × endpoint × mode), Save changes ('Applies from the next call'), Delete connection (danger confirm)
- first run (connection-new ?state=first-run): Encrypted here preselected, Secret source behind 'Use a secret manager instead (Vault, GCP, AWS)' (__toggle placed before the fieldset), plain-words section descriptions

**Reference screens:**

- [pages/connection-new.html](pages/connection-new.html)
- [pages/onboarding.html](pages/onboarding.html)

### `connector-card`

One connector type in the catalog. It shows the type icon tile, name, kind badge (Built-in / Template / Plugin / MCP server), mono version, trust badge (Signed / Unsigned; built-ins carry one Built-in badge), tool count (the same on every screen: PostgreSQL 4, MySQL 4, MongoDB 2, Redis 2, OpenSearch 3, Elasticsearch 3, SSH 2, GCP Cloud Logging 2, AWS CloudWatch 2, Loki 2, Kubernetes 4, GCP 4, AWS 2, HTTP API 2), a one-line description and one action (Add connection / Install / Configure / Update). GCP Cloud Logging, AWS CloudWatch and Loki are separate built-in log connectors with provider-specific search and tail tools. OpenSearch and Elasticsearch are separate built-in database connectors with index discovery, JSON Query DSL and REST request tools. Categories group connectors for navigation; tool sets and descriptions belong to each connector. A plugin runs in its own plugin process. Install on an unsigned plugin and the review of an external MCP server confirm in gw-dialog--danger dialogs that name the code and the risk: it runs as your user in a separate process and gets only its connection's secrets during a call, and where there is no sandbox 'Not sandboxed on this system…'. The OpenSearch and Elasticsearch descriptions read 'Search indices with JSON Query DSL. Administration waits for approval.' The picker variant is gw-choice-card radio cards ('Built-in · 4 tools'), grouped by category in one radio group, for step 1 of Add connection.

[SCSS](elements/_connector-card.scss) · [Interactive demo](elements/connector-card.html)

**Composes:**

- gw-card
- gw-grid
- gw-icon-tile
- gw-icon
- gw-badge
- gw-status
- gw-btn
- gw-dialog
- gw-kv
- gw-choice-card
- gw-field
- gw-code

**States and behavior:**

- built-in, Add connection
- template, signed, Install
- plugin, signed, Install
- plugin, unsigned, Install (asks first: gw-dialog--sm --danger)
- MCP server, signed, Configure
- installed plugin, update available
- picker: checked
- picker: installed plugin with kind and trust badges
- picker: disabled (catalog plugin not installed, reason in the description)
- picker: nothing chosen (aria-invalid + error)
- MCP server review before the first start: gw-dialog--md --danger (command verbatim — for a catalog server the allowed argv the core runs, node …/dist/index.js after npm ci --ignore-scripts —, version pinned or not, runs as, secrets passed by name, tools Off everywhere and Approval on an endpoint, 'Declared by <server>, not verified'; required trust checkbox; Cancel autofocus)
- update available: gw-status --info 'Update available · v1.3.0' (Jira Service Desk; adds the read-only jira_request_sla)

**Reference screens:**

- [pages/connectors.html](pages/connectors.html)
- [pages/connection-new.html](pages/connection-new.html)

### `event-detail`

Everything about one logged call, in the order a developer asks about it. Summary: decision badge from the §7 vocabulary, tool on connection with env, ISO and relative time, duration, event id, and Copy link / Open trace / View raw. Then Request, Arguments (verbatim as the tool's inputSchema has them — sql_query takes limit, 50 by default, not max_rows, and no per-call timeout —, ‹masked›, counted), Policy decision (rules in evaluation order, the deciding rule marked — a mutation's rule is the core's <env>.<section>.write-approval —, the decision in words), Approval path (mutations only), Result (status, rows, size, SHA-256 copy field, masked fields, the structuredContent or isError preview), Timing (waterfall plus legend), Raw event disclosure, and one-click filters into Activity and Insights. Also the trace view: one agent task grouped into phases with call counts and decision badges.

[SCSS](elements/_event-detail.scss) · [Interactive demo](elements/event-detail.html)

**Composes:**

- gw-badge
- gw-code
- gw-env
- gw-kv
- gw-client-mark
- gw-btn
- gw-copy-field
- gw-code-block
- gw-tok-*
- gw-timeline
- gw-waterfall
- gw-legend
- gw-alert
- gw-divider
- gw-drawer
- gw-card
- gw-toast
- gw-icon
- gw-link

**States and behavior:**

- approved mutation: every section, the Approval timeline (requested, notified via desktop and Slack, approved by you with a comment and the channel, executed), 47 s hatched wait in Timing; named approvers only in server-mode previews
- inside a real gw-drawer--lg opened from a button ('Event 7c41e2d0', call 12 of 23 in trace 4f2a9c)
- allowed read (sql_query): no Approval section, 38 ms split between gateway and dev-db
- denied by policy (DROP TABLE on prod-replica through incident-prod): prod.sql.block_ddl marked as the matched rule, the unreached rule shows a dot marker and 'not checked', Duration 'Not run: denied in the gateway', Result 'Not executed' plus the isError text, gateway-only timing
- expired (http_request POST /v1/refunds on billing-api, stage): approval path ends in 'Expired, denied automatically', Duration '10 min waiting for approval, then expired', hatched wait, Not executed
- error (redis_get on cache-redis): gw-alert--danger under the summary (what happened, what to do, Open connection cache-redis), connector log, 2.0 s spent in the resource
- raw event collapsed / expanded (data-gw-toggle, two button faces)
- trace view: Fix failing order sync, 4f2a9c…, 23 calls, 18 min, phases Explore / Change / Wait for deploy / Verify with counts and decision badges, task name marked unverified
- token of a client served on the fly: the grant name only (codex-laptop 'issued on the fly', 'Bridge (.mcpb)'), no gwt_… prefix
- Copy link copies gaterook://activity/events/<id>; Open trace → activity.html?view=traces&q=trace:<id>

**Reference screens:**

- [pages/activity.html](pages/activity.html)
- [pages/approvals.html](pages/approvals.html)
- [pages/playground.html](pages/playground.html)

### `log-explorer`

The query and filter zone above the Activity events table, reused as the Insights filter bar. Row 1: a structured query input (placeholder 'Filter: decision:denied tool:sql_* env:prod client:codex') with a Query syntax popover, a time range menu (15 min / hour / 24 hours / 7 days / Custom…, absolute range in the trigger title), Live toggle, Saved views ('Save current view…' opens a small Name dialog) and Export (CSV, JSONL, Send to SIEM… — destinations by their Settings names, CEF unavailable because the core sends JSON lines). Row 2: Filters toggle, active filters as removable mono tags, Clear all, and a live result count. Facet column (Decision, Tool, AI app, Endpoint, Connection, Environment) with counts within the other filters and Show all. Interactive hourly histogram stacked by decision, using the showcase/64-charts series, with legend counts, click-to-zoom bars and an sr-only table. Empty result state.

[SCSS](elements/_log-explorer.scss) · [Interactive demo](elements/log-explorer.html)

**Composes:**

- gw-toolbar
- gw-search
- gw-menu-host
- gw-menu
- gw-popover
- gw-kv
- gw-code
- gw-btn
- gw-status
- gw-tag
- gw-tag-list
- gw-counter
- gw-field
- gw-checkbox
- gw-env
- gw-chart
- gw-legend
- gw-empty
- gw-card
- gw-dialog
- gw-input
- gw-icon

**States and behavior:**

- default: last 24 hours, no filters (a hint instead of tags), facets open, histogram, first table rows; 1,287 calls
- filters applied: decision:denied + env:prod checked in facets and mirrored as tags, Filters counter 2, facet counts within the other filters, filtered histogram and legend, '6 of 1,287 calls'
- zoomed into one hour: the 10:00 bar aria-pressed, trigger 'Today 10:00–11:00', count and legend for that hour, facets collapsed
- live: Live aria-pressed with a gw-status--brand --live dot, Last hour, rows 1 and 2 only
- insights variant (the Insights filter bar): rows 1 and 2 without Live, Saved views, Export and Custom…; ranges Last hour … Last 30 days
- no results, facets collapsed: gw-empty 'No calls match these filters' + Clear filters, '0 of 1,287 calls', main column full width
- show all values: __facet-more disclosure with 'Show all 10' / 'Show fewer' faces (Tool, Connection)
- custom range dialog (gw-dialog--sm, From / To datetime-local, Apply range), opened from 'Custom…' in every range menu
- the query field carries aria-keyshortcuts="/": the shared kit.js hook focuses it (not while typing in a field; off with data-gw-single-keys="off")
- histogram data for screen readers: <div class="gw-sr-only"><table>…</table></div> (never class=gw-sr-only on the table)

**Reference screens:**

- [pages/activity.html](pages/activity.html)
- [pages/insights.html](pages/insights.html)

### `settings-layout`

Grouped settings. On the left, a sticky settings nav: gw-nav with groups Workspace, Security, Approvals, Network, Data and Project (role=group + aria-labelledby), aria-current on the item in view. On the right, one section per item (h2 + description, a divider, then a gw-form of topics or read-only project information). Topics that apply at once sit outside any form and confirm with a toast. Topics with values to save are <form class=gw-form__section id> that own the item's sticky Save bar via form="id", shown only while unsaved. Below lg the nav becomes a native 'Jump to section' select with an optgroup per group.

[SCSS](elements/_settings-layout.scss) · [Interactive demo](elements/settings-layout.html)

**Composes:**

- gw-nav
- gw-section
- gw-divider
- gw-stack
- gw-form
- gw-field
- gw-select
- gw-input
- gw-segmented
- gw-radio
- gw-switch
- gw-grid
- gw-choice-card
- gw-badge
- gw-copy-field
- gw-secret
- gw-alert
- gw-status
- gw-kbd
- gw-btn
- gw-icon
- gw-toast

**States and behavior:**

- current item: aria-current="page" on the nav link of the section in view (scroll-spy, /settings/<item> in the URL); the jump select shows the same item
- unsaved: gw-form__actions visible with 'Unsaved changes' (role=status), Discard (type=reset) + Save <object> (type=submit) via form="<id>"; Discard restores the saved values; hidden otherwise
- applies at once: switch / select / theme topics outside any <form>, no Save bar, confirmed by a success toast naming the setting and its value
- read-only: About ends Settings with the project author, website, GitHub repository link, license link and third-party notice; no Save bar
- unavailable: server-mode-only topic (Users & roles, UI sign-in, token IP allowlist) with aria-disabled controls + aria-describedby to a gw-alert--neutral --compact (circle-help) that says why and what would enable it
- narrow (< lg / 1280px): nav hidden, 'Jump to section' select (optgroups per group, max 320 wide) above the sections; changing it scrolls, focus stays on the select
- anchors #settings-<section>: general, appearance, authorization, users, notifications, approval-rules, tunnel, tunnel-callers, local-api, audit, backup, about
- demo values (BLUEPRINT §9): default token lifetime 6 months (select 1 / 7 / 30 / 90 / 184 days); issuer 'None yet' while the quick tunnel is off; Notifications — desktop on, Slack connected (workspace acme, DMs recommended, #deploy-approvals selected, last delivery 14:02:30), Telegram not set up; Slack and Telegram never offer Approve for prod; Approval rules — 'Require a comment on prod' on; Tunnel › Callers — the one Anthropic IP allowlist switch (on); Audit — retention 1 / 2 / 3 / 5 years, default 1 year

**Reference screens:**

- [pages/settings.html](pages/settings.html)
- [pages/endpoint-detail.html](pages/endpoint-detail.html)

### `status-screen`

Stands in for the whole window while the gateway core is unavailable: fixed, z status-screen, on the window canvas, no gw-shell. Before OS-protected startup, explains why the encryption key is needed and waits for Unlock GateRook before requesting OS access. Shows the logo, then a card with the title, the reason, the start-up steps of the native core (Native core, Master key, Database migrations, Local API) and the one or two actions that fix it, then version / address / log path under it. A drag strip keeps the frameless window movable. --contained puts it in page flow inside a gw-card.gw-status-screen__frame preview.

[SCSS](elements/_status-screen.scss) · [Interactive demo](elements/status-screen.html)

**Composes:**

- gw-logo
- gw-card
- gw-icon-tile
- gw-progress
- gw-stepper
- gw-spinner
- gw-alert
- gw-code-block
- gw-code
- gw-kv
- gw-stack
- gw-btn
- gw-dialog
- gw-icon

**States and behavior:**

- unlock-required, waiting: neutral lock-keyhole tile; 'Unlock GateRook' title; explain that the operating system protects the encryption key for saved credentials. The desktop's optional `authentication` metadata selects the explanation: `touch-id` offers Touch ID or the Mac password; `windows-hello` offers the configured face, fingerprint, or PIN through Windows Hello; `system` or an absent value explains the possible OS access or computer-password prompt. Authentication happens only in the system dialog after the action; the renderer never receives passwords or biometric data. Denial or cancellation closes GateRook without starting the core. When `keychainService` is supplied for legacy macOS access, gw-alert--info --compact explains that Safe Storage entry name; omit the alert for native authentication. Footer Quit + Unlock GateRook (primary); no password field, metadata line, or core progress steps
- unlock-required, unlocking: keep the Unlock GateRook label with gw-spinner--sm, aria-busy and aria-disabled; describe the busy button with visible role=status text naming the available system method: 'Complete Touch ID or enter your Mac password in the system dialog to continue.', 'Complete Windows Hello in the system dialog to continue.', or 'Complete the request from your operating system to continue.'; prevent duplicate requests
- starting: 'Starting the native core and the local API on 127.0.0.1:7465. This usually takes about a second.'; gw-progress--indeterminate named by the title; stepper Native core ('Native image · 0.1 s'), Master key and Database migrations complete + Local API current (gw-spinner--sm in the marker); sr-only role=status line updated per step
- failed, port in use: danger tile; Local API step data-state=error with the reason; the text says what another port costs (endpoint URLs change; clients with the URL in their config, such as Codex and Cursor, reconnect); open disclosure with the lsof command (copy) + netstat for Windows; footer Quit + Use another port (primary)
- failed, master key not found: error on the Master key step; Database migrations and Local API complete (the locked core has migrated and listens); gw-alert--danger (circle-alert) says a new key makes the 8 secrets encrypted here unreadable (6 connections, github-mcp, the Vault sign-in) while the 4 references keep working (12 in all, as in Secrets), with a danger-ghost 'Create a new key' that opens gw-dialog--danger (Cancel autofocus, confirm 'Create a new master key'); footer Quit + Restore master key (primary)
- failed, didn't start (any other ready:false reason, e.g. DB_LOCKED 'Another core is using the database'): lock tile, the stopped card's facts (exit code 2, reason in mono, log file), last log lines under a disclosure, stepper only when the core reached a step; footer Open logs + Restart core (primary)
- stopped unexpectedly: gw-kv --dense facts (exit code, reason, uptime, log file in mono); last log lines under a closed disclosure; pending approval requests are kept; footer Open logs + Restart core (primary)
- full recovery: the desktop can project a Restore full backup danger-ghost action into locked, failed and stopped footers. It opens the shared encrypted-backup dialog, requires the backup passphrase and exact RESTORE confirmation, and initially focuses Cancel. This action is absent during startup and before OS unlock. The Angular composition accepts this app-owned action through its `[fullRecovery]` content slot.
- --contained: in flow, fills a gw-card.gw-status-screen__frame (demos, reviews)

**Reference screens:**

- [pages/core-starting.html](pages/core-starting.html)

### `command-palette`

⌘K / Ctrl+K entry point to the whole app. A native modal gw-dialog anchored near the top, with a gw-search combobox and a grouped listbox built from gw-menu item parts. Go to lists every page with its sidebar group as the hint; Actions use verb + object; Connections show name + gw-env; Endpoints show slug + /mcp path. Up / Down move the active option, Enter opens it, Esc closes. --preview renders the same panel in page flow for docs.

[SCSS](elements/_command-palette.scss) · [Interactive demo](elements/command-palette.html)

**Composes:**

- gw-dialog
- gw-search
- gw-menu
- gw-env
- gw-counter
- gw-empty
- gw-kbd
- gw-btn
- gw-icon

**States and behavior:**

- suggestions: empty query, every group, first option active
- filtered: options matching label + hint + data-keywords, empty groups hidden, first match active, status line 'N results'
- active option: aria-selected="true" + gw-menu__item--active on the option, aria-activedescendant on the input (wash + inset ring, the trailing corner-down-left __check as a second cue); Up / Down wrap, Home / End, pointer movement moves it, Enter clicks it
- no results: listbox hidden, aria-expanded="false", gw-empty--compact repeats the query and offers 'Add connection'
- closed: Esc (always, even with a query) or ⌘K again; closing resets the query and the active option; focus returns to the opener
- --preview: static panel in page flow (div role=group), never in the app
- behaviour from kit.js (palette hook): ⌘K / Ctrl+K toggles <dialog id="cmdk"> from anywhere unless another modal is open; typing filters by text + data-keywords, hides empty groups, writes the count into [data-cmdk-status] and shows [data-cmdk-empty] with [data-cmdk-query]; Esc always closes and focus returns

**Reference screens:**

- [pages/_shell.html](pages/_shell.html)
- [pages/overview.html](pages/overview.html)
- [pages/approvals.html](pages/approvals.html)
- [pages/connections.html](pages/connections.html)
- [pages/connection-new.html](pages/connection-new.html)
- [pages/connectors.html](pages/connectors.html)
- [pages/secrets.html](pages/secrets.html)
- [pages/endpoints.html](pages/endpoints.html)
- [pages/endpoint-detail.html](pages/endpoint-detail.html)
- [pages/clients.html](pages/clients.html)
- [pages/policies.html](pages/policies.html)
- [pages/playground.html](pages/playground.html)
- [pages/activity.html](pages/activity.html)
- [pages/insights.html](pages/insights.html)
- [pages/settings.html](pages/settings.html)

### `status-tile`

One compact card that names one thing (a connection, an AI app) and says its state in words, with at most one action (Connect, Test again, How to install). For pages that answer 'what is connected and is it working' at a glance; sets of tiles sit in a gw-grid --3 list (--2 for the fixed set of four AI apps), problems first. The title links to the thing's page; when the action is the tile's only destination the title is plain text and the tile is not interactive.

AI app tiles show their endpoint slugs below the metadata as neutral small badges with monospaced labels in a wrapping `gw-tag-list`. Use `gw-badge--wrap` to keep long slugs fully visible within narrow tiles. Show each connected or restart-pending endpoint once. Exclude failed and revoked links, and omit the list when no endpoints remain. Name the list `Connected endpoints for <app>`; badges are display only and retain the tile's existing navigation.

[SCSS](elements/_status-tile.scss) · [Interactive demo](elements/status-tile.html)

**Composes:**

- gw-card
- gw-icon-tile
- gw-client-mark
- gw-env
- gw-status
- gw-btn
- gw-spinner
- gw-mono
- gw-grid
- gw-badge
- gw-tag-list

**States and behavior:**

- healthy / connected (success)
- slow (warning)
- unreachable (danger) + Test again
- busy: Test again aria-busy + aria-disabled, state gw-status--checking (role=status)
- found, not connected (info) + Connect
- not installed (neutral) + How to install (gw-btn--link)
- action unavailable: <button aria-disabled> + aria-describedby to a visible reason (never a link)
- narrow tile (content box < 280px, about 314px outer): state and action under the text on one row

**Reference screens:**

- [pages/overview.html](pages/overview.html)

## Markup examples

These examples retain the original component and composition structures. Ellipses indicate omitted surrounding markup, not a complete production screen. Use the corresponding API entry above and the live demo for state behavior.

### Example 1 · gw-btn

```html
<button class="gw-btn gw-btn--primary" type="button"><i class="gw-icon" data-lucide="plus"></i> Create endpoint</button>
<button class="gw-btn gw-btn--icon gw-btn--ghost" type="button" aria-label="Copy endpoint URL"><i class="gw-icon" data-lucide="copy"></i></button>
```

### Example 2 · gw-shell / gw-titlebar / gw-sidebar / gw-nav

```html
<a class="gw-skip-link" href="#main">Skip to content</a>
<div class="gw-shell">
  <header class="gw-titlebar">
    <div class="gw-titlebar__drag">
      <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" title="No previous page" disabled><i class="gw-icon" data-lucide="arrow-left"></i> Back</button>
    </div>
    <span class="gw-titlebar__title">Overview</span>
    <div class="gw-titlebar__actions">…Search ⌘K, approvals bell, appearance popover…</div>
  </header>
  <div class="gw-sidebar" id="gw-sidebar">
    <div class="gw-sidebar__header">
      <a class="gw-logo" href="overview.html" aria-label="GateRook, overview">…</a>
      <button class="gw-btn gw-btn--ghost gw-btn--sm gw-btn--icon gw-sidebar__toggle" type="button"
              data-gw-sidebar-toggle aria-controls="gw-sidebar" aria-expanded="true"
              aria-label="Collapse sidebar"><i class="gw-icon" data-lucide="panel-left"></i></button>
    </div>
    <nav class="gw-sidebar__body gw-nav" data-gw-nav aria-label="Main">
      <div class="gw-nav__group">
        <ul class="gw-nav__list">
          <li><a class="gw-nav__item" href="overview.html" aria-current="page"><i class="gw-icon gw-nav__icon" data-lucide="layout-dashboard"></i><span class="gw-nav__label">Overview</span></a></li>
          <li><a class="gw-nav__item" href="approvals.html"><i class="gw-icon gw-nav__icon" data-lucide="user-check"></i><span class="gw-nav__label">Approvals</span><span class="gw-nav__counter gw-counter gw-counter--attention"><span class="gw-sr-only">, </span>3<span class="gw-sr-only"> waiting</span></span></a></li>
        </ul>
      </div>
      <div class="gw-nav__group" role="group" aria-labelledby="gw-nav-resources">
        <p class="gw-nav__group-label" id="gw-nav-resources">Resources</p>
        <ul class="gw-nav__list">…</ul>
      </div>
      …Access, Observe…
      <div class="gw-nav__group gw-nav__group--end"><ul class="gw-nav__list">…Settings…</ul></div>
    </nav>
    <div class="gw-sidebar__footer">
      <span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Core running</span></span>
      <span class="gw-sidebar__meta">127.0.0.1:7465</span>
      <span class="gw-status gw-status--neutral"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Tunnel off</span></span>
      <span class="gw-sidebar__meta">v0.4.0</span>
    </div>
  </div>
  <main class="gw-shell__main" id="main" tabindex="-1">
    <!-- app-wide gw-alert--banner here -->
    <div class="gw-page">…</div>
  </main>
</div>
```

### Example 3 · gw-page

```html
<div class="gw-page gw-page--full">
  <header class="gw-page-header">…</header>
  <div class="gw-split">…</div>
</div>
```

### Example 4 · gw-page-header / gw-breadcrumb

```html
<header class="gw-page-header">
  <nav class="gw-breadcrumb gw-page-header__breadcrumb" aria-label="Breadcrumb">
    <ol>
      <li><a href="endpoints.html">Endpoints</a></li>
      <li><i class="gw-icon" data-lucide="chevron-right"></i><span class="gw-mono" aria-current="page">dev</span></li>
    </ol>
  </nav>
  <div class="gw-page-header__row">
    <div class="gw-page-header__heading">
      <h1 class="gw-page-header__title">Endpoint <span class="gw-mono">dev</span></h1>
      <p class="gw-page-header__description">Local development: 8 connections, mutations go through approval.</p>
      <div class="gw-page-header__meta">…gw-status, gw-divider--vertical, <code class="gw-code">http://127.0.0.1:7465/mcp/dev</code>…</div>
    </div>
    <div class="gw-page-header__actions">
      <button class="gw-btn" type="button">Open in Playground</button>
      <button class="gw-btn gw-btn--primary" type="button">Create token</button>
    </div>
  </div>
  <div class="gw-page-header__tabs gw-tabs"><div class="gw-tabs__list" role="tablist" aria-label="Endpoint dev">…</div></div>
</header>
```

### Example 5 · gw-section

```html
<section class="gw-section" aria-labelledby="health">
  <header class="gw-section__header">
    <h2 class="gw-section__title" id="health">Connection health</h2>
    <p class="gw-section__description">Checked every minute.</p>
    <div class="gw-section__actions"><button class="gw-btn gw-btn--sm" type="button">Check all</button></div>
  </header>
  <div class="gw-grid gw-grid--3">…</div>
</section>
```

### Example 6 · gw-toolbar

```html
<div class="gw-toolbar">
  <div class="gw-toolbar__start">…gw-search, filters…</div>
  <div class="gw-toolbar__end"><button class="gw-btn gw-btn--primary gw-btn--sm" type="button">Add connection</button></div>
</div>
```

### Example 7 · gw-split

```html
<div class="gw-split gw-split--40" style="--gw-split-height: 480px">
  <div class="gw-split__list">…</div>
  <div class="gw-split__detail">…</div>
</div>
```

### Example 8 · gw-kbd

```html
Press <kbd class="gw-kbd">⌘</kbd><kbd class="gw-kbd">K</kbd> to search.
<kbd class="gw-kbd"><i class="gw-icon" data-lucide="corner-down-left"></i><span class="gw-sr-only">Enter</span></kbd>
```

### Example 9 · gw-avatar / gw-client-mark

```html
<span class="gw-avatar" aria-hidden="true">PN</span> Priya Nair
<span class="gw-avatar" role="img" aria-label="Alex Morgan">AM</span>
<span class="gw-client-mark gw-client-mark--codex" aria-hidden="true">Cx</span> Codex
<span class="gw-client-mark gw-client-mark--generic gw-client-mark--lg" role="img" aria-label="Other app"><i class="gw-icon" data-lucide="app-window"></i></span>
```

### Example 10 · gw-badge / gw-counter

```html
<span class="gw-badge gw-badge--warning">
  <i class="gw-icon gw-badge__icon" data-lucide="hourglass"></i>Waiting for approval
</span>
```

### Example 11 · gw-badge / gw-counter

```html
<a href="approvals.html">Approvals <span class="gw-counter gw-counter--attention">3<span class="gw-sr-only"> waiting</span></span></a>
```

### Example 12 · gw-status

```html
<span class="gw-status gw-status--success">
  <span class="gw-status__dot" aria-hidden="true"></span>
  <span class="gw-status__label">Healthy · 12 ms</span>
</span>
<span class="gw-status gw-status--checking" role="status">
  <span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>
  <span class="gw-status__label">Checking connection</span>
</span>
```

### Example 13 · gw-env

```html
<code class="gw-code">prod-replica</code> <span class="gw-sr-only">environment</span> <span class="gw-env gw-env--prod">prod</span>
```

### Example 14 · gw-mode

```html
<span class="gw-mode gw-mode--approval"><i class="gw-icon" data-lucide="user-check"></i>Approval</span>
```

### Example 15 · gw-tag / gw-tag-list

```html
<ul class="gw-tag-list" aria-label="Allowed hosts for dev-vm">
  <li class="gw-tag gw-tag--mono">
    <span class="gw-tag__label">db.dev.internal</span>
    <button class="gw-tag__remove" type="button" aria-label="Remove db.dev.internal"><i class="gw-icon" data-lucide="x"></i></button>
  </li>
</ul>
```

### Example 16 · gw-kv

```html
<dl class="gw-kv">
  <div class="gw-kv__row">
    <dt class="gw-kv__key">Host</dt>
    <dd class="gw-kv__value gw-mono">db.dev.internal:5432/app</dd>
  </div>
</dl>
```

### Example 17 · gw-stat

```html
<div class="gw-stat">
  <dt class="gw-stat__label">Gateway overhead p95</dt>
  <dd class="gw-stat__value">31 <span class="gw-stat__unit">ms</span></dd>
  <dd class="gw-stat__delta gw-stat__delta--down" data-sentiment="good"><i class="gw-icon" data-lucide="trending-down"></i>−4 ms vs yesterday</dd>
  <dd class="gw-stat__meta">Target ≤ 50 ms · within target</dd>
</div>
```

### Example 18 · gw-progress / gw-meter

```html
<p class="gw-text-small" id="up">Uploading orders-sync-plugin-1.2.0.jar · 42%</p>
<div class="gw-progress" role="progressbar" aria-labelledby="up" aria-valuemin="0" aria-valuemax="100" aria-valuenow="42" style="--gw-progress: 0.42">
  <div class="gw-progress__bar"></div>
</div>
```

### Example 19 · gw-progress / gw-meter

```html
<div class="gw-meter" role="meter" aria-labelledby="rl" aria-valuemin="0" aria-valuemax="1000" aria-valuenow="812"
     aria-valuetext="812 of 1,000 calls per hour, near the limit" data-level="warn" style="--gw-meter: 0.812">
  <span class="gw-meter__label" id="rl">Rate limit · endpoint dev</span>
  <span class="gw-meter__value"><i class="gw-icon" data-lucide="triangle-alert"></i>812 / 1,000 calls per hour</span>
  <span class="gw-meter__track"><span class="gw-meter__fill"></span></span>
</div>
```

### Example 20 · gw-empty

```html
<div class="gw-empty">
  <span class="gw-empty__icon"><i class="gw-icon" data-lucide="database"></i></span>
  <h2 class="gw-empty__title">No connections yet</h2>
  <p class="gw-empty__text">Connect a database, server, log store or cluster. Agents reach it through an endpoint and never see its secrets.</p>
  <div class="gw-empty__actions"><a class="gw-btn gw-btn--primary" href="connection-new.html">Add connection</a></div>
</div>
```

### Example 21 · gw-field

```html
<div class="gw-field">
  <label class="gw-field__label" for="host">Host</label>
  <input class="gw-input gw-input--mono" id="host" value="db.dev.internal" required aria-describedby="host-hint">
  <p class="gw-field__hint" id="host-hint">Hostname or IP the core can reach.</p>
</div>

<div class="gw-field">
  <label class="gw-field__label" for="db">Database</label>
  <input class="gw-input gw-input--mono" id="db" required aria-invalid="true" aria-describedby="db-err">
  <p class="gw-field__error" id="db-err"><i class="gw-icon" data-lucide="circle-alert"></i>Enter a database name. The core opens it for every call.</p>
</div>
```

### Example 22 · gw-input / gw-textarea / gw-select

```html
<input class="gw-input gw-input--mono" id="port" value="5432" inputmode="numeric">
<textarea class="gw-textarea gw-textarea--code" id="sql" rows="5" spellcheck="false" autocapitalize="off">SELECT id, status FROM orders LIMIT :limit</textarea>
<select class="gw-select" id="ssl"><option value="verify-full" selected>Verify CA and host name</option></select>
```

### Example 23 · gw-input-group

```html
<div class="gw-input-group">
  <span class="gw-input-group__affix">https://</span>
  <input class="gw-input gw-input--mono" id="base-url" value="billing.stage.acme.dev">
  <button class="gw-input-group__addon" type="button" aria-label="Copy base URL" data-gw-copy="https://billing.stage.acme.dev">
    <i class="gw-icon" data-lucide="copy"></i><i class="gw-icon" data-lucide="check"></i>
  </button>
</div>
```

### Example 24 · gw-checkbox / gw-radio / gw-switch

```html
<label class="gw-checkbox">
  <input class="gw-checkbox__input" type="checkbox" checked>
  <span class="gw-checkbox__control" aria-hidden="true"></span>
  <span class="gw-checkbox__label">Mask secrets in results</span>
</label>

<!-- With a hint: keep the hint outside the accessible name. -->
<div class="gw-switch">
  <input class="gw-switch__input" type="checkbox" role="switch" id="desktop" checked aria-describedby="desktop-hint">
  <span class="gw-switch__control" aria-hidden="true"></span>
  <label class="gw-switch__label" for="desktop">Desktop notifications</label>
  <p class="gw-switch__hint" id="desktop-hint">A system notification with Review and Deny for every request.</p>
</div>
```

### Example 25 · gw-segmented

```html
<fieldset class="gw-segmented" aria-describedby="exec-why">
  <legend class="gw-sr-only">Mode for sql_execute</legend>
  <label class="gw-segmented__option" data-tone="off">
    <input class="gw-segmented__input" type="radio" name="mode-sql_execute" value="off">
    <span class="gw-segmented__label"><i class="gw-icon gw-icon--sm" data-lucide="circle-off"></i>Off</span>
  </label>
  <label class="gw-segmented__option" data-tone="read">
    <input class="gw-segmented__input" type="radio" name="mode-sql_execute" value="read" disabled aria-describedby="exec-why">
    <span class="gw-segmented__label"><i class="gw-icon gw-icon--sm" data-lucide="eye"></i>Read only</span>
  </label>
  <label class="gw-segmented__option" data-tone="approval">
    <input class="gw-segmented__input" type="radio" name="mode-sql_execute" value="approval" checked>
    <span class="gw-segmented__label"><i class="gw-icon gw-icon--sm" data-lucide="user-check"></i>Approval</span>
  </label>
</fieldset>
<p class="gw-field__hint" id="exec-why">Read only is unavailable: sql_execute changes data, so it runs only after an approval.</p>
```

### Example 26 · gw-secret

```html
<div class="gw-field">
  <span class="gw-field__label" id="pw-l">Password</span>
  <div class="gw-secret" data-state="stored" role="group" aria-labelledby="pw-l">
    <span class="gw-secret__status"><i class="gw-icon" data-lucide="lock-keyhole"></i>Encrypted</span>
    <span class="gw-secret__meta">version <span class="gw-mono">3</span> · updated <time datetime="2026-10-20T09:14:00Z" title="2026-10-20 09:14 UTC">2 days ago</time></span>
    <span class="gw-secret__actions">
      <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" aria-label="Replace password for dev-db">Replace</button>
      <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" aria-label="Rotate password for dev-db">Rotate</button>
    </span>
  </div>
</div>
```

### Example 27 · gw-choice-card

```html
<fieldset class="gw-field">
  <legend class="gw-field__label">Secret source</legend>
  <div class="gw-grid gw-grid--3">
    <label class="gw-choice-card">
      <input class="gw-choice-card__input" type="radio" name="src" value="vault" aria-labelledby="src-vault-t" aria-describedby="src-vault-d">
      <span class="gw-choice-card__icon"><i class="gw-icon" data-lucide="vault"></i></span>
      <span class="gw-choice-card__title" id="src-vault-t">Vault</span>
      <span class="gw-choice-card__description" id="src-vault-d">Resolve a vault:// reference at call time.</span>
      <span class="gw-choice-card__meta">KV v2</span>
    </label>
  </div>
</fieldset>
```

### Example 28 · gw-search

```html
<div class="gw-search">
  <i class="gw-icon gw-search__icon" data-lucide="search"></i>
  <input class="gw-search__input" type="search" placeholder="Search connections" aria-label="Search connections" aria-keyshortcuts="/">
  <button class="gw-search__clear" type="button" aria-label="Clear search"><i class="gw-icon gw-icon--sm" data-lucide="x"></i></button>
  <kbd class="gw-search__kbd" aria-hidden="true">/</kbd>
</div>
```

### Example 29 · gw-file-drop

```html
<label class="gw-file-drop" data-state="idle">
  <input class="gw-file-drop__input" type="file" accept=".jar" aria-describedby="jar-hint">
  <span class="gw-file-drop__icon"><i class="gw-icon gw-icon--lg" data-lucide="upload"></i></span>
  <span class="gw-file-drop__title">Drop a plugin JAR here or <span class="gw-fg-link">choose a file</span></span>
  <span class="gw-file-drop__hint" id="jar-hint">PF4J plugin, signed, up to 50 MB</span>
</label>
```

### Example 30 · gw-form

```html
<form class="gw-form gw-form--sticky-actions" aria-label="Edit connection dev-db">
  <fieldset class="gw-form__section">
    <legend class="gw-form__legend">Connection</legend>
    <p class="gw-form__description">Where the core reaches the database.</p>
    <div class="gw-form__rows">
      <div class="gw-form__row"><!-- Host · Port · Database: gw-field × 3 --></div>
      <div class="gw-field"><!-- SSL mode --></div>
    </div>
  </fieldset>
  <div class="gw-form__actions">
    <p class="gw-form__note" role="status"><i class="gw-icon" data-lucide="circle-dot"></i>Unsaved changes</p>
    <button class="gw-btn" type="reset">Discard</button>
    <button class="gw-btn gw-btn--primary" type="submit">Save connection</button>
  </div>
</form>
```

### Example 31 · gw-tabs

```html
<div class="gw-tabs">
  <div class="gw-tabs__list" role="tablist" aria-label="Endpoint dev">
    <button class="gw-tabs__tab" type="button" role="tab" id="t-tools"
            aria-selected="true" aria-controls="p-tools">
      Tools &amp; policies <span class="gw-tabs__count">14<span class="gw-sr-only"> tools</span></span>
    </button>
    <button class="gw-tabs__tab" type="button" role="tab" id="t-tokens"
            aria-selected="false" aria-controls="p-tokens" tabindex="-1">Tokens</button>
  </div>
  <div class="gw-tabs__panel" role="tabpanel" id="p-tools" aria-labelledby="t-tools" tabindex="0">…</div>
  <div class="gw-tabs__panel" role="tabpanel" id="p-tokens" aria-labelledby="t-tokens" tabindex="0" hidden>…</div>
</div>
```

### Example 32 · gw-menu-host / gw-menu / gw-popover

```html
<div class="gw-menu-host">
  <button class="gw-btn gw-btn--ghost gw-btn--icon" type="button"
          aria-label="Actions for endpoint dev" aria-haspopup="menu"
          aria-expanded="false" aria-controls="m-dev">
    <i class="gw-icon" data-lucide="ellipsis"></i>
  </button>
  <div class="gw-menu gw-menu--right" id="m-dev" role="menu" aria-label="Actions for endpoint dev" hidden>
    <button class="gw-menu__item" type="button" role="menuitem" tabindex="-1" data-gw-copy="http://127.0.0.1:7465/mcp/dev">
      <i class="gw-icon gw-menu__icon" data-lucide="copy"></i>
      <span class="gw-menu__label">Copy URL</span>
      <kbd class="gw-menu__kbd" aria-hidden="true">C</kbd>
    </button>
    <div class="gw-menu__divider" role="separator"></div>
    <button class="gw-menu__item gw-menu__item--danger" type="button" role="menuitem" tabindex="-1"
            data-gw-dialog-open="dlg-delete-endpoint">
      <i class="gw-icon gw-menu__icon" data-lucide="trash-2"></i>
      <span class="gw-menu__label">Delete endpoint</span>
    </button>
  </div>
</div>
```

### Example 33 · gw-menu-host / gw-menu / gw-popover

```html
<!-- listbox: the trigger shows the picked value -->
<button class="gw-btn gw-btn--sm" type="button" aria-haspopup="listbox" aria-expanded="false" aria-controls="m-sort">
  Sort: <span data-gw-menu-value>Newest first</span> <i class="gw-icon gw-icon--sm" data-lucide="chevron-down"></i>
</button>
<div class="gw-menu" id="m-sort" role="listbox" aria-label="Sort events" hidden>
  <button class="gw-menu__item" type="button" role="option" aria-selected="true" tabindex="-1">
    <i class="gw-icon gw-menu__check" data-lucide="check"></i><span class="gw-menu__label">Newest first</span>
  </button>
</div>
```

### Example 34 · gw-tooltip

```html
<!-- the tooltip IS the name -->
<span class="gw-tooltip">
  <button class="gw-btn gw-btn--ghost gw-btn--icon" type="button" aria-labelledby="tip-copy">
    <i class="gw-icon" data-lucide="copy"></i>
  </button>
  <span class="gw-tooltip__bubble" role="tooltip" id="tip-copy">Copy endpoint URL</span>
</span>

<!-- the tooltip ADDS information -->
<span class="gw-tooltip gw-tooltip--bottom">
  <time datetime="2026-10-22T14:07:31+02:00" tabindex="0" aria-describedby="tip-t">3 min ago</time>
  <span class="gw-tooltip__bubble" role="tooltip" id="tip-t">2026-10-22 14:07:31 CEST</span>
</span>
```

### Example 35 · gw-dialog / gw-drawer

```html
<button class="gw-btn gw-btn--danger-ghost" type="button" data-gw-dialog-open="dlg-revoke">Revoke token</button>
<dialog class="gw-dialog gw-dialog--sm gw-dialog--danger" id="dlg-revoke"
        aria-labelledby="dlg-revoke-title" aria-describedby="dlg-revoke-desc">
  <header class="gw-dialog__header">
    <span class="gw-icon-tile"><i class="gw-icon" data-lucide="key-round"></i></span>
    <h2 class="gw-dialog__title" id="dlg-revoke-title">Revoke token inspector-debug?</h2>
    <p class="gw-dialog__description" id="dlg-revoke-desc">MCP Inspector loses access to endpoint dev on its next call.</p>
    <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button"
            aria-label="Close" data-gw-dialog-close><i class="gw-icon" data-lucide="x"></i></button>
  </header>
  <div class="gw-dialog__body">…</div>
  <footer class="gw-dialog__footer">
    <button class="gw-btn" type="button" data-gw-dialog-close autofocus>Cancel</button>
    <button class="gw-btn gw-btn--danger" type="button">Revoke token inspector-debug</button>
  </footer>
</dialog>
```

### Example 36 · gw-toast-region / gw-toast

```html
<section class="gw-toast-region" aria-label="Notifications">
  <div class="gw-toast gw-toast--success" role="status">
    <i class="gw-icon gw-toast__icon" data-lucide="circle-check"></i>
    <div class="gw-toast__content">
      <p class="gw-toast__title">Token inspector-debug revoked</p>
      <p class="gw-toast__body">MCP Inspector loses access on its next call.</p>
      <div class="gw-toast__actions"><button class="gw-btn gw-btn--sm" type="button">Undo</button></div>
    </div>
    <button class="gw-btn gw-btn--ghost gw-btn--sm gw-btn--icon gw-toast__close" type="button"
            aria-label="Dismiss notification"><i class="gw-icon gw-icon--sm" data-lucide="x"></i></button>
  </div>
</section>
```

### Example 37 · gw-toast-region / gw-toast

```js
window.gwToast({ title: "dev-db connected", body: "Healthy · 12 ms", tone: "success" });
```

### Example 38 · gw-alert

```html
<div class="gw-alert gw-alert--warning">
  <i class="gw-icon gw-alert__icon" data-lucide="triangle-alert"></i>
  <div class="gw-alert__content">
    <p class="gw-alert__title">This user can write</p>
    <p class="gw-alert__body">app_rw has INSERT and UPDATE on 14 tables. Use a read-only user for prod.</p>
    <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button">Use another user</button></div>
  </div>
</div>

<div class="gw-alert gw-alert--danger gw-alert--banner" role="alert">
  <i class="gw-icon gw-alert__icon" data-lucide="server-off"></i>
  <div class="gw-alert__content">
    <p class="gw-alert__title">Core stopped</p>
    <p class="gw-alert__body">Agents get no answer from 127.0.0.1:7465. Pending approvals are kept.</p>
  </div>
  <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button">Start core</button></div>
</div>
```

### Example 39 · gw-stepper

```html
<ol class="gw-stepper" aria-label="Add connection">
  <li class="gw-stepper__step" data-state="complete">
    <span class="gw-stepper__marker" aria-hidden="true"><i class="gw-icon gw-icon--sm" data-lucide="check"></i></span>
    <span class="gw-stepper__label">Choose type<span class="gw-sr-only">, completed</span></span>
    <span class="gw-stepper__description">PostgreSQL</span>
  </li>
  <li class="gw-stepper__step" data-state="current" aria-current="step">
    <span class="gw-stepper__marker" aria-hidden="true">2</span>
    <span class="gw-stepper__label">Configure</span>
  </li>
  <li class="gw-stepper__step" data-state="upcoming">
    <span class="gw-stepper__marker" aria-hidden="true">3</span>
    <span class="gw-stepper__label">Test</span>
  </li>
</ol>
```

### Example 40 · gw-stepper

```html
<ol class="gw-stepper gw-stepper--guide" aria-label="Start in two steps">
  <li class="gw-stepper__step" data-state="current" aria-current="step">
    <span class="gw-stepper__marker" aria-hidden="true">1</span>
    <h3 class="gw-stepper__label">Add your first connection</h3>
    <p class="gw-stepper__description">A connection is something your AI can work with…</p>
    <div class="gw-stepper__content gw-stack gw-stack--sm">
      <div><a class="gw-btn gw-btn--primary" href="connection-new.html">Add connection</a></div>
    </div>
  </li>
  <li class="gw-stepper__step" data-state="upcoming">…</li>
</ol>
```

### Example 41 · gw-paginator

```html
<!-- Compact: tables sorted / filtered on the server -->
<nav class="gw-paginator" aria-label="Events pages">
  <p class="gw-paginator__info">1–50 of 1,284 events</p>
  <div class="gw-paginator__controls">
    <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm" type="button" aria-label="Previous page" disabled><i class="gw-icon" data-lucide="chevron-left"></i></button>
    <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm" type="button" aria-label="Next page"><i class="gw-icon" data-lucide="chevron-right"></i></button>
  </div>
</nav>

<!-- Numbered: short lists people browse (connector catalog) -->
<nav class="gw-paginator" aria-label="Connector catalog pages">
  <p class="gw-paginator__info">Page 3 of 12 · 136 connectors</p>
  <ul class="gw-paginator__controls">
    <li><button class="gw-paginator__page" type="button" aria-label="Page 2">2</button></li>
    <li><button class="gw-paginator__page" type="button" aria-label="Page 3" aria-current="page">3</button></li>
    <li aria-hidden="true"><span class="gw-paginator__page"><i class="gw-icon gw-icon--sm" data-lucide="ellipsis"></i></span></li>
    <li><button class="gw-paginator__page" type="button" aria-label="Page 12">12</button></li>
  </ul>
</nav>

<!-- Load older: the audit log, a cursor, never page numbers -->
<div class="gw-paginator">
  <p class="gw-paginator__info">Newest 50 of 1,284 events in the last 24 hours</p>
  <div class="gw-paginator__controls">
    <button class="gw-btn gw-btn--sm" type="button"><i class="gw-icon gw-icon--sm" data-lucide="history"></i> Load older events</button>
  </div>
</div>
```

### Example 42 · gw-skeleton

```html
<section class="gw-card" aria-labelledby="calls-title" aria-busy="true">
  <header class="gw-card__header"><div class="gw-card__heading">
    <h2 class="gw-card__title" id="calls-title">Calls over time</h2>
  </div></header>
  <div class="gw-card__body">
    <p class="gw-sr-only" role="status">Loading calls over time</p>
    <div class="gw-stack gw-stack--sm" aria-hidden="true">
      <span class="gw-skeleton gw-skeleton--text"></span>
      <span class="gw-skeleton gw-skeleton--text"></span>
      <span class="gw-skeleton gw-skeleton--block"></span>
    </div>
  </div>
</section>
```

### Example 43 · gw-code

```html
Codex called <code class="gw-code">sql_query</code> on <code class="gw-code">dev-db</code>.
The password stays in the gateway: <code class="gw-code gw-code--secret">‹masked›</code>.
```

### Example 44 · gw-code-block

```html
<div class="gw-code-block">
  <div class="gw-code-block__header">
    <span class="gw-code-block__lang">SQL</span>
    <span class="gw-code-block__filename">sql_query · dev-db</span>
    <div class="gw-code-block__actions">
      <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" data-gw-copy-from="#q1">
        <span class="gw-code-block__copy"><i class="gw-icon gw-icon--sm" data-lucide="copy"></i> Copy<span class="gw-sr-only"> SQL query</span></span>
        <span class="gw-code-block__copied"><i class="gw-icon gw-icon--sm" data-lucide="check"></i> Copied</span>
      </button>
    </div>
  </div>
  <div class="gw-code-block__body" tabindex="0" role="region" aria-label="SQL query">
<pre class="gw-code-block__pre" id="q1"><code><span class="gw-tok-keyword">SELECT</span> id, status <span class="gw-tok-keyword">FROM</span> orders</code></pre>
  </div>
</div>
```

### Example 45 · gw-diff

```html
<div class="gw-diff">
  <div class="gw-diff__header">
    <span class="gw-diff__filename">~/.codex/config.toml</span>
    <span class="gw-diff__summary">6 lines added · backup kept</span>
  </div>
  <div class="gw-diff__body" tabindex="0" role="region" aria-label="Changes to ~/.codex/config.toml">
    <div class="gw-diff__line gw-diff__line--hunk"><span class="gw-diff__text">@@ -1,6 +1,12 @@</span></div>
    <div class="gw-diff__line gw-diff__line--context">
      <span class="gw-diff__gutter" aria-hidden="true">6</span><span class="gw-diff__gutter" aria-hidden="true">6</span><span class="gw-diff__marker" aria-hidden="true"></span>
      <span class="gw-diff__text">args = ["-y", "@modelcontextprotocol/server-github"]</span>
    </div>
    <div class="gw-diff__line gw-diff__line--add">
      <span class="gw-diff__gutter" aria-hidden="true"></span><span class="gw-diff__gutter" aria-hidden="true">8</span><span class="gw-diff__marker" aria-hidden="true">+</span><span class="gw-sr-only">Added: </span>
      <span class="gw-diff__text">[mcp_servers.gaterook-dev]</span>
    </div>
  </div>
</div>
```

### Example 46 · gw-copy-field

```html
<p class="gw-text-ui" id="ep-url-label">Endpoint URL</p>
<div class="gw-copy-field" role="group" aria-labelledby="ep-url-label">
  <code class="gw-copy-field__value" id="ep-url">http://127.0.0.1:7465/mcp/dev</code>
  <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" data-gw-copy-from="#ep-url">
    <span class="gw-copy-field__copy"><i class="gw-icon gw-icon--sm" data-lucide="copy"></i> Copy<span class="gw-sr-only"> endpoint URL</span></span>
    <span class="gw-copy-field__copied"><i class="gw-icon gw-icon--sm" data-lucide="check"></i> Copied</span>
  </button>
</div>
```

### Example 47 · gw-table-wrap / gw-table

```html
<div class="gw-table-wrap" role="region" aria-labelledby="calls-cap" tabindex="0">
  <table class="gw-table">
    <caption class="gw-sr-only" id="calls-cap">Recent calls</caption>
    <thead><tr>
      <th scope="col" aria-sort="descending">
        <button class="gw-table__sort" type="button">Time
          <i class="gw-icon gw-icon--sm gw-table__sort-icon gw-table__sort-icon--none" data-lucide="chevrons-up-down"></i>
          <i class="gw-icon gw-icon--sm gw-table__sort-icon gw-table__sort-icon--asc" data-lucide="chevron-up"></i>
          <i class="gw-icon gw-icon--sm gw-table__sort-icon gw-table__sort-icon--desc" data-lucide="chevron-down"></i>
        </button>
      </th>
      <th scope="col">Tool</th>
      <th scope="col">Decision</th>
      <th scope="col" class="gw-table__cell--num">Duration</th>
    </tr></thead>
    <tbody>
      <tr>
        <td><time datetime="2026-10-22T14:28:11+02:00" title="2026-10-22 14:28:11">2 min ago</time></td>
        <th scope="row" class="gw-table__cell--mono">sql_query</th>
        <td><span class="gw-badge gw-badge--success"><i class="gw-icon gw-badge__icon" data-lucide="check"></i>Allowed</span></td>
        <td class="gw-table__cell--num">38 ms</td>
      </tr>
    </tbody>
  </table>
</div>
```

### Example 48 · gw-chart / gw-legend / gw-sparkline

```html
<div class="gw-chart" role="img" aria-label="Calls per hour, last 24 hours. Peak of 166 calls at 11:00. Most denials came between 09:00 and 11:00.">
  <div class="gw-chart__plot">
    <span class="gw-chart__gridline" style="--v: 0.5"><span class="gw-chart__label">100</span></span>
    <span class="gw-chart__bar">
      <span class="gw-chart__segment gw-chart__segment--success" style="--v: 0.475"></span>
      <span class="gw-chart__segment gw-chart__segment--danger" style="--v: 0.005"></span>
    </span>
    …
  </div>
  <div class="gw-chart__axis"><span class="gw-chart__label" style="--v: 0">15:00</span> … <span class="gw-chart__label" style="--v: 1">Now</span></div>
</div>
<ul class="gw-legend">
  <li class="gw-legend__item"><span class="gw-legend__swatch gw-legend__swatch--success"></span>Allowed or approved <span class="gw-legend__value">1,247</span></li>
</ul>
<div class="gw-sr-only"><table><caption>Calls per hour by decision, last 24 hours</caption>…</table></div>
```

### Example 49 · gw-waterfall

```html
<p id="wf-label"><code class="gw-code">sql_query</code> on <code class="gw-code">dev-db</code> · 38 ms in total</p>
<ol class="gw-waterfall" aria-labelledby="wf-label">
  <li class="gw-waterfall__row">
    <span class="gw-waterfall__label">Authenticate token and check policy</span>
    <span class="gw-waterfall__track" aria-hidden="true"><span class="gw-waterfall__span gw-waterfall__span--gateway" style="--start: 0; --len: 0.053"></span></span>
    <span class="gw-waterfall__value">2 ms</span>
  </li>
</ol>
```

### Example 50 · gw-timeline

```html
<ol class="gw-timeline" aria-label="Approval history of sql_execute on dev-db">
  <li class="gw-timeline__item">
    <span class="gw-timeline__marker gw-timeline__marker--success"><i class="gw-icon gw-icon--sm" data-lucide="user-check"></i></span>
    <div class="gw-timeline__content">
      <p class="gw-timeline__title">Approved by you</p>
      <p class="gw-timeline__meta">From the desktop notification · after 47 s</p>
      <blockquote class="gw-timeline__comment"><p>Customer asked to cancel order 48213.</p></blockquote>
    </div>
    <time class="gw-timeline__time" datetime="2026-10-22T14:02:58+02:00" title="2026-10-22 14:02:58">14:02:58</time>
  </li>
</ol>
```

### Example 51 · gw-setup-map

```html
<p class="gw-sr-only" id="flow">Set it up left to right. An agent's call travels the other way: AI app → endpoint → policy → connection, where the secret is injected. The agent never receives the secret.</p>
<ol class="gw-setup-map" aria-label="Setup path" aria-describedby="flow">
  <li class="gw-setup-map__step" data-state="done">
    <div class="gw-card gw-setup-map__node">
      <div class="gw-card__body gw-setup-map__body">
        <span class="gw-icon-tile gw-icon-tile--brand gw-setup-map__tile"><i class="gw-icon" data-lucide="key-round"></i></span>
        <h3 class="gw-card__title gw-setup-map__title">Secrets &amp; resources</h3>
        <p class="gw-card__description gw-setup-map__desc">Credentials the agent never sees</p>
        <p class="gw-setup-map__status gw-text-small gw-fg-secondary gw-tabular">12 secrets · master key backed up</p>
        <span class="gw-badge gw-badge--success gw-setup-map__state"><i class="gw-icon gw-badge__icon" data-lucide="circle-check"></i>Done</span>
        <a class="gw-btn gw-btn--link gw-setup-map__action" href="secrets.html">Configure<span class="gw-sr-only"> secrets</span></a>
      </div>
    </div>
    <span class="gw-setup-map__connector" aria-hidden="true">
      <span class="gw-setup-map__line"></span>
      <i class="gw-icon gw-setup-map__arrow gw-setup-map__arrow--across" data-lucide="chevron-right"></i>
      <i class="gw-icon gw-setup-map__arrow gw-setup-map__arrow--down" data-lucide="chevron-down"></i>
    </span>
  </li>
  <!-- … Connections → Policies → Endpoints → AI apps; the final AI apps step has no __connector;
       the Connections step places this note between its card and arrow: -->
  <!-- <p class="gw-setup-map__note gw-text-caption gw-fg-muted"><i class="gw-icon gw-icon--xs" data-lucide="lock-keyhole"></i><span>Secret injected here, never sent to the agent</span></p> -->
</ol>
<p class="gw-text-caption gw-fg-muted" aria-hidden="true"><i class="gw-icon gw-icon--xs" data-lucide="arrow-left-right"></i> Set it up left to right. An agent's call travels the other way: AI app → endpoint → policy → connection, where the secret is injected.</p>
```

### Example 52 · gw-setup-map

```html
<li class="gw-setup-map__item">
  <span class="gw-icon-tile gw-icon-tile--sm gw-icon-tile--neutral" aria-hidden="true"><i class="gw-icon gw-icon--sm" data-lucide="database"></i></span>
  <span class="gw-setup-map__name"><span class="gw-mono" title="dev-db">dev-db</span> <span class="gw-sr-only">environment</span> <span class="gw-env gw-env--sm">dev</span></span>
  <span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Healthy · 12 ms</span></span>
</li>
```

### Example 53 · gw-how-it-works

```html
<section class="gw-card gw-card--muted gw-how-it-works" aria-labelledby="hiw-t">
  <div class="gw-card__body gw-how-it-works__body">
    <div class="gw-how-it-works__header">
      <div class="gw-card__heading">
        <h3 class="gw-card__title" id="hiw-t">How it works</h3>
        <p class="gw-card__description">The agent never sees a secret value, only the result of the call.</p>
      </div>
      <button class="gw-btn gw-btn--ghost gw-btn--sm gw-btn--icon gw-how-it-works__toggle" type="button"
              data-gw-toggle aria-expanded="true" aria-controls="hiw-p" aria-labelledby="hiw-t">
        <i class="gw-icon gw-how-it-works__toggle-icon gw-how-it-works__toggle-icon--collapse" data-lucide="chevron-up"></i>
        <i class="gw-icon gw-how-it-works__toggle-icon gw-how-it-works__toggle-icon--expand" data-lucide="chevron-down"></i>
      </button>
    </div>
    <div class="gw-how-it-works__panel" id="hiw-p">
      <ol class="gw-how-it-works__steps" aria-label="What happens to a secret">
        <li class="gw-how-it-works__step">
          <span class="gw-icon-tile gw-icon-tile--brand gw-how-it-works__icon"><i class="gw-icon" data-lucide="key-round"></i></span>
          <span class="gw-how-it-works__arrow gw-fg-muted" aria-hidden="true"><i class="gw-icon gw-icon--sm" data-lucide="chevron-right"></i></span>
          <p class="gw-how-it-works__title gw-text-h3"><span class="gw-counter" aria-hidden="true">1</span>Store or reference</p>
          <p class="gw-how-it-works__text gw-text-small gw-fg-secondary">Paste the value here, or point to Vault, GCP Secret Manager or AWS Secrets Manager.</p>
        </li>
        <!-- The final step has no __arrow. -->
      </ol>
      <p class="gw-how-it-works__more"><a class="gw-link gw-text-small" href="secrets.html#master-key">Learn more about the master key and rotation</a></p>
    </div>
  </div>
</section>
```

### Example 54 · gw-getting-started

```html
<section class="gw-card gw-getting-started" aria-labelledby="gs-t">
  <header class="gw-card__header">
    <div class="gw-card__heading">
      <h2 class="gw-card__title" id="gs-t"><span class="gw-icon-tile gw-icon-tile--sm gw-icon-tile--brand"><i class="gw-icon gw-icon--sm" data-lucide="list-checks"></i></span>Get started</h2>
      <p class="gw-card__description">Six steps from install to your agent's first safe call. About 5 minutes.</p>
    </div>
    <div class="gw-card__actions"><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button"><i class="gw-icon gw-icon--sm" data-lucide="x"></i>Dismiss checklist</button></div>
  </header>
  <div class="gw-card__body gw-getting-started__body">
    <div class="gw-getting-started__progress">
      <p class="gw-getting-started__count gw-text-small gw-fg-secondary gw-tabular" id="gs-c">5 of 6 done</p>
      <div class="gw-progress gw-progress--sm gw-getting-started__bar" role="progressbar" aria-labelledby="gs-c"
           aria-valuemin="0" aria-valuemax="6" aria-valuenow="5" aria-valuetext="5 of 6 steps done" style="--gw-progress: 0.833">
        <div class="gw-progress__bar"></div>
      </div>
    </div>
    <ol class="gw-getting-started__steps" aria-label="Setup steps">
      <li class="gw-getting-started__step" data-state="done">
        <span class="gw-getting-started__icon gw-fg-success" aria-hidden="true"><i class="gw-icon gw-icon--lg" data-lucide="circle-check"></i></span>
        <p class="gw-getting-started__title gw-text-body gw-fg-secondary">Protect your master key<span class="gw-sr-only">, done</span></p>
        <p class="gw-getting-started__meta gw-text-small gw-fg-muted">Backed up 12 days ago</p>
      </li>
      <li class="gw-getting-started__step" data-state="current" aria-current="step">
        <span class="gw-getting-started__icon gw-fg-brand" aria-hidden="true"><i class="gw-icon gw-icon--lg" data-lucide="circle-dot"></i></span>
        <p class="gw-getting-started__title gw-text-h3">Connect your AI app</p>
        <p class="gw-getting-started__why gw-text-small gw-fg-secondary">Claude Desktop, Codex and Cursor are installed. You see the config change before it is written.</p>
        <a class="gw-btn gw-btn--primary gw-btn--sm gw-getting-started__action" href="clients.html">Connect your AI app</a>
      </li>
      <li class="gw-getting-started__step" data-state="todo">
        <span class="gw-getting-started__icon gw-fg-muted" aria-hidden="true"><i class="gw-icon gw-icon--lg" data-lucide="circle-dashed"></i></span>
        <p class="gw-getting-started__title gw-text-body">Try a tool in Playground<span class="gw-sr-only">, not done yet</span></p>
        <p class="gw-getting-started__why gw-text-small gw-fg-secondary">Call <code class="gw-code">sql_query</code> the way your agent would and see exactly what comes back.</p>
        <a class="gw-btn gw-btn--link gw-getting-started__action" href="playground.html">Open Playground</a>
      </li>
    </ol>
  </div>
</section>
```

### Example 55 · approval-request

```html
<div class="gw-field">
  <label class="gw-field__label" for="ap-rs-comment">Comment <span class="gw-field__required"><span aria-hidden="true">*</span><span class="gw-sr-only">required</span></span></label>
  <textarea class="gw-textarea" id="ap-rs-comment" rows="2" required aria-describedby="ap-rs-comment-hint"></textarea>
  <p class="gw-field__hint" id="ap-rs-comment-hint">Add a comment to approve on prod. Sent to Claude Desktop with your decision and kept in the audit log.</p>
</div>
<div class="gw-approval-request__decision-row">
  <p class="gw-text-caption gw-fg-muted">Runs once, exactly as shown above.</p>
  <div class="gw-approval-request__buttons">
    <button class="gw-btn gw-btn--danger" type="button">Deny<span class="gw-sr-only"> ssh_run on prod-vm</span></button>
    <button class="gw-btn gw-btn--approve" type="button" aria-disabled="true" aria-describedby="ap-rs-approve-why" data-gw-enable-when="#ap-rs-comment">Approve and run<span class="gw-sr-only"> ssh_run on prod-vm</span></button>
  </div>
</div>
<p class="gw-sr-only" id="ap-rs-approve-why">Add a comment to approve on prod</p>
```

### Example 56 · approval-notification

```html
<section class="gw-card gw-approval-notification" aria-labelledby="ch-slack">
  <header class="gw-card__header"><div class="gw-card__heading">
    <h3 class="gw-card__title" id="ch-slack"><span class="gw-client-mark gw-client-mark--generic gw-client-mark--sm" aria-hidden="true">S</span>Slack</h3>
    <p class="gw-card__description">Direct message from the GateRook app to a linked approver.</p>
  </div></header>
  <div class="gw-card__body gw-approval-notification__stage">
    <figure class="gw-approval-notification__preview">
      <div class="gw-approval-notification__message" inert>…preview…</div>
      <figcaption class="gw-sr-only">A text description of what the preview shows.</figcaption>
    </figure>
    <div class="gw-alert gw-alert--info gw-alert--compact">…channel rule…</div>
  </div>
</section>
```

### Example 57 · risk-callout

```html
<div class="gw-alert gw-alert--warning gw-risk-callout" data-state="present">
  <i class="gw-icon gw-alert__icon" data-lucide="shield-alert"></i>
  <div class="gw-alert__content">
    <p class="gw-alert__title">Risky combination in endpoint dev</p>
    <p class="gw-alert__body">An agent that reads untrusted text can be steered into sending private data out. …</p>
    <ul class="gw-risk-callout__ingredients" aria-label="Risk ingredients in endpoint dev">
      <li class="gw-risk-callout__ingredient" data-present="true">
        <div class="gw-risk-callout__ingredient-body">
          <span class="gw-badge gw-badge--danger"><i class="gw-icon gw-badge__icon" data-lucide="database"></i>Private data<span class="gw-sr-only">, present</span></span>
          <span class="gw-text-caption gw-fg-secondary"><code class="gw-code">sql_query</code> reads <code class="gw-code">dev-db</code></span>
        </div>
      </li>
      <li class="gw-risk-callout__ingredient" data-present="true"><i class="gw-icon gw-icon--xs gw-fg-muted gw-risk-callout__plus" data-lucide="plus"></i>…</li>
    </ul>
    <div class="gw-alert__actions">
      <a class="gw-btn gw-btn--secondary gw-btn--sm" href="…#tools">Review tools<span class="gw-sr-only"> of dev</span></a>
      <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" data-gw-dialog-open="dlg-split">Split endpoint<span class="gw-sr-only"> dev</span></button>
      <button class="gw-btn gw-btn--link gw-btn--sm" type="button" data-gw-dialog-open="dlg-dismiss">Dismiss for this endpoint<span class="gw-sr-only">, dev</span></button>
    </div>
  </div>
</div>
```

### Example 58 · event-detail

```html
<article class="gw-event-detail" aria-labelledby="ev-decision ev-title">
  <header class="gw-event-detail__summary">
    <div class="gw-event-detail__headline">
      <span class="gw-badge gw-badge--success" id="ev-decision"><i class="gw-icon gw-badge__icon" data-lucide="user-check"></i>Approved</span>
      <h3 class="gw-event-detail__title gw-text-h3" id="ev-title"><code class="gw-code">sql_execute</code> on <code class="gw-code">dev-db</code> <span class="gw-sr-only">environment</span> <span class="gw-env gw-env--dev">dev</span></h3>
    </div>
    <dl class="gw-kv gw-kv--inline">…Time · Duration · Event…</dl>
    <div class="gw-event-detail__actions">…Copy link · Open trace · View raw…</div>
  </header>
  <section class="gw-event-detail__section" aria-labelledby="ev-request">
    <div class="gw-event-detail__section-header"><h4 class="gw-text-overline" id="ev-request">Request</h4></div>
    <dl class="gw-kv">…</dl>
  </section>
  …
  <hr class="gw-divider">
  <footer class="gw-event-detail__footer">…</footer>
</article>
```

### Example 59 · settings-layout

```html
<div class="gw-settings-layout">
  <nav class="gw-settings-layout__nav gw-nav" aria-label="Settings">
    <div class="gw-nav__group" role="group" aria-labelledby="st-g-security">
      <p class="gw-nav__group-label" id="st-g-security">Security</p>
      <ul class="gw-nav__list">
        <li><a class="gw-nav__item" href="#settings-authorization" aria-current="page">
          <i class="gw-icon gw-nav__icon" data-lucide="shield-check"></i>
          <span class="gw-nav__label">Authorization</span></a></li>
      </ul>
    </div>
  </nav>
  <nav class="gw-settings-layout__jump" aria-label="Settings">
    <div class="gw-field">
      <label class="gw-field__label" for="st-jump">Jump to section</label>
      <select class="gw-select" id="st-jump"><optgroup label="Security">…</optgroup></select>
    </div>
  </nav>
  <div class="gw-settings-layout__content">
    <section class="gw-settings-layout__section" id="settings-authorization" aria-labelledby="st-auth-t">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="st-auth-t">Authorization</h2>
        <p class="gw-section__description">How AI apps prove who they are.</p>
      </header>
      <hr class="gw-divider">
      <div class="gw-form gw-form--sticky-actions">
        <section class="gw-form__section" aria-labelledby="…">…applies immediately…</section>
        <form class="gw-form__section" id="st-oauth" aria-labelledby="…">…requires saving…</form>
        <div class="gw-form__actions">
          <p class="gw-form__note" role="status"><i class="gw-icon" data-lucide="circle-dot"></i>Unsaved changes</p>
          <button class="gw-btn" type="reset" form="st-oauth">Discard</button>
          <button class="gw-btn gw-btn--primary" type="submit" form="st-oauth">Save OAuth settings</button>
        </div>
      </div>
    </section>
  </div>
</div>
```

### Example 60 · status-screen

```html
<main class="gw-status-screen" data-state="starting" aria-labelledby="core-title">
  <div class="gw-status-screen__drag" aria-hidden="true"></div>
  <div class="gw-status-screen__column">
    <span class="gw-logo gw-logo--xl"><img class="gw-logo__mark" src="assets/logo-mark.svg" alt=""><span class="gw-logo__name">GateRook</span></span>
    <section class="gw-card gw-status-screen__card" aria-labelledby="core-title">
      <div class="gw-card__body gw-status-screen__body">
        <div class="gw-status-screen__intro">
          <div class="gw-status-screen__text">
            <h1 class="gw-text-h1" id="core-title">Starting the gateway core</h1>
            <p class="gw-text-body-read">Starting the native core and the local API on <span class="gw-mono">127.0.0.1:7465</span>. This usually takes about a second.</p>
          </div>
        </div>
        <div class="gw-progress gw-progress--indeterminate" role="progressbar" aria-labelledby="core-title"><div class="gw-progress__bar"></div></div>
        <ol class="gw-stepper gw-stepper--vertical" aria-label="Start-up steps">…</ol>
        <p class="gw-sr-only" role="status">Starting the gateway core: local API, step 4 of 4.</p>
      </div>
      <!-- On failure: <footer class="gw-card__footer"> secondary action, then primary action. -->
    </section>
    <p class="gw-status-screen__meta gw-text-caption gw-fg-muted">GateRook 0.4.0 · core on <span class="gw-mono">127.0.0.1:7465</span></p>
  </div>
</main>
```

### Example 61 · command-palette

```html
<dialog class="gw-dialog gw-dialog--md gw-command-palette" id="cmdk" aria-labelledby="cmdk-title" aria-describedby="cmdk-keys">
  <h2 class="gw-sr-only" id="cmdk-title">Command palette</h2>
  <div class="gw-command-palette__search">
    <div class="gw-search">
      <i class="gw-icon gw-search__icon" data-lucide="search"></i>
      <input class="gw-search__input" type="search" role="combobox" autofocus aria-expanded="true"
             aria-controls="cmdk-list" aria-autocomplete="list" aria-activedescendant="cmdk-o-overview"
             aria-label="Search pages, connections, endpoints, actions" placeholder="Search pages, connections, endpoints, actions">
      <button class="gw-search__clear" type="button" aria-label="Clear search"><i class="gw-icon gw-icon--sm" data-lucide="x"></i></button>
    </div>
  </div>
  <div class="gw-command-palette__results">
    <div class="gw-command-palette__list" role="listbox" id="cmdk-list" aria-label="Results">
      <div class="gw-menu__group" role="group" aria-labelledby="cmdk-g-goto">
        <p class="gw-menu__note" id="cmdk-g-goto" aria-hidden="true">Go to</p>
        <a class="gw-menu__item" role="option" id="cmdk-o-overview" href="overview.html" aria-selected="true" tabindex="-1">
          <i class="gw-icon gw-menu__icon" data-lucide="layout-dashboard"></i><span class="gw-menu__label">Overview</span>
          <i class="gw-icon gw-menu__check" data-lucide="corner-down-left"></i>
        </a>
      </div>
    </div>
    <div class="gw-empty gw-empty--compact" hidden>…</div>
  </div>
  <footer class="gw-dialog__footer gw-command-palette__footer" id="cmdk-keys">
    <p class="gw-command-palette__hint gw-text-caption gw-fg-muted"><kbd class="gw-kbd"><i class="gw-icon" data-lucide="arrow-up"></i><span class="gw-sr-only">Up arrow</span></kbd><kbd class="gw-kbd"><i class="gw-icon" data-lucide="arrow-down"></i><span class="gw-sr-only">Down arrow</span></kbd> to move</p>
    <p class="gw-command-palette__hint gw-text-caption gw-fg-muted"><kbd class="gw-kbd"><i class="gw-icon" data-lucide="corner-down-left"></i><span class="gw-sr-only">Enter</span></kbd> to open</p>
    <p class="gw-command-palette__hint gw-text-caption gw-fg-muted"><kbd class="gw-kbd">Esc</kbd> to close</p>
  </footer>
  <p class="gw-sr-only" role="status"></p>
</dialog>
```

### Example 62 · status-tile

```html
<ul class="gw-grid gw-grid--3" role="list" aria-label="Your connections">
  <li class="gw-card gw-card--interactive gw-status-tile">
    <span class="gw-icon-tile gw-icon-tile--neutral" aria-hidden="true"><i class="gw-icon" data-lucide="database"></i></span>
    <div class="gw-status-tile__main">
      <h3 class="gw-status-tile__title gw-text-ui"><a class="gw-card__link gw-mono" href="connection-new.html?edit=dev-db">dev-db</a> <span class="gw-sr-only">environment</span> <span class="gw-env gw-env--dev gw-env--sm">dev</span></h3>
      <p class="gw-text-caption gw-fg-muted gw-truncate">PostgreSQL 16</p>
    </div>
    <div class="gw-status-tile__end">
      <span class="gw-status gw-status--success"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Healthy</span></span>
    </div>
  </li>
</ul>
```
