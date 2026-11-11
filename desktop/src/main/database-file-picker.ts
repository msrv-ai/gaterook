import { stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import type { OpenDialogOptions, OpenDialogReturnValue } from 'electron';

/** SQLite's usual file names; any file may be chosen, the core checks that it is a database. */
const DATABASE_EXTENSIONS = ['db', 'sqlite', 'sqlite3', 'db3', 's3db', 'sl3'] as const;

/** Returns only the path explicitly chosen in the native dialog; it never opens the database. */
export async function chooseDatabasePath(
  showDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>,
): Promise<string | null> {
  const result = await showDialog({
    title: 'Choose SQLite database',
    buttonLabel: 'Choose database',
    filters: [
      { name: 'SQLite databases', extensions: [...DATABASE_EXTENSIONS] },
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
