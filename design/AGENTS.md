# Working with the GateRook design kit

Read these rules before creating UI. [DESIGN.md](DESIGN.md) defines visual and behavioral rules, [COMPONENTS.md](COMPONENTS.md) documents component APIs, and [BLUEPRINT.md](BLUEPRINT.md) defines screens and shared demo data.

The kit is the canonical source for GateRook's tokens, SCSS components, compositions, reference screens, and interaction rules. Reference pages cover the desktop application; keep website marketing pages and guide content out of this package. Build the Angular application and mockups from these sources. The product name is **GateRook**; `gaterook` is the technical slug. Use `assets/logo-mark.svg` and `gw-logo`; regenerate canonical logo exports with `scripts/export-logo.mjs`.

## Language and private working files

All documentation, comments, descriptions, and other authored repository prose must be English. Preserve intentional Unicode input in security tests and demos when it is needed to demonstrate behavior.

Put temporary AI files, scratch work, investigations, research notes, and progress logs in the repository-root `ai/` directory. Never commit anything in `ai/`, and never force-add ignored AI files. The public architecture and research documents are curated deliverables explicitly requested by the user, not scratch files. Keep temporary logo-export dependencies in `ai/` as well.

## 0 · Core rule

**Do not duplicate styles.** Reuse kit components and tokens. If a required component or token does not exist, ask the user before adding it; do not introduce styling only at the call site. Explain the missing capability and propose either a reusable `gw-<name>` component or a variant of an existing component, including its SCSS partial, manifest entry, and showcase example.

The same rule applies to third-party logos. Use `gw-client-mark` neutral monograms unless use of an official vendor asset has been agreed. Official marks must come from the vendor's asset collection with provenance and notices retained.

## 1 · Decision hierarchy

Use the first suitable option:

1. An existing composition in `elements/` and `manifest.json → elements`.
2. An existing component such as `gw-btn`, `gw-card`, or `gw-table`.
3. A documented variant of that component.
4. A mixin from `scss/_mixins.scss`: `gw-type`, `gw-tone`, `gw-surface`, `gw-pressable`, `gw-input-base`, or `gw-focus-ring`.
5. Semantic tokens from `tokens/tokens.json`, referenced as `var(--gw-*)`.
6. A new component or token only when the preceding options do not fit and the user has authorized it.

Do not hard-code colors, dimensions, fonts, shadows, stacking levels, or durations in consumers.

## 2 · Source ownership

| Path | Purpose | Editing rule |
| --- | --- | --- |
| `tokens/tokens.json` | DTCG tokens for light, dark, light-hc, and dark-hc | Change deliberately; new tokens require discussion |
| `tokens/contrast-pairs.json` | Color pairs checked in all four themes | Add every new text/background or border/background pair |
| `tokens/tokens.css`, `scss/_tokens.scss`, `scss/_tokens-css.scss` | Generated token output | Regenerate; never edit directly |
| `scss/_mixins.scss`, `_reset.scss`, `_typography.scss`, `_fonts.scss` | Foundations | Change sparingly |
| `scss/components/_*.scss` | Components | Canonical editable source |
| `elements/_*.scss`, `elements/*.html` | Layout compositions and state demonstrations | Canonical editable source |
| `pages/*.html` | Reference application screens | Edit source pages only |
| `pages/_shell.html` | Shared titlebar and sidebar | Keep the same shell on every application screen |
| `showcase/*.html` | Component demonstrations | Update with component changes |
| `pages/showcase.html`, `dist/gw-kit.css` | Generated showcase and stylesheet | Regenerate; never edit directly |
| `assets/kit.js` | Interaction reference for Angular parity | Keep keyboard, ARIA, and state behavior consistent |
| `assets/theme-boot.js` | Theme applied before first paint | Avoid introducing a theme flash |
| `assets/fonts/`, `vendor/lucide/` | Local fonts and icons | Discuss version changes; preserve licenses |
| `manifest.json` | Machine-readable inventory | Update for every component, composition, and screen |

## 3 · Workflow

Run from `design/`:

```bash
npm ci
npm run build
npm run check
npm run verify
npm run serve
npm run icons -- shield-check database
npm run icons -- --search shield
```

