// The token broker in main (ARCHITECTURE.md A7, §7.2; bridge/README.md): bridges of Claude Desktop and Codex ask it
// for a 15-minute token by grant; it asks the core (`/internal/bridge/tokens`, signed) and, when the core wants a
// person to allow the first token of a prod grant in this session, shows "Codex wants to use incident-prod". It lives
// as long as the app: when the core restarts, the broker talks to the new one.
import { app, dialog } from 'electron';
import { createCoreClient, startBroker, type Broker } from '@gaterook/broker';
import { answeredByAPerson } from './activation-gate.ts';
import type { Logger } from './log.ts';
import type { CoreEndpoint } from './proxy.ts';

const CLIENT_NAMES: Readonly<Record<string, string>> = {
  'claude-desktop': 'Claude Desktop',
  codex: 'Codex',
  cursor: 'Cursor',
  antigravity: 'Antigravity',
};

export function clientName(client: string): string {
  return CLIENT_NAMES[client] ?? client.slice(0, 32);
}

export interface BrokerOptions {
  readonly home: string;
  readonly core: () => CoreEndpoint | null;
  readonly log: Logger;
}

export async function startTokenBroker(o: BrokerOptions): Promise<Broker | null> {
  try {
    const broker = await startBroker({
      home: o.home,
      core: {
        request(path, body) {
          const core = o.core();
          if (!core) return Promise.reject(new Error('The core is not running'));
          return createCoreClient({ origin: `http://127.0.0.1:${core.port}`, launchSecret: core.launchSecret,
            instanceNonce: core.instanceNonce }).request(path, body);
        },
      },
      confirm: ({ client, endpoint, message }) => askProdUse(clientName(client), endpoint, message, o.log),
      log: (line) => o.log(`broker: ${line}`),
    });
    o.log(`token broker listening (${broker.infoPath})`);
    return broker;
  } catch (e) {
    // Another GateRook answers on its broker (a second app with the same home): bridges keep using that one.
    o.log(`token broker not started: ${(e as Error).message}`);
    return null;
  }
}

/**
 * "Codex wants to use incident-prod": allow the first token of a prod grant in this session, or not. Deny is the
 * default and the answer to Esc; an Allow given faster than a person can read (700 ms) is not taken — the dialog
 * comes back, three times at most, then it is a Deny.
 */
export async function askProdUse(client: string, endpoint: string, message: string, log: Logger): Promise<boolean> {
  if (process.platform === 'darwin') app.focus({ steal: true });
  for (let attempt = 0; attempt < 3; attempt++) {
    const shownAt = performance.now();
    const { response } = await dialog.showMessageBox({
      type: 'warning',
      title: 'GateRook',
      message: `${client} wants to use ${endpoint}`,
      detail: `${message ? `${message}\n\n` : ''}${endpoint} works on production. Allow it only if you started ${client} `
        + 'yourself just now. Each change it asks for still waits for your approval.',
      buttons: ['Deny', 'Allow'],
      defaultId: 0,
      cancelId: 0,
      noLink: true,
    });
    if (response !== 1) return false;
    if (answeredByAPerson(performance.now() - shownAt)) return true;
    log(`"${client} wants to use ${endpoint}": Allow came within 700 ms, asking again`);
  }
  return false;
}
