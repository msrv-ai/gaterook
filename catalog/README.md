# Catalog

The signed catalog of plugins, templates and MCP servers (ARCHITECTURE.md §7.5 "Catalog", report 11 §4.4).
The core shows it in Connectors › Catalog, installs from it, and trusts it only by an Ed25519 signature over the raw
bytes of `index.json`, made with a key whose public half a release ships (`gateway.plugins.catalog-keys`).

| File | What |
|---|---|
| `index.json` | The source index: the entries as the kit's Catalog shows them. Artifact hashes, `sequence` and the times are filled in when it is signed. |
| `mcp-servers/github-mcp-0.6.2.package-lock.json` | The lockfile `npm ci --ignore-scripts` installs `github-mcp` from; the index pins its SHA-256. |
| `sign-index.mjs` | Signs the index: pins every artifact, sets `sequence`, `generatedAt`, `expiresAt` (≤ 90 days), keeps `revoked[]`. |
| `sign-index.test.mjs` | `node --test 'catalog/*.test.mjs'` |
| `publish.sh` | Builds the plugin JARs, signs, verifies and creates the GitHub release (only when explicitly requested). |

## Entries (schema 1)

- `plugin`: `artifact {url, sha256, size}` — the JAR; `signed:false` (RabbitMQ) pins the JAR's hash too, but the core
  installs it only after the unsigned dialog: nobody reviewed the code.
- `template`: `templateKind`, `connectionTypes`, `definitions[]` — installed by `POST /api/templates {catalogRef,
  connection}`, one template per definition; read-only confirmations in a definition are dropped on import.
- `mcp-server`: `npm {package, version, integrity, lockfile {url, sha256, size}, bin, node, ignoreScripts}`, `env[]`,
  `serverName`, `toolPrefix` — installed by the C10 runtime with `npm ci --ignore-scripts` and started as `node <bin>`.

An artifact `url` is a file name (or an `https://` URL): the core resolves it against the folder the index came from,
so one GitHub release carries the index, its signature and every file it pins.

## Signing and publishing

```sh
node catalog/sign-index.mjs keygen --key-file ~/catalog-2026-1.pem --key-id catalog-2026-1   # once; prints the trust line
CATALOG_REPO=<org>/mcp-gateway-catalog CATALOG_KEY_ID=catalog-2026-1 CATALOG_PUBLIC_KEY=<base64url> \
  catalog/publish.sh --key-file ~/catalog-2026-1.pem              # dry run: build, sign, verify
  catalog/publish.sh --key-file ~/catalog-2026-1.pem --publish    # and create the release (--first the first time)
```

The release's core gets `gateway.plugins.catalog-keys=catalog-2026-1=<base64url>` and
`gateway.plugins.catalog-url=https://github.com/<org>/mcp-gateway-catalog/releases/latest/download/`. A new release
must have a larger `sequence` (the core refuses a rollback) and be published before the last one expires (the core
then shows the cached catalog with "Checked … ago" and installs nothing new from it). To revoke a build:
`--revoke <sha256>=<reason>`; revocations stay in every later index.
