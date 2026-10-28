// Types of @gaterook/broker for Electron's main process (TypeScript). The package itself is plain ESM.

/** What the broker answers a bridge (protocol v1). */
export type BrokerAnswer =
  | { v: 1; ok: true; token: string; expiresAt: string; url: string; instance?: string }
  | { v: 1; ok: true; broker: string }
  | { v: 1; ok: false; error: BrokerError; message: string };

export type BrokerError =
  | 'bad_request' | 'grant_unknown' | 'grant_changed' | 'grant_revoked' | 'grant_mismatch' | 'endpoint_off'
  | 'confirmation_required' | 'confirmation_denied' | 'rate_limited' | 'core_unavailable';

/** How the broker reaches the core: POST of a JSON body to /internal/bridge/*, the parsed JSON answer back. */
export interface CoreClient {
  request(path: '/internal/bridge/tokens' | '/internal/bridge/confirm', body: object): Promise<any>;
}

/** The person is asked: may this client use a prod endpoint? Allow is remembered for this grant across restarts. */
export interface ConfirmRequest {
  client: 'claude-desktop' | 'codex' | 'cursor' | 'antigravity' | string;
  endpoint: string;
  grant: string;
  message: string;
}

export interface BrokerOptions {
  /** The gateway home, e.g. ~/.gaterook. */
  home: string;
  core: CoreClient;
  /** Resolve true to allow, false to deny, null for no decision; absent means "confirmation_required". */
  confirm?: (ask: ConfirmRequest) => Promise<boolean | null>;
  /** Each bridge waits at most this long; the shared dialog and its later answer remain valid. Default 55,000 ms. */
  confirmTimeoutMs?: number;
  /** Time a connection has to send its request line, default 3,000 ms. */
  requestTimeoutMs?: number;
  /** Default 32. */
  maxConnections?: number;
  /** One line per refusal or error; never a token. */
  log?: (line: string) => void;
  /** Defaults to process.platform. */
  platform?: NodeJS.Platform;
}

export interface Broker {
  /** The socket (macOS, Linux) or pipe (Windows) the broker listens on: a random name. */
  readonly path: string;
  /** run/broker.json (0600), where bridges find `path`. */
  readonly infoPath: string;
  close(): Promise<void>;
}

export function startBroker(options: BrokerOptions): Promise<Broker>;

export interface CoreClientOptions {
  /** http://127.0.0.1:<port> */
  origin: string;
  /** base64url, the secret Electron put into the core's bootstrap line. */
  launchSecret: string;
  /** The core's instance nonce: answers without the same X-Gateway-Instance are refused. */
  instanceNonce?: string;
  timeoutMs?: number;
}

export function createCoreClient(options: CoreClientOptions): CoreClient;

/** X-Gateway-Auth: <ts>.<nonce>.<mac>, the same as the core's platform.LaunchSecret.sign. */
export function signRequest(launchSecret: string, method: string, target: string, ts?: number, nonce?: string): string;

export function ping(address: string, ms?: number): Promise<boolean>;
export function brokerInfoPath(home: string): string;
export function readBrokerInfo(home: string): { v: number; path: string; pid: number; broker: string; startedAt: string } | null;
export function randomBrokerPath(home: string, platform?: NodeJS.Platform): string;
export const PROTOCOL_VERSION: 1;
export const BROKER_VERSION: string;
export const MAX_REQUEST_BYTES: number;
