# GateRook UI

The renderer of the desktop app uses Angular 22 with standalone components, signals, and zoneless change detection.
Compiled interface styles and runtime assets are versioned in `../assets/`. See [the UI architecture](../docs/ARCHITECTURE.md#8-angular-ui).

## Prerequisites

- Node 24.22.1, pinned in the root `.nvmrc`.
- JDK 25 for the core; set `JAVA_HOME` and add its `bin` directory to `PATH`.
- `npm ci` at the repository root (`ui` is a workspace).

Every `npm run … -w ui` command below is run from the repository root; `start`, `build` and `test` first copy the
bundled fonts, `theme-boot.js` and logo into `public/assets` (`scripts/sync-ui-assets.mjs`).

## Develop against the demo world

```bash
npm run dev -w ui                    # or: npm run dev -w ui -- --port 4311 [--core-port N] [--keep] [--no-demo]
```

One command (`scripts/dev.mjs`):

1. builds `app/target/app.jar` once if it is missing (`mvn -pl app -am -DskipTests package`, under the shared
   heavy-build lock);
2. starts the core on the JVM in dev mode (`-Dgateway.dev=true`, a fresh `GW_DEV_INTERNAL_SECRET`) with `GW_DEMO=1`
   on an empty home in `ui/target/dev-home`: the shared demo world (`--keep` reuses the last home,
   `--no-demo` starts empty and discovers sample AI apps in `demo-user-home` inside that development home,
   so connecting them does not change your real AI app configurations);
3. waits for the core's ready line and starts `ng serve` with `proxy.conf.mjs`, which signs every `/api` request
   (`X-Gateway-Auth`, HMAC of `METHOD|target|ts|nonce`) and strips `Origin`, `Referer`, `Cookie` and `Sec-Fetch-*`,
   as Electron's main process does. `/internal` is not proxied.

Plain `ng serve` has no signing proxy: the core answers 401 and the app shows the status screen.

The demo world is one moment: its pending approvals expire 5 to 10 minutes after the core starts. Restart the dev
loop for a fresh one.

## Element gallery (dev only)

`http://127.0.0.1:<port>/dev/elements` lists 17 reusable elements; `/dev/elements/<name>` renders one without the application shell. Elements of merged areas read the dev core (approvals, tokens, tools, risk,
overview, activity, connector types, search); states not represented by the current demo world use fixtures from `src/app/dev/`, moved to the core's clock. Production builds replace
`dev-routes.ts` with `dev-routes.prod.ts`: no gallery or fixture ships.

## Test and build

```bash
npm test -w ui                       # Vitest + jsdom: interactions, elements, schema form, code, API, SSE, shell
npm run build -w ui                  # production build into ui/dist (CSP-friendly: no inline styles or innerHTML)
node ui/scripts/schema-goldens.mjs --fetch   # refresh the connector form goldens from a running dev loop
```
