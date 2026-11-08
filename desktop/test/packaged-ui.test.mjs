import assert from 'node:assert/strict';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';
import { createPackage } from '@electron/asar';
import { getFileMatchers } from 'app-builder-lib/out/fileMatcher.js';
import { computeFileSets, getDestinationPath } from 'app-builder-lib/out/util/appFileCopier.js';
import { getConfig } from 'app-builder-lib/out/util/config/config.js';
import { load } from 'js-yaml';
import afterPack from '../scripts/after-pack.mjs';
import { verifyPackagedUi } from '../scripts/verify-packaged-ui.mjs';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const yaml = await readFile(new URL('../electron-builder.yml', import.meta.url), 'utf8');
const renderer = {
  'index.html': '<!doctype html><gw-root></gw-root><script src="main-ABC.js" type="module"></script>',
  'main-ABC.js': 'import("./chunk-XYZ.js");',
  'chunk-XYZ.js': 'export const ready = true;',
  'styles-ABC.css': 'body { color: black; }',
  'assets/fonts/example.woff2': 'font fixture',
  'assets/theme-boot.js': 'document.documentElement.dataset.theme = "light";',
};

async function fixture(t) {
  const scratch = join(repository, 'ai');
  await mkdir(scratch, { recursive: true });
  const directory = await mkdtemp(join(scratch, 'packaged-ui-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const stagedUi = join(directory, 'stage', 'ui');
  const resources = join(directory, 'out', 'resources');
  const payload = join(directory, 'payload');
  for (const [name, content] of Object.entries(renderer)) {
    const file = join(stagedUi, name);
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, content);
  }
  await mkdir(resources, { recursive: true });
  await mkdir(payload);
  await writeFile(join(payload, 'package.json'), '{"name":"fixture","main":"dist/main.cjs"}');
  const context = {
    electronPlatformName: 'win32', appOutDir: join(directory, 'out'),
    packager: { info: { appDir: directory }, getResourcesDir: () => resources },
  };
  return {
    directory, stagedUi, resources, payload, context,
    pack: () => createPackage(payload, join(resources, 'app.asar')),
  };
}

describe('Windows renderer packaging', () => {
  for (const mode of ['configuration file', 'configuration file merged with API options']) {
    it(`keeps the complete UI with ${mode}`, async (t) => {
      const f = await fixture(t);
      await writeFile(join(f.directory, 'electron-builder.yml'), yaml);
      await writeFile(join(f.directory, 'package.json'), '{"name":"fixture","version":"1.0.0"}');
      const config = await getConfig(f.directory, null, mode.includes('API') ? load(yaml) : null);
      const matchers = getFileMatchers(config, 'files', f.payload, {
        macroExpander: value => value, customBuildOptions: config.win,
        globalOutDir: join(f.directory, 'out'), defaultSrc: f.directory,
      });
      const sets = await computeFileSets(matchers, null, { info: { appDir: f.directory } }, false);
      for (const set of sets) {
        for (const source of set.files) {
          const target = getDestinationPath(source, set);
          await mkdir(dirname(target), { recursive: true });
          await cp(source, target);
        }
      }
      await f.pack();
      await afterPack(f.context);
      for (const [name, content] of Object.entries(renderer)) {
        assert.equal(await readFile(join(f.payload, 'ui', name), 'utf8'), content);
      }
    });
  }

  it('blocks the actual empty-renderer failure even when desktop files are present', async (t) => {
    const f = await fixture(t);
    await mkdir(join(f.payload, 'dist'));
    await writeFile(join(f.payload, 'dist', 'main.cjs'), 'main process');
    await writeFile(join(f.payload, 'dist', 'preload.cjs'), 'preload');
    await f.pack();
    await assert.rejects(afterPack(f.context), /packaged UI is missing/);
  });

  for (const name of ['index.html', 'chunk-XYZ.js', 'assets/fonts/example.woff2']) {
    it(`blocks a package missing ${name}`, async (t) => {
      const f = await fixture(t);
      await cp(f.stagedUi, join(f.payload, 'ui'), { recursive: true });
      await rm(join(f.payload, 'ui', name));
      await f.pack();
      await assert.rejects(afterPack(f.context), /packaged UI is missing/);
    });
  }

  it('blocks a stale renderer file', async (t) => {
    const f = await fixture(t);
    await cp(f.stagedUi, join(f.payload, 'ui'), { recursive: true });
    await writeFile(join(f.payload, 'ui', 'main-ABC.js'), 'old renderer');
    await f.pack();
    await assert.rejects(afterPack(f.context), /packaged UI differs.*main-ABC\.js/);
  });

  it('cannot accept an empty staging directory', async (t) => {
    const f = await fixture(t);
    await f.pack();
    await rm(f.stagedUi, { recursive: true });
    await mkdir(f.stagedUi);
    await assert.rejects(verifyPackagedUi(f.resources, f.stagedUi), /ENOENT/);
  });
});
