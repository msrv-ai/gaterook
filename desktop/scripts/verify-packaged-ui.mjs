import { lstat, readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { readAsar } from 'app-builder-lib/out/asar/asar.js';

/** Verify the complete staged renderer before accepting a Windows package, including lazy chunks and assets. */
export async function verifyPackagedUi(resources, stagedUi) {
  const archive = await readAsar(join(resources, 'app.asar'));
  const index = join(stagedUi, 'index.html');
  if (!(await lstat(index)).isFile() || (await readFile(index)).length === 0) {
    throw new Error('The staged UI needs a nonempty index.html');
  }

  async function check(directory, segments = []) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const parts = [...segments, entry.name];
      const source = join(directory, entry.name);
      if (entry.isDirectory()) {
        await check(source, parts);
        continue;
      }
      const name = join('ui', ...parts);
      if (!entry.isFile()) throw new Error(`The staged UI contains a nonregular file: ${name}`);
      let packaged;
      try {
        const node = archive.getFile(name, false);
        if (!node || node.files || node.link || node.unpacked) throw new Error('Not a packed regular file');
        packaged = await archive.readFile(name);
      } catch (cause) {
        throw new Error(`The packaged UI is missing ${name}`, { cause });
      }
      if (!packaged.equals(await readFile(source))) {
        throw new Error(`The packaged UI differs from the staged file: ${name}`);
      }
    }
  }

  await check(stagedUi);
}
