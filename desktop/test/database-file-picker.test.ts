import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { chooseDatabasePath, databaseKind } from '../src/main/database-file-picker.ts';
import { tempDir } from './helpers.ts';

describe('Database path picker', () => {
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

  it('offers DuckDB files for a DuckDB connection', async () => {
    const t = tempDir('database-picker');
    try {
      const path = join(t.dir, 'warehouse.duckdb');
      writeFileSync(path, 'not a database: selection must not open or read it');
      const result = await chooseDatabasePath(async (options) => {
        assert.equal(options.title, 'Choose DuckDB database');
        assert.deepEqual(options.filters?.map((f) => f.name), ['DuckDB databases', 'All files']);
        assert.ok(options.filters?.[0]?.extensions.includes('duckdb'));
        return { canceled: false, filePaths: [path] };
      }, 'duckdb');
      assert.equal(result, path);
    } finally { t.remove(); }
  });

  it('offers Access files for an Access connection', async () => {
    const t = tempDir('database-picker');
    try {
      const path = join(t.dir, 'orders.accdb');
      writeFileSync(path, 'not a database: selection must not open or read it');
      const result = await chooseDatabasePath(async (options) => {
        assert.equal(options.title, 'Choose Access database');
        assert.deepEqual(options.filters?.[0]?.extensions, ['accdb', 'mdb']);
        return { canceled: false, filePaths: [path] };
      }, 'access');
      assert.equal(result, path);
    } finally { t.remove(); }
  });

  it('takes no kind or one known kind from the renderer', () => {
    assert.equal(databaseKind([]), 'sqlite');
    assert.equal(databaseKind(['sqlite']), 'sqlite');
    assert.equal(databaseKind(['duckdb']), 'duckdb');
    assert.equal(databaseKind(['access']), 'access');
    for (const args of [['postgres'], ['duckdb', 'x'], [{ kind: 'duckdb' }], [null]]) {
      assert.throws(() => databaseKind(args), /Unknown database kind/);
    }
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
