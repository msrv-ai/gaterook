import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, describe, it } from 'node:test';
import { prepareDevRuntime } from '../scripts/dev-runtime.mjs';

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

  it('brands only a copy, preserves executable identity and symlinks, and reuses the cached runtime',
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
        CFBundleIdentifier: 'dev.gaterook.runtime-test', CFBundlePackageType: 'APPL', CFBundleVersion: '1.0',
      };
      writeFileSync(info, JSON.stringify(metadata));
      assert.equal(spawnSync('/usr/bin/plutil', ['-convert', 'xml1', info]).status, 0);
      writeFileSync(join(contents, 'Resources', 'asset.txt'), 'original asset');
      symlinkSync('asset.txt', join(contents, 'Resources', 'asset-link'));
      const originalExecutable = readFileSync(executable);
      const originalInfo = readFileSync(info);
      const cache = join(directory, 'cache');
      const result = prepareDevRuntime(executable, cache);
      assert.equal(result.endsWith('/GateRook.app/Contents/MacOS/Electron'), true);
      assert.notEqual(result, executable);
      const copiedContents = dirname(dirname(result));
      const copiedInfo = JSON.parse(spawnSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-',
        join(copiedContents, 'Info.plist')], { encoding: 'utf8' }).stdout);
      assert.deepEqual(copiedInfo, { ...metadata, CFBundleName: 'GateRook', CFBundleDisplayName: 'GateRook' });
      assert.deepEqual(readFileSync(info), originalInfo);
      assert.deepEqual(readFileSync(executable), originalExecutable);
      assert.equal(readFileSync(join(copiedContents, 'Resources', 'asset-link'), 'utf8'), 'original asset');
      assert.equal(spawnSync('/usr/bin/codesign', ['--verify', join(copiedContents, '..')]).status, 0);
      const mtime = statSync(result).mtimeMs;
      assert.equal(prepareDevRuntime(executable, cache), result);
      assert.equal(statSync(result).mtimeMs, mtime);
      assert.equal(readdirSync(cache).length, 1);

      // A dependency update makes another runtime without replacing a potentially running old copy.
      metadata.CFBundleVersion = '2.0';
      writeFileSync(info, JSON.stringify(metadata));
      assert.equal(spawnSync('/usr/bin/plutil', ['-convert', 'xml1', info]).status, 0);
      const updated = prepareDevRuntime(executable, cache);
      assert.notEqual(updated, result);
      assert.equal(existsSync(result), true);
      assert.equal(readdirSync(cache).length, 2);
    });
});