The preview is `http://127.0.0.1:8020/pages/index.html`. `verify` builds and checks the kit and is required before declaring a kit change complete.

Checks cover contrast in four themes, unexplained raw SCSS values, document language and titles, one `main` and `h1`, heading hierarchy, button types and names, image alternatives, valid `for` and `aria-controls` targets, external assets, unsupported glyph icons, missing Lucide names, unknown `gw-*` classes, sidebar consistency, and unregistered or unimported partials.

## 4 · Constraints

- Keep themes in tokens; do not branch on `[data-theme]` inside components.
- Use `gw-card` or `gw-surface` for surfaces. Reuse kit buttons, menus, tooltips, native dialogs with `gw-dialog`, and native tables with `gw-table`.
- Use `gw-page` for width, with `--narrow` for forms and `--full` for journals. Do not add page-specific `max-width` or centered containers.
- Copy the shared navigation from `pages/_shell.html`; do not create a second sidebar.
- Use one primary action per screen level. Green `--approve` is reserved for **Approve and run**.
- Never reveal stored secrets, offer changes without approval, or replace the exact proposed action with an agent's explanation.
- Show state with text, icons, shape, or weight as well as color.
- Open menus and popovers by click, Enter, or Space. Hover and focus alone must not disclose actionable content.
- Use local Lucide icons through `gw-icon`; no emoji or text glyph substitutes and no CDN dependencies.
- Disable ligatures in literal text: an approved `!=` must not visually become a different operator.
- Use sentence case and calm interface language, without exclamation marks.
- Desktop is a single-user product: decisions are **by you**. Named people belong only in explicitly labeled server-mode previews.
- Shared demo time is 14:30; only deadlines may be in the future. Follow [BLUEPRINT.md §9](BLUEPRINT.md#9--shared-demo-world).
- The `/` shortcut belongs to the shared `kit.js` hook, declared with `aria-keyshortcuts="/"`. Do not add per-page key listeners.
- Every dialog specifies initial focus. Use `aria-disabled` plus an explanation when an unavailable action has a reason; use native `disabled` only where appropriate.

## 5 · Extending the kit

For a variant, add `&--<variant>` to its partial using mixins and tokens, demonstrate it in the showcase, register it in `components[].variants`, and verify.

For an authorized new component, add a partial documenting purpose, markup, variants, states, accessibility, and any raw values; import it from `scss/index.scss`; add a showcase fragment, manifest entry, and reference section; then verify.

For an authorized new token, put the light value in `$value`, theme overrides in `$extensions["gw.modes"]`, and purpose in `$description`. Register relevant contrast pairs and verify generated output in all themes.

A new composition must repeat in at least two places. List the existing components it combines. Keep its SCSS limited to layout, add an HTML demonstration of states, import it, register `elements[].composes`, document it, and verify. New appearance requires an underlying component or token change first.

For a new screen, copy `pages/_shell.html`, update the current navigation item, heading, and `<title>` (`Section · GateRook`), compose existing elements, use the shared demo data, register the screen and index link, verify, and test with the keyboard.

## 6 · Before creating a screen

Record which manifest components and compositions will be used, whether anything is missing, whether new tokens are needed, and why any inline values are necessary. Identify risky actions and their confirmation behavior: `--danger` for irreversible actions or untrusted code, `--warning` for reversible risk. Specify initial focus for every dialog and check consistency with the shared demo clock, names, counts, and desktop ownership language.

## 7 · Completion checklist

- `npm run verify` reports zero errors.
- All styling uses tokens; every `// raw:` exception explains why.
- Interactive controls use native semantics and accessible names.
- Each screen has one `h1`, a coherent heading hierarchy, named navigation, and `main`.
- Keyboard navigation, menu/tab arrows, Escape, focus return, and visible focus work.
- Light, dark, and high-contrast themes have been reviewed.
- Empty, loading, error, and permission-denied states exist where applicable.
- Risky decisions show the exact action and identify the affected object.
- Demo data agrees with the blueprint and neighboring screens.
- Repeated groups are reusable compositions rather than copied styling.
