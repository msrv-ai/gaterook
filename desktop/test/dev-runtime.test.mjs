import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, describe, it } from 'node:test';
import { prepareDevRuntime } from '../scripts/dev-runtime.mjs';

function signingIdentifier(bundle) {
  const result = spawnSync('/usr/bin/codesign', ['--display', '--verbose=4', bundle], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const identifier = /^Identifier=(.+)$/m.exec(result.stderr);
  assert.ok(identifier, 'The bundle has a signing identifier.');
  return identifier[1];
}

describe('the macOS development runtime', () => {
  const directory = mkdtempSync(join(tmpdir(), 'gw-dev-runtime-'));
  after(() => rmSync(directory, { recursive: true, force: true }));

  it('leaves Windows and Linux launch paths alone without creating a cache', () => {
    for (const platform of ['win32', 'linux']) {
      const cache = join(directory, platform);
      assert.equal(prepareDevRuntime('/original/electron', cache, { platform }), '/original/electron');
      assert.equal(existsSync(cache), false);
    }
  });

  it('isolates notification identity in a copy, preserves the Electron executable and symlinks, and reuses the cache',
    { skip: process.platform !== 'darwin' }, () => {
      const bundle = join(directory, 'source', 'Electron.app');
      const contents = join(bundle, 'Contents');
      mkdirSync(join(contents, 'MacOS'), { recursive: true });
      mkdirSync(join(contents, 'Resources'), { recursive: true });
      const executable = join(contents, 'MacOS', 'Electron');
      // A small valid Mach-O binary lets codesign exercise the real bundle path without launching an app.
      copyFileSync('/usr/bin/true', executable);
      const info = join(contents, 'Info.plist');
      const metadata = {
        CFBundleExecutable: 'Electron', CFBundleName: 'Electron', CFBundleDisplayName: 'Electron',
        CFBundleIconFile: 'electron.icns',
        CFBundleIdentifier: 'dev.gaterook.runtime-test', CFBundlePackageType: 'APPL', CFBundleVersion: '1.0',
      };
      writeFileSync(info, JSON.stringify(metadata));
      assert.equal(spawnSync('/usr/bin/plutil', ['-convert', 'xml1', info]).status, 0);
      writeFileSync(join(contents, 'Resources', 'asset.txt'), 'original asset');
      writeFileSync(join(contents, 'Resources', 'electron.icns'), 'original runtime icon');
      symlinkSync('asset.txt', join(contents, 'Resources', 'asset-link'));
      assert.equal(spawnSync('/usr/bin/codesign', ['--force', '--sign', '-', '--identifier',
        metadata.CFBundleIdentifier, bundle]).status, 0);
      const originalIdentifier = signingIdentifier(bundle);
      assert.equal(originalIdentifier, metadata.CFBundleIdentifier);
      const originalExecutable = readFileSync(executable);
      const originalInfo = readFileSync(info);
      const iconPath = join(directory, 'canonical.icns');
      const originalIcon = Buffer.from('icns-fixture-version-one');
      writeFileSync(iconPath, originalIcon);
      const cache = join(directory, 'cache');
      const result = prepareDevRuntime(executable, cache, { iconPath });
      assert.equal(result.endsWith('/GateRook.app/Contents/MacOS/Electron'), true);
      assert.notEqual(result, executable);
      const copiedContents = dirname(dirname(result));
      const copiedInfo = JSON.parse(spawnSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-',
        join(copiedContents, 'Info.plist')], { encoding: 'utf8' }).stdout);
      assert.deepEqual(copiedInfo, { ...metadata, CFBundleName: 'GateRook', CFBundleDisplayName: 'GateRook',
        CFBundleIconFile: 'GateRook.icns', CFBundleIdentifier: 'ai.msrv.gaterook.desktop.dev',
        NSUserNotificationAlertStyle: 'alert' });
      assert.equal(signingIdentifier(join(copiedContents, '..')), copiedInfo.CFBundleIdentifier);
      assert.equal(signingIdentifier(bundle), originalIdentifier, 'The source signing identity stays unchanged.');
      assert.deepEqual(readFileSync(info), originalInfo);
      assert.deepEqual(readFileSync(executable), originalExecutable);
      assert.equal(readFileSync(join(contents, 'Resources', 'electron.icns'), 'utf8'), 'original runtime icon');
      assert.deepEqual(readFileSync(iconPath), originalIcon);
      assert.deepEqual(readFileSync(join(copiedContents, 'Resources', 'GateRook.icns')), originalIcon);
      assert.equal(readFileSync(join(copiedContents, 'Resources', 'asset-link'), 'utf8'), 'original asset');
      assert.equal(spawnSync('/usr/bin/codesign', ['--verify', join(copiedContents, '..')]).status, 0);
      const mtime = statSync(result).mtimeMs;
      assert.equal(prepareDevRuntime(executable, cache, { iconPath }), result);
      assert.equal(statSync(result).mtimeMs, mtime);
      assert.equal(readdirSync(cache).length, 1);

      // A dependency update makes another runtime without replacing a potentially running old copy.
      metadata.CFBundleVersion = '2.0';
      writeFileSync(info, JSON.stringify(metadata));
      assert.equal(spawnSync('/usr/bin/plutil', ['-convert', 'xml1', info]).status, 0);
      const updated = prepareDevRuntime(executable, cache, { iconPath });
      assert.notEqual(updated, result);
      assert.equal(existsSync(result), true);
      assert.equal(readdirSync(cache).length, 2);

      // Changing artwork refreshes the icon without overwriting a running bundle or the source runtime.
      const revisedIcon = Buffer.from('icns-fixture-version-two');
      writeFileSync(iconPath, revisedIcon);
      const revised = prepareDevRuntime(executable, cache, { iconPath });
      assert.notEqual(revised, updated);
      assert.deepEqual(readFileSync(join(dirname(dirname(revised)), 'Resources', 'GateRook.icns')), revisedIcon);
      assert.deepEqual(readFileSync(join(copiedContents, 'Resources', 'GateRook.icns')), originalIcon);
      assert.equal(readFileSync(join(contents, 'Resources', 'electron.icns'), 'utf8'), 'original runtime icon');
      assert.equal(existsSync(updated), true);
      assert.equal(readdirSync(cache).length, 3);
    });
});
