import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { choosePrivateKeyPath } from '../src/main/private-key-picker.ts';
import { tempDir } from './helpers.ts';

describe('private key path picker', () => {
  it('chooses one file including hidden or extensionless keys and returns only its absolute path', async () => {
    const t = tempDir('key-picker');
    try {
      const path = join(t.dir, 'id_ed25519');
      writeFileSync(path, 'not a key: selection must not parse or import its contents');
      const result = await choosePrivateKeyPath(async (options) => {
        assert.deepEqual(options.properties, ['openFile', 'showHiddenFiles']);
        assert.equal(options.title, 'Choose SSH private key');
        assert.equal(options.filters, undefined, 'SSH key files need not have an extension');
        return { canceled: false, filePaths: [path] };
      });
      assert.equal(result, path);
    } finally { t.remove(); }
  });

  it('does not return any path when the person cancels or no file was chosen', async () => {
    assert.equal(await choosePrivateKeyPath(async () => ({ canceled: true, filePaths: ['/not/selected'] })), null);
    assert.equal(await choosePrivateKeyPath(async () => ({ canceled: false, filePaths: [] })), null);
  });

  it('refuses relative paths, missing files and folders', async () => {
    const t = tempDir('key-picker');
    try {
      for (const path of ['id_ed25519', join(t.dir, 'missing'), t.dir]) {
        await assert.rejects(choosePrivateKeyPath(async () => ({ canceled: false, filePaths: [path] })), /existing private key file/);
      }
    } finally { t.remove(); }
  });

  it('reports dialog errors instead of returning a bogus path', async () => {
    await assert.rejects(choosePrivateKeyPath(async () => { throw new Error('Dialog unavailable'); }), /Dialog unavailable/);
  });
});
