import assert from 'node:assert/strict';
import { mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { chooseVaultTokenPath } from '../src/main/vault-token-picker.ts';
import { tempDir } from './helpers.ts';

describe('Vault token file picker', () => {
  it('grants the chosen file to the core and returns only the grant token and its path', async () => {
    const t = tempDir('vault-token-picker');
    try {
      const path = join(t.dir, '.vault-agent-token');
      writeFileSync(path, 'not a real token: the picker must not read it');
      const granted: string[] = [];
      const result = await chooseVaultTokenPath(async (options) => {
        assert.deepEqual(options.properties, ['openFile', 'showHiddenFiles']);
        assert.equal(options.title, 'Choose Vault token file');
        assert.equal(options.filters, undefined, 'token files rarely have an extension');
        return { canceled: false, filePaths: [path] };
      }, async (chosen) => { granted.push(chosen); return 'ft_granted'; });
      assert.deepEqual(result, { token: 'ft_granted', path });
      assert.deepEqual(granted, [path]);
    } finally { t.remove(); }
  });

  it('grants nothing when the person cancels or no file was chosen', async () => {
    const grant = async (): Promise<string> => { throw new Error('nothing may be granted'); };
    assert.equal(await chooseVaultTokenPath(async () => ({ canceled: true, filePaths: ['/not/selected'] }), grant), null);
    assert.equal(await chooseVaultTokenPath(async () => ({ canceled: false, filePaths: [] }), grant), null);
  });

  it('refuses relative paths, missing files, folders and links before granting', async () => {
    const t = tempDir('vault-token-picker');
    try {
      const target = join(t.dir, 'token');
      writeFileSync(target, 'token');
      const link = join(t.dir, 'link');
      symlinkSync(target, link);
      const folder = join(t.dir, 'folder');
      mkdirSync(folder);
      const grant = async (): Promise<string> => { throw new Error('nothing may be granted'); };
      for (const path of ['token', join(t.dir, 'missing'), folder, link]) {
        await assert.rejects(chooseVaultTokenPath(async () => ({ canceled: false, filePaths: [path] }), grant), /holds the token itself/);
      }
    } finally { t.remove(); }
  });

  it('reports a refused grant instead of returning a path the core does not know', async () => {
    const t = tempDir('vault-token-picker');
    try {
      const path = join(t.dir, 'token');
      writeFileSync(path, 'token');
      await assert.rejects(chooseVaultTokenPath(async () => ({ canceled: false, filePaths: [path] }),
        async () => { throw new Error('The core answered HTTP 422'); }), /HTTP 422/);
    } finally { t.remove(); }
  });
});
