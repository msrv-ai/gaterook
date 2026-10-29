import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Platform } from 'app-builder-lib';
import { resolveFunction } from 'app-builder-lib/out/util/resolve.js';
import { Arch } from 'builder-util';
import { load } from 'js-yaml';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const builderConfig = load(readFileSync(new URL('../electron-builder.yml', import.meta.url), 'utf8'));
const afterPack = await resolveFunction('module', builderConfig.afterPack, 'afterPack', repository);

function fixture(t, { platform = 'darwin', config = builderConfig, platformOptions = config.mac } = {}) {
  const scratch = join(repository, 'ai');
  mkdirSync(scratch, { recursive: true });
  const directory = mkdtempSync(join(scratch, 'after-pack-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const resources = join(directory, 'GateRook.app', 'Contents', 'Resources');
  mkdirSync(resources, { recursive: true });
  const appInfo = { updaterCacheDirName: 'gaterook-desktop-updater', channel: null };
  const packager = {
    platform: Platform.MAC,
    config,
    platformSpecificBuildOptions: platformOptions,
    appInfo,
    info: { config, appInfo },
    expandMacro: (value) => value,
    getResourcesDir: (appOutDir) => {
      assert.equal(appOutDir, directory);
      return resources;
    },
  };
  return {
    context: { electronPlatformName: platform, packager, arch: Arch.arm64, appOutDir: directory, targets: [] },
    metadata: join(resources, 'app-update.yml'),
  };
}

describe('macOS update metadata packaging', () => {
  it('includes the configured feed and updater cache in directory-only packages', async (t) => {
    const f = fixture(t);
    await afterPack(f.context);
    assert.deepEqual(load(readFileSync(f.metadata, 'utf8')), {
      provider: 'github', owner: 'msrv-ai', repo: 'gaterook', updaterCacheDirName: 'gaterook-desktop-updater',
    });
  });

  it('uses the same metadata for directory-only and installer targets', async (t) => {
    const directory = fixture(t);
    const installer = fixture(t);
    installer.context.targets = [{ name: 'dmg' }, { name: 'zip' }];
    await afterPack(directory.context);
    await afterPack(installer.context);
    assert.equal(readFileSync(directory.metadata, 'utf8'), readFileSync(installer.metadata, 'utf8'));
  });

  it('resolves platform publish overrides through electron-builder', async (t) => {
    const f = fixture(t, { platformOptions: { publish: { provider: 'generic', url: 'https://updates.example.test/' } } });
    await afterPack(f.context);
    assert.deepEqual(load(readFileSync(f.metadata, 'utf8')), {
      provider: 'generic', url: 'https://updates.example.test/', updaterCacheDirName: 'gaterook-desktop-updater',
    });
  });

  it('respects explicitly disabled update sources', async (t) => {
    for (const options of [{ config: { ...builderConfig, publish: null } }, { platformOptions: { publish: null } }]) {
      const f = fixture(t, options);
      await afterPack(f.context);
      assert.equal(existsSync(f.metadata), false);
    }
  });

  it('leaves Windows, Linux and Mac App Store packaging to electron-builder', async () => {
    for (const electronPlatformName of ['win32', 'linux', 'mas']) {
      await afterPack({ electronPlatformName });
    }
  });

  it('fails packaging when the update source is invalid', async (t) => {
    const f = fixture(t, { platformOptions: { publish: { provider: 'generic' } } });
    await assert.rejects(afterPack(f.context), /Please specify "url"/);
    assert.equal(existsSync(f.metadata), false);
  });
});
