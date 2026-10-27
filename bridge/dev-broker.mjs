#!/usr/bin/env node
// A broker for a core started without Electron (development, tests; report 03 §9.7):
//
//   GW_DEV_INTERNAL_SECRET=… node bridge/dev-broker.mjs --home ./.dev --origin http://127.0.0.1:7465 [--confirm allow|deny]
//
// It signs with the dev launch secret (GW_DEV_INTERNAL_SECRET, or GW_LAUNCH_SECRET), prints one JSON line
// {"ready":true,"path":…,"infoPath":…} on stdout once it listens, and stops on SIGTERM or when stdin closes.
// --confirm answers "may this client use a prod endpoint" for the person; without it bridges get confirmation_required.

import { createCoreClient, startBroker } from './index.mjs';

const args = {};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, '')] = argv[i + 1];
const secret = process.env.GW_LAUNCH_SECRET || process.env.GW_DEV_INTERNAL_SECRET;
if (!args.home || !args.origin || !secret) {
  process.stderr.write('usage: GW_DEV_INTERNAL_SECRET=… node dev-broker.mjs --home <gateway home> --origin '
    + 'http://127.0.0.1:<port> [--confirm allow|deny] [--instance <nonce>]\n');
  process.exit(3);
}
const core = createCoreClient({ origin: args.origin, launchSecret: secret, instanceNonce: args.instance });
const confirm = args.confirm ? async () => args.confirm === 'allow' : undefined;
const broker = await startBroker({
  home: args.home, core, confirm, log: (line) => process.stderr.write(`[broker] ${line}\n`),
});
process.stdout.write(JSON.stringify({ ready: true, path: broker.path, infoPath: broker.infoPath }) + '\n');
const stop = async () => {
  await broker.close();
  process.exit(0);
};
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
process.stdin.on('end', stop);
process.stdin.resume();
