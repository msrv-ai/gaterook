import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { chooseDatabasePath } from '../src/main/database-file-picker.ts';
import { tempDir } from './helpers.ts';

describe('SQLite database path picker', () => {
  it('chooses one file, hidden folders included, and returns only its absolute path', async () => {
    const t = tempDir('database-picker');
    try {
      const path = join(t.dir, 'development.sqlite3');
      writeFileSync(path, 'not a database: selection must not open or read it');
      const result = await chooseDatabasePath(async (options) => {
        assert.deepEqual(options.properties, ['openFile', 'showHiddenFiles']);
        assert.equal(options.title, 'Choose SQLite database');
        assert.deepEqual(options.filters?.map((f) => f.name), ['SQLite databases', 'All files']);
        assert.ok(options.filters?.[0]?.extensions.includes('sqlite3'));
        return { canceled: false, filePaths: [path] };
      });
      assert.equal(result, path);
    } finally { t.remove(); }
  });

  it('does not return any path when the person cancels or no file was chosen', async () => {
    assert.equal(await chooseDatabasePath(async () => ({ canceled: true, filePaths: ['/not/selected.db'] })), null);
    assert.equal(await chooseDatabasePath(async () => ({ canceled: false, filePaths: [] })), null);
  });

  it('refuses relative paths, missing files and folders', async () => {
    const t = tempDir('database-picker');
    try {
      for (const path of ['shop.db', join(t.dir, 'missing.db'), t.dir]) {
        await assert.rejects(chooseDatabasePath(async () => ({ canceled: false, filePaths: [path] })), /existing database file/);
      }
    } finally { t.remove(); }
  });
});
