// The entry of main (ARCHITECTURE.md §7.1 "the first main.ts statements terminate the process"): before anything else runs —
// the rest of main is loaded only after this check — the app refuses to start with a switch that opens it to
// another program (switches.ts). Chromium starts its DevTools server only after this script ran, so a refused start
// never had one (the smoke checks the port stays closed).
import { app } from 'electron';
import { findDangerousEnv, findDangerousSwitches, refusalMessage, REFUSED_EXIT_CODE, sanitizeEnvironment } from './switches.ts';

/** Replaced by esbuild (scripts/build.mjs): true in a development bundle, false in a release, where every branch on it
 * is folded away — the smoke runner isn't even in a release bundle. Used directly, not through a variable, so esbuild
 * can fold it. */
declare const __GW_DEV_BUILD__: boolean;

// Linux CI containers without user namespaces can't run Chromium's sandbox: a development build may opt out there.
const allowNoSandbox = __GW_DEV_BUILD__ && !app.isPackaged && process.env['GW_DEV_ALLOW_NO_SANDBOX'] === '1';
const refused = [...findDangerousSwitches(process.argv, (name) => app.commandLine.hasSwitch(name), { allowNoSandbox }),
  ...findDangerousEnv(process.env)];
sanitizeEnvironment(process.env);
if (refused.length > 0) {
  process.stderr.write(`${refusalMessage(refused)}\n`);
  process.exit(REFUSED_EXIT_CODE);
}

void import('./app.ts').then(async (m) => {
  const desktop = m.main();
  // The development smoke (scripts/smoke.mjs); a release bundle doesn't contain it (build.mjs folds DEV_BUILD).
  if (__GW_DEV_BUILD__ && desktop && !app.isPackaged && process.env['GW_SMOKE']) {
    const smoke = await import('./smoke.ts');
    smoke.run(desktop.smokeHost(), process.env['GW_SMOKE']);
  }
});
