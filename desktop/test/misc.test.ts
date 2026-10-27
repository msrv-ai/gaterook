import assert from 'node:assert/strict';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { CORE_ENV_ALLOWLIST, coreArgv, coreEnv } from '../src/main/core-command.ts';
import { clientId, defaultFileName, filePurpose, linkId, revealKind } from '../src/main/file-purposes.ts';
import { resolveLayout } from '../src/main/layout.ts';
import { autoUpdateWanted, checkDue, friendlyUpdateError, namesPublisher, updatesUnavailable, updateStateFor } from '../src/main/updates.ts';
import { openableConfig, revealableFolder } from '../src/main/file-purposes.ts';
import { mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { tempDir } from './helpers.ts';

describe('starting the core', () => {
  it('passes -X/-D flags only, never a secret, and no -XX to the native binary', () => {
    const { file, args } = coreArgv({ kind: 'native', binary: '/r/core/gaterook-core', libDir: '/r/core' },
      { home: '/h/.gaterook', resources: '/r' });
    assert.equal(file, '/r/core/gaterook-core');
    assert.deepEqual(args, ['-Xmx384m', '-Dorg.sqlite.lib.path=/r/core', `-Djava.io.tmpdir=${join('/h/.gaterook', 'tmp')}`,
      '-Dgateway.home=/h/.gaterook', '-Dgateway.resources=/r']);
    assert.ok(args.every((a) => a.startsWith('-X') || a.startsWith('-D')));
    assert.ok(!args.some((a) => a.startsWith('-XX')));
  });

  it('runs the jar with the development bridge in a development build', () => {
    const { file, args } = coreArgv({ kind: 'jar', java: '/jdk/bin/java', jar: '/repo/app/target/app.jar' },
      { home: '/h', resources: null, bridgeJar: '/repo/bridge-cli/target/gw-bridge.jar' });
    assert.equal(file, '/jdk/bin/java');
    assert.deepEqual(args.slice(-2), ['-jar', '/repo/app/target/app.jar']);
    assert.ok(args.includes('-Dgateway.clients.bridge-jar=/repo/bridge-cli/target/gw-bridge.jar'));
    assert.ok(!args.some((a) => a.startsWith('-Dgateway.resources')));
  });

  it('closes the JVM fallback to attaching programs, as report 12 §6 asks', () => {
    const { args } = coreArgv({ kind: 'jar', java: '/r/jre/bin/java', jar: '/r/core/app.jar' }, { home: '/h', resources: '/r' });
    for (const flag of ['-XX:+DisableAttachMechanism', '-XX:-UsePerfData', '-XX:+ExitOnOutOfMemoryError']) {
      assert.ok(args.indexOf(flag) >= 0 && args.indexOf(flag) < args.indexOf('-jar'), flag);
    }
  });

  it('gives the core a short allowlist of variables', () => {
    const env = coreEnv({ PATH: '/usr/bin', HOME: '/Users/x', LANG: 'en_US.UTF-8', AWS_SECRET_ACCESS_KEY: 's', NODE_OPTIONS: '--inspect',
      GITHUB_TOKEN: 't', JAVA_TOOL_OPTIONS: '-javaagent:x', ELECTRON_RUN_AS_NODE: '1', GW_DEMO: '1', CODEX_HOME: '/c' }, false);
    assert.deepEqual(env, { PATH: '/usr/bin', HOME: '/Users/x', LANG: 'en_US.UTF-8', CODEX_HOME: '/c' });
    assert.equal(coreEnv({ PATH: '/p', GW_DEMO: '1' }, true)['GW_DEMO'], '1', 'development passes the demo switch');
    for (const name of CORE_ENV_ALLOWLIST) assert.doesNotMatch(name, /SECRET|TOKEN|PASSWORD|KEY|OPTIONS/);
  });
});

describe('layout', () => {
  const base = { appPath: '/repo/desktop', resourcesPath: '/repo/desktop/res', homedir: '/Users/x', platform: 'darwin' as const };

  it('an installed app uses the bundle and ~/.gaterook, whatever the environment says', () => {
    const l = resolveLayout({ ...base, devBuild: false, packaged: true, appPath: '/Applications/GateRook.app/Contents/Resources/app.asar',
      resourcesPath: '/Applications/GateRook.app/Contents/Resources', env: { GW_HOME: '/tmp/evil', GW_CORE_COMMAND: '["sh"]' } });
    assert.equal(l.dev, false);
    assert.equal(l.home, '/Users/x/.gaterook');
    assert.equal(l.uiRoot, '/Applications/GateRook.app/Contents/Resources/app.asar/ui');
    assert.deepEqual(l.core, { kind: 'native', binary: '/Applications/GateRook.app/Contents/Resources/core/gaterook-core',
      libDir: '/Applications/GateRook.app/Contents/Resources/core' });
  });

  it('a development build packaged by mistake is still a release', () => {
    assert.equal(resolveLayout({ ...base, devBuild: true, packaged: true, env: { GW_HOME: '/tmp/x' } }).dev, false);
  });

  it('a development run follows its variables', () => {
    const l = resolveLayout({ ...base, devBuild: true, packaged: false, env: { GW_HOME: '/tmp/home', GW_UI_DIST: '/tmp/ui',
      GW_CORE_COMMAND: '["node","fake.mjs"]' } });
    assert.equal(l.dev, true);
    assert.equal(l.home, '/tmp/home');
    assert.equal(l.uiRoot, '/tmp/ui');
    assert.deepEqual(l.core, { kind: 'custom', argv: ['node', 'fake.mjs'] });
    const jar = resolveLayout({ ...base, devBuild: true, packaged: false, env: { JAVA_HOME: '/jdk' } });
    assert.deepEqual(jar.core, { kind: 'jar', java: '/jdk/bin/java', jar: '/repo/app/target/app.jar' });
    assert.equal(jar.uiRoot, '/repo/ui/dist/gaterook-ui/browser', 'the renamed UI project when none is built yet');
    assert.throws(() => resolveLayout({ ...base, devBuild: true, packaged: false, env: { GW_CORE_COMMAND: '"sh"' } }), /JSON array/);
  });
});

describe('files for the core', () => {
  it('knows which purposes save and which open', () => {
    assert.equal(filePurpose('master-key-backup', true)?.id, 'master-key-backup');
    assert.equal(filePurpose('master-key-backup', false), null, 'a backup is never opened through saveFile');
    assert.equal(filePurpose('master-key-restore', false)?.id, 'master-key-restore');
    assert.equal(filePurpose('master-key-restore', true), null);
    assert.equal(filePurpose('../../etc/passwd', true), null);
    assert.equal(filePurpose('__proto__', true), null);
    assert.equal(defaultFileName(filePurpose('audit-export', true)!, new Date('2026-10-26T10:00:00Z')), 'gaterook-activity-2026-10-26.jsonl');
  });

  it('accepts only known ids', () => {
    assert.equal(revealKind('logs'), 'logs');
    assert.equal(revealKind('/etc'), null);
    assert.equal(clientId('codex'), 'codex');
    assert.equal(clientId('/Users/x/.codex/config.toml'), null);
    assert.equal(linkId('3f1c2e4a-0000-4000-8000-000000000001'), '3f1c2e4a-0000-4000-8000-000000000001');
    assert.equal(linkId('../x'), null);
  });

  it('opens a client\'s config only when it is a JSON or TOML file, never what a link leads to', { skip: process.platform === 'win32' },
    async () => {
      const t = tempDir('open');
      try {
        writeFileSync(join(t.dir, 'config.toml'), '[mcp_servers]\n');
        writeFileSync(join(t.dir, 'evil.command'), '#!/bin/sh\ntouch /tmp/pwned\n', { mode: 0o755 });
        symlinkSync(join(t.dir, 'evil.command'), join(t.dir, 'mcp.json'));
        symlinkSync(join(t.dir, 'config.toml'), join(t.dir, 'linked.toml'));
        mkdirSync(join(t.dir, 'Calculator.json'));
        assert.equal(await openableConfig(join(t.dir, 'config.toml')), join(t.dir, 'config.toml'));
        assert.equal(await openableConfig(join(t.dir, 'linked.toml')), join(t.dir, 'config.toml'), 'a link to a config: its file');
        assert.equal(await openableConfig(join(t.dir, 'mcp.json')), 'refused', 'a config that is a link to a script');
        assert.equal(await openableConfig(join(t.dir, 'Calculator.json')), 'refused', 'a folder (an app bundle is one)');
        assert.equal(await openableConfig(join(t.dir, 'nothing.json')), 'missing');

        mkdirSync(join(t.dir, 'home', 'logs'), { recursive: true });
        mkdirSync(join(t.dir, 'Some.app'));
        symlinkSync(join(t.dir, 'Some.app'), join(t.dir, 'home', 'backups'));
        assert.ok(await revealableFolder(join(t.dir, 'home', 'logs')));
        assert.ok(!(await revealableFolder(join(t.dir, 'home', 'backups'))), 'a link to an app');
        assert.ok(!(await revealableFolder(join(t.dir, 'config.toml'))));
        assert.ok(!(await revealableFolder(join(t.dir, 'home', 'nothing'))));
      } finally {
        t.remove();
      }
    });
});

describe('updates', () => {
  it('maps the updater\'s events to the UI\'s states', () => {
    assert.deepEqual(updateStateFor({ type: 'checking-for-update' }, '2026-10-26T10:00:00.000Z'),
      { state: 'checking', lastCheckedAt: '2026-10-26T10:00:00.000Z' });
    assert.deepEqual(updateStateFor({ type: 'update-available', version: '0.5.0' }, null), { state: 'available', version: '0.5.0' });
    assert.deepEqual(updateStateFor({ type: 'update-not-available' }, null), { state: 'up-to-date' });
    assert.deepEqual(updateStateFor({ type: 'update-downloaded', version: '0.5.0' }, null), { state: 'downloaded', version: '0.5.0' });
    assert.equal(updateStateFor({ type: 'error', message: 'net::ERR_INTERNET_DISCONNECTED' }, null).state, 'error');
    assert.match(friendlyUpdateError('getaddrinfo ENOTFOUND github.com\n at …'), /Couldn’t reach/);
  });

  // app-update.yml as electron-builder writes it: a signed Windows build names its publisher.
  const feed = 'owner: msrv-ai\nrepo: gaterook\nprovider: github\nupdaterCacheDirName: gaterook-desktop-updater\n';
  const signed = `${feed}publisherName:\n  - GateRook Inc.\n`;

  it('only an installed, signed Windows or macOS build updates itself', () => {
    assert.match(updatesUnavailable(false, 'darwin', feed) ?? '', /development build/);
    assert.match(updatesUnavailable(true, 'linux', signed) ?? '', /Linux/);
    assert.match(updatesUnavailable(true, 'win32', null) ?? '', /no update source/);
    assert.equal(updatesUnavailable(true, 'darwin', feed), null, 'Squirrel.Mac checks the signature itself');
    assert.equal(updatesUnavailable(true, 'win32', signed), null);
    // Without publisherName electron-updater installs a Windows update without checking its signature.
    assert.match(updatesUnavailable(true, 'win32', feed) ?? '', /isn’t signed/);
  });

  it('reads the publisher of app-update.yml', () => {
    assert.ok(namesPublisher(signed));
    assert.ok(namesPublisher(`${feed}publisherName: GateRook Inc.\n`));
    assert.ok(namesPublisher('publisherName:\r\n  - "CN=GateRook Inc., O=GateRook Inc."\r\n'));
    assert.ok(!namesPublisher(feed));
    assert.ok(!namesPublisher(`${feed}publisherName: null\n`));
    assert.ok(!namesPublisher(`${feed}publisherName: []\n`));
    assert.ok(!namesPublisher(`${feed}publisherName:\nprovider: github\n`));
    assert.ok(!namesPublisher(`${feed}# publisherName: GateRook Inc.\n`));
  });

  it('checks once a day while the setting is on', () => {
    assert.ok(checkDue(true, null, 1000));
    assert.ok(!checkDue(false, null, 1000));
    assert.ok(!checkDue(true, 0, 23 * 3_600_000));
    assert.ok(checkDue(true, 0, 24 * 3_600_000));
  });

  it('keeps a person\'s "off" while the core doesn\'t answer', () => {
    assert.equal(autoUpdateWanted(false, true), false, 'the core says now');
    assert.equal(autoUpdateWanted(true, false), true);
    assert.equal(autoUpdateWanted(undefined, false), false, 'stopped or locked core: what it said last');
    assert.equal(autoUpdateWanted(undefined, undefined), true, 'never answered: the core\'s default');
  });
});
