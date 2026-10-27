# GateRook design kit

The canonical interface system for **GateRook**, the Electron and Angular desktop application with a local Spring Boot core. The kit includes semantic tokens, four themes, SCSS components, reusable compositions, reference screens, local assets, interaction behavior, and automated checks.

| Reference | Purpose |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Read first: workflow, reuse rules, and verification |
| [DESIGN.md](DESIGN.md) | Visual style, product patterns, content, and accessibility |
| [COMPONENTS.md](COMPONENTS.md) | Component and composition usage, markup, and states |
| [BLUEPRINT.md](BLUEPRINT.md) | Screen contracts, vocabulary, and consistent demo data |
| [manifest.json](manifest.json) | Machine-readable classes, variants, and source inventory |
| [Application architecture](../docs/ARCHITECTURE.md) | Runtime architecture and integration boundaries |

## Run the kit

```bash
cd design
npm ci
npm run verify
npm run serve
```

Open `http://127.0.0.1:8020/pages/index.html`. The index links to application reference screens, reusable compositions, and the generated component showcase. Use `npm run icons -- --search shield` to inspect the pinned Lucide inventory.

`npm run build` generates token styles, compiles SCSS, assembles the showcase, and generates public documentation pages. `npm run check` checks contrast, SCSS, HTML, accessibility conventions, links, and the manifest. `npm run verify` performs both.

## Use in a static page

```html
<!-- In head, before CSS, to apply the theme before first paint. -->
<script src="design/assets/theme-boot.js"></script>
<link rel="stylesheet" href="design/dist/gw-kit.css">
<!-- At the end of body. -->
<script src="design/vendor/lucide/lucide.min.js"></script>
<script src="design/assets/kit.js"></script>
```

Fonts, tokens, components, and compositions are included by `gw-kit.css`. Fonts and icons are local; the kit does not need a CDN.

## Use in Angular

The application consumes `@gaterook/design-kit` as a local package. Its SCSS entry is `scss/index.scss`; the built CSS entry is `dist/gw-kit.css`. The package also exports tokens, the theme bootstrap, the manifest, and canonical logo assets. See the actual integration in [`../ui/src/styles.scss`](../ui/src/styles.scss) and [`../scripts/sync-kit-assets.mjs`](../scripts/sync-kit-assets.mjs).

```scss
@use "@gaterook/design-kit/scss" with ($gw-font-path: "/assets/fonts");
```

Apply the theme before first paint. `window.gwTheme.set({ theme: "dark" })` is the static kit's theme API. In Angular, use the application's theme service and Electron preload boundary. Use the pinned Lucide icon set with the `gw-icon` class. `assets/kit.js` is the behavior specification for keyboard handling, ARIA, menus, dialogs, focus, and states; Angular implements equivalent behavior with its components and CDK. Do not load two independent behavior controllers on the same application screen.

Reuse the kit's classes for JSON Schema controls. The current application's schema-form implementation is documented by the application architecture; reference HTML is not a substitute for its runtime logic.

## Logo and reusable assets

The mark combines a rook and an open gateway. [assets/gaterook/preview.svg](assets/gaterook/preview.svg) shows the export set. Colors come from the token source: Gate violet is `color.brand.solid`, white is `color.brand.on-solid`, and the wordmark uses the relevant `color.text.primary`. Exported SVG wordmarks contain Inter Semibold outlines and require no installed font.

| Asset in `assets/gaterook/` | Use |
| --- | --- |
| `gaterook-logo.svg` / `.png` | Horizontal logo for light surfaces |
| `gaterook-logo-dark.svg` / `.png` | Horizontal logo for dark surfaces |
| `gaterook-mark.svg` / `.png` | Violet mark on transparency |
| `gaterook-mark-mono.svg` / `.png` | Single-color mark; SVG inherits `currentColor` |
| `gaterook-mark-white.svg` / `.png` | White mark for dark or colored surfaces |
| `gaterook-icon.svg` / `.png` | App icon; primary PNG is 1024 × 1024 |
| `gaterook-icon-{16,20,24,32,48,64,128,256,512}.png` | Raster icon sizes |

