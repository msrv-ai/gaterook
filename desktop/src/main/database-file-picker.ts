import { stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import type { OpenDialogOptions, OpenDialogReturnValue } from 'electron';

/** The engines whose database file a connection names: SQLite (built in) and DuckDB (a plugin). */
export type DatabaseKind = 'sqlite' | 'duckdb';

/** Each engine's usual file names; any file may be chosen, the core or the plugin checks that it is a database. */
const DATABASES: Record<DatabaseKind, { readonly name: string; readonly extensions: readonly string[] }> = {
  sqlite: { name: 'SQLite', extensions: ['db', 'sqlite', 'sqlite3', 'db3', 's3db', 'sl3'] },
  duckdb: { name: 'DuckDB', extensions: ['duckdb', 'ddb', 'db'] },
};

/** The kind an IPC call names: none is SQLite, the picker's first kind; anything else is refused. */
export function databaseKind(args: readonly unknown[]): DatabaseKind {
  if (args.length === 0) return 'sqlite';
  if (args.length === 1 && (args[0] === 'sqlite' || args[0] === 'duckdb')) return args[0];
  throw new Error('Unknown database kind.');
}

/** Returns only the path explicitly chosen in the native dialog; it never opens the database. */
export async function chooseDatabasePath(
  showDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>,
  kind: DatabaseKind = 'sqlite',
): Promise<string | null> {
  const engine = DATABASES[kind];
  const result = await showDialog({
    title: `Choose ${engine.name} database`,
    buttonLabel: 'Choose database',
    filters: [
      { name: `${engine.name} databases`, extensions: [...engine.extensions] },
      { name: 'All files', extensions: ['*'] },
    ],
    properties: ['openFile', 'showHiddenFiles'],
  });
  if (result.canceled || !result.filePaths.length) return null;
  const path = result.filePaths[0]!;
  if (!isAbsolute(path) || !(await stat(path).catch(() => null))?.isFile()) {
    throw new Error('Choose an existing database file, not a folder.');
  }
  return path;
}
