import assert from 'node:assert/strict';
import { readFileSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { installId, redactInstallId } from '../src/main/install-id.ts';
import { tempDir } from './helpers.ts';

describe('installation identity', () => {
  it('creates 16 alphanumeric characters once and keeps them across restarts', (t) => {
    const temp = tempDir('install-id');
    t.after(() => temp.remove());
    const profile = join(temp.dir, 'profile');
    const first = installId(profile);
    assert.match(first, /^[A-Za-z0-9]{16}$/);
    assert.equal(installId(profile), first);
    assert.equal(readFileSync(join(profile, 'install-id'), 'utf8'), `${first}\n`);
    if (process.platform !== 'win32') assert.equal(statSync(join(profile, 'install-id')).mode & 0o777, 0o600);
    const other = installId(join(temp.dir, 'other-profile'));
    assert.notEqual(other, first);
  });

  it('does not silently replace a corrupt identity or follow a symbolic link', (t) => {
    const temp = tempDir('invalid-install-id');
    t.after(() => temp.remove());
    writeFileSync(join(temp.dir, 'install-id'), 'invalid');
    assert.throws(() => installId(temp.dir), /Invalid installation identity/);
    assert.equal(readFileSync(join(temp.dir, 'install-id'), 'utf8'), 'invalid');
    if (process.platform !== 'win32') {
      const profile = tempDir('linked-install-id');
      t.after(() => profile.remove());
      symlinkSync(join(temp.dir, 'install-id'), join(profile.dir, 'install-id'));
      assert.throws(() => installId(profile.dir), /Invalid installation identity/);
    }
  });

  it('redacts query parameters and any occurrence of the known identity', () => {
    const id = '0123456789AbCdEf';
    assert.equal(redactInstallId(`GET https://gaterook.com/api/updates/latest.yml?install_id=${id}&noCache=1\n${id}`, id),
      'GET https://gaterook.com/api/updates/latest.yml?install_id=[redacted]&noCache=1\n[redacted]');
    assert.equal(redactInstallId(`install_id%3D${id}`), 'install_id%3D[redacted]');
  });
});