Keep at least eight units of clear space on the 64 × 64 base canvas and preserve proportions. Minimum icon width is 16 px; minimum horizontal-logo width is 144 px. For an image on a dark surface, use the white asset rather than relying on inherited SVG color.

`assets/logo-mark.svg` is the canonical mark used throughout the kit. `assets/logo-mark-mono.svg` has the same silhouette. `assets/logo.svg` is the outlined 288 × 80 logo, with a 1728 × 480 PNG counterpart; `assets/logo-mark.png` is 1024 × 1024. `gw-logo` combines the mark with live **GateRook** text so the wordmark follows the active theme.

Run export from the repository root. Temporary export dependencies belong in ignored `ai/`:

```bash
npm install --prefix ai/logo-export --no-save --package-lock=false fontkit@2.0.4 wawoff2@2.0.1 sharp@0.35.5
NODE_PATH="$PWD/ai/logo-export/node_modules" node design/scripts/export-logo.mjs
```

Preserve font and vendor notices in `assets/fonts/`, `vendor/lucide/`, and [assets/integrations/SOURCES.md](assets/integrations/SOURCES.md).

## Reference marketing and documentation pages

[pages/gaterook.html](pages/gaterook.html) is a local marketing reference with an illustrative access-and-approval demo. Its standalone navigation is intentional; application screens continue using `pages/_shell.html`. The demo changes browser state only and does not contact infrastructure. Product language and claim boundaries are specified in [BLUEPRINT.md](BLUEPRINT.md#11--marketing-reference).

The download dialog contains macOS and Windows cards. In `assets/promo.js`, `releases.macos` and `releases.windows` start as `null`: the corresponding link displays **Coming soon**, has `aria-disabled="true"`, and has no `href`. Set a verified installer URL to make a platform available. Do not invent release URLs, supported architectures, or availability.

The connection diagram is sourced from the `@FLOW` block in `pages/gaterook.html`, styled by `_promo.scss`, and controlled by `assets/promo-flow.js`. It is copied into the showcase by the build. It pauses for reduced motion, hidden pages, offscreen sections, and the user's Pause control. Vendor asset provenance is recorded in the kit.

The [Docs index](pages/docs.html) links to getting started and 15 integration guides. Content lives in `content/integrations-data.mjs`, `content/integrations-cloud.mjs`, and `content/getting-started.html`. `scripts/build-docs.mjs` generates `pages/docs*.html`, `pages/mcp-*.html`, manifest entries, and index links. **Edit the content and generator, not generated pages.** Search is optional enhancement: all guides remain readable without JavaScript.

To inspect the optional static export, provide an explicit site URL:

```bash
npm run build:site -- --site-url https://your-domain.example/
npm run check:site -- --site-url https://your-domain.example/
```

This produces `site-dist/` with 18 public pages, local assets, canonical URLs, Open Graph metadata, BreadcrumbList, sitemap, and robots rules. It excludes application previews. Deployment is a separate action; these commands do not publish. When using a subpath, preserve that path in `--site-url` and deployment, and merge the sitemap directive into the domain-root robots file. Search ranking is not guaranteed by metadata.

## Source layout

```text
design/
├── tokens/       DTCG source, contrast pairs, generated CSS
├── scss/         Foundations and component partials
├── elements/     Reusable compositions and demonstrations
├── showcase/     Editable component showcase fragments
├── pages/        Reference screens and generated public guides
├── assets/       Behavior, themes, fonts, and brand assets
├── vendor/       Pinned local icons and notices
├── content/      Public guide content
├── scripts/      Build, verification, export, and preview tools
├── dist/         Generated distributable kit stylesheet
└── manifest.json
```

Do not edit generated token SCSS/CSS, the compiled kit stylesheet, the assembled showcase, or generated public guides directly. Keep temporary AI research and work files in repository-root `ai/`, which must never be committed.
