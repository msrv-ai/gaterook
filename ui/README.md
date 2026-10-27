# GateRook UI

The renderer of the desktop app: Angular 22 (standalone, signals, zoneless), styled only by the design kit in
`../design` (`@use "@gaterook/design-kit/scss"`). See [the UI architecture](../docs/ARCHITECTURE.md#8-angular-ui-and-design-kit).

## Prerequisites

- Node 24.22.1, pinned in the root `.nvmrc`.
- JDK 25 for the core; set `JAVA_HOME` and add its `bin` directory to `PATH`.
- `npm ci` at the repository root (`ui` is a workspace).

Every `npm run … -w ui` command below is run from the repository root; `start`, `build` and `test` first copy the
kit's fonts, `theme-boot.js` and logo into `public/assets` (`scripts/sync-kit-assets.mjs`).

## Develop against the demo world

```bash
npm run dev -w ui                    # or: npm run dev -w ui -- --port 4311 [--core-port N] [--keep] [--no-demo]
```

One command (`scripts/dev.mjs`):

1. builds `app/target/app.jar` once if it is missing (`mvn -pl app -am -DskipTests package`, under the shared
   heavy-build lock);
2. starts the core on the JVM in dev mode (`-Dgateway.dev=true`, a fresh `GW_DEV_INTERNAL_SECRET`) with `GW_DEMO=1`
   on an empty home in `ui/target/dev-home`: the demo world of BLUEPRINT §9 (`--keep` reuses the last home,
   `--no-demo` starts empty);
3. waits for the core's ready line and starts `ng serve` with `proxy.conf.mjs`, which signs every `/api` request
   (`X-Gateway-Auth`, HMAC of `METHOD|target|ts|nonce`) and strips `Origin`, `Referer`, `Cookie` and `Sec-Fetch-*`,
   as Electron's main process does. `/internal` is not proxied.

Plain `ng serve` has no signing proxy: the core answers 401 and the app shows the status screen.

The demo world is one moment: its pending approvals expire 5 to 10 minutes after the core starts. Restart the dev
loop for a fresh one.

## Element gallery (dev only)

`http://127.0.0.1:<port>/dev/elements` lists the kit's 16 elements; `/dev/elements/<name>` renders one frameless,
like `design/elements/<name>.html`. Elements of merged areas read the dev core (approvals, tokens, tools, risk,
overview, activity, connector types, search); states not represented by the current demo world use fixtures from `src/app/dev/`, moved to the core's clock. Production builds replace
`dev-routes.ts` with `dev-routes.prod.ts`: no gallery or fixture ships.

## Compare with the kit

```bash
npm run compare -w ui -- --app http://127.0.0.1:4311 [--only overview,token-issue] [--themes light,dark,hc]
```

Screenshots of each kit page or element and the matching app route at 1440×900, side by side, in
`ui/target/compare/` (git-ignored) with an `index.html` ranked by the share of differing pixels — a triage aid,
not a threshold: data and dates differ by design. Chromium comes from `$PLAYWRIGHT_BROWSERS_PATH` or
`/opt/pw-browsers`; the script never downloads one.

## Test and build

```bash
npm test -w ui                       # Vitest + jsdom: kit.js parity, elements, schema form, code, API, SSE, shell
npm run build -w ui                  # production build into ui/dist (CSP-friendly: no inline styles or innerHTML)
node ui/scripts/schema-goldens.mjs --fetch   # refresh the connector form goldens from a running dev loop
```
