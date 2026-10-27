# Token broker and client bridge

`@mcp-gateway/broker` runs in Electron main. `gw-bridge.mjs` is the stdio bridge packaged in Claude Desktop `.mcpb` extensions. Both use dependency-free ESM. The repository toolchain is Node 24; the standalone bridge supports Node 18 or newer. See [architecture](../docs/ARCHITECTURE.md#72-broker-and-clients).

```text
Client → gw-bridge (headers or stdio) → local socket/pipe → Electron broker
  → signed POST /internal/bridge/tokens → core → short-lived gwb_ token
```

The core decides whether a `gr_` grant may obtain a token. The broker signs the request with the launch secret and asks the person through `confirm` when the core returns `confirmation_required`, such as the first production-grant token in a core session.

## Electron API

```js
import { createCoreClient, startBroker } from '@mcp-gateway/broker';

const core = createCoreClient({
  origin: `http://127.0.0.1:${port}`, // Port from the core's ready message.
  launchSecret,                    // The bootstrap secret; never expose to the renderer.
  instanceNonce,                   // Reject a response from a different core instance.
});

const broker = await startBroker({
  home: gatewayHome,
  core,
  // true allows; false denies; null/timeout leaves confirmation required.
  confirm: async ({ client, endpoint, message }) => showProdDialog({ client, endpoint, message }),
  log: (line) => logger.info(`[broker] ${line}`), // Never log tokens.
});

app.on('will-quit', () => broker.close());
```

| Export | Contract |
| --- | --- |
| `startBroker(options)` | Returns `{path, infoPath, close()}`. Creates a random Unix socket (directory 0700, socket 0600) or Windows named pipe and a restricted `run/broker.json`. A live existing broker yields `EADDRINUSE`; an abandoned socket can be replaced. |
| `createCoreClient({origin, launchSecret, instanceNonce?, timeoutMs?})` | Signed requests to the core's bridge token/confirmation routes; origin must be loopback HTTP. |
| `signRequest(secret, method, target, ts?, nonce?)` | `ts.nonce.base64url(HMAC-SHA256(secret, "METHOD\|target\|ts\|nonce"))`. |
| `ping(address, ms?)` | Broker liveness check. |
| `readBrokerInfo(home)`, `brokerInfoPath(home)`, `randomBrokerPath(home, platform?)` | Broker discovery and path helpers. |
| `PROTOCOL_VERSION`, `BROKER_VERSION`, `MAX_REQUEST_BYTES` | Protocol constants: version 1 and a 4096-byte request limit. |

Options include `confirmTimeoutMs` (55,000), `requestTimeoutMs` (3,000 per request line), `maxConnections` (32), and a platform override for tests. Concurrent requests for the same grant share one confirmation. See `index.d.ts` for types.

The core also emits `bridge.confirm` on `/api/events`. Main can submit a signed `POST /internal/bridge/confirm` with `{grant, decision: "allow" | "deny"}` after a UI decision.

## Protocol v1

One connection carries one newline-terminated JSON request, at most 4 KiB, and one JSON response line:

```json
{"v":1,"op":"token","client":"codex","endpoint":"dev","grant":"gr_PLACEHOLDER","pid":48211,"bridge":"0.4.0"}
{"v":1,"ok":true,"token":"gwb_PLACEHOLDER","expiresAt":"...","url":"http://127.0.0.1:7465/mcp/dev","instance":"..."}
{"v":1,"ok":false,"error":"grant_revoked","message":"..."}
{"v":1,"op":"ping"}
{"v":1,"ok":true,"broker":"0.4.0"}
```

Errors include `bad_request`, `grant_unknown`, `grant_changed`, `grant_revoked`, `grant_mismatch`, `endpoint_off`, `confirmation_required`, `confirmation_denied`, `rate_limited`, and `core_unavailable`. The token endpoint limits grant issuance to 20 requests per minute. The bridge checks the instance against `X-Gateway-Bridge-Instance` on MCP responses to avoid trusting an unrelated process on the core port.

## Bridge and development

```bash
node bridge/gw-bridge.mjs stdio --endpoint dev --grant gr_PLACEHOLDER --client claude-desktop
npm test -w bridge
```

The bridge discovers the broker through `GW_BROKER` (a `broker.json` path) or `GW_HOME`. It refreshes a cached token before expiry and retries authentication once after a 401. The core's `McpbBuilder` includes this script as `server/gw-bridge.mjs`. The Java/native equivalent lives in `bridge-cli/` and supports `headers`, `stdio`, and `sandbox-exec`.

For a separately running development core, set `GW_DEV_INTERNAL_SECRET` to the same development secret and run `node bridge/dev-broker.mjs --home ./.dev --origin http://127.0.0.1:7465 --confirm allow`. This development broker prints a ready record and stops on SIGTERM or stdin EOF. Use it only with a disposable development home.
