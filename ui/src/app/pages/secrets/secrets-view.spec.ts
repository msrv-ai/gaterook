import { SecretStore } from '../../core/api/secrets.api';
import { ENVS, NOW, kitMasterKey, kitSecrets, kitShortLived, kitStores, local, reference } from './secrets.fixtures';
import {
  DEFAULT_SORT,
  RowContext,
  backedUpToast,
  backupFileName,
  backupLead,
  connectedToast,
  countText,
  disconnectLead,
  disconnectedToast,
  filterRows,
  keyAlerts,
  keyFileName,
  keyFolder,
  createdRow,
  keysText,
  osProtectionText,
  ownerCell,
  refProblem,
  refTestLine,
  refTestToast,
  replaceFailedText,
  replaceHint,
  replacedToast,
  rotateLead,
  rotatedText,
  rotationView,
  rowActions,
  shortLivedGroups,
  shortLivedSummary,
  sortRows,
  sourceCell,
  sourceGroup,
  storeAnchors,
  storeDescription,
  storeIssuesCount,
  storeSignIn,
  storeStatus,
  storeTestToast,
  storesSummary,
  subjectOf,
  versionCell,
  waitingFor,
  waitingText,
} from './secrets-view';

const ctx: RowContext = { envOf: ENVS, stores: kitStores() };
const row = (name: string) => kitSecrets().find((r) => r.name === name)!;
const text = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');

describe('stored secrets: filters, sort, count', () => {
  it('searches the secret and its Used by cell, as the kit does', () => {
    expect(filterRows(kitSecrets(), { q: 'billing', source: '' }, ctx).map((r) => r.name)).toEqual(['billing-api/token', 'billing-api/webhook-secret']);
    // The connection name and the owner's words ("Vault store sign-in", "MCP server") are searchable too.
    expect(filterRows(kitSecrets(), { q: 'sign-in', source: '' }, ctx).map((r) => r.name)).toEqual(['vault/approle-secret-id']);
    expect(filterRows(kitSecrets(), { q: 'MCP SERVER', source: '' }, ctx).map((r) => r.name)).toEqual(['github-mcp/token']);
    expect(filterRows(kitSecrets(), { q: '  stage ', source: '' }, ctx).map((r) => r.name)).toEqual([
      'aws-stage/access-key',
      'billing-api/token',
      'billing-api/webhook-secret',
      'stage-db/password',
    ]);
  });

  it('filters by source: encrypted here, or the store kind (an unbound reference by its URI)', () => {
    expect(filterRows(kitSecrets(), { q: '', source: 'local' }, ctx)).toHaveLength(8);
    expect(filterRows(kitSecrets(), { q: '', source: 'vault' }, ctx).map((r) => r.name)).toEqual(['stage-db/password']);
    expect(filterRows(kitSecrets(), { q: '', source: 'aws-sm' }, ctx).map((r) => r.name)).toEqual(['billing-api/webhook-secret']);
    expect(sourceGroup(reference('x', 'y', 'reference', 'gcp-sm://projects/p/secrets/s'))).toBe('gcp-sm');
    expect(filterRows(kitSecrets(), { q: 'dev-db', source: 'vault' }, ctx)).toEqual([]);
  });

  it('sorts by name both ways, and by Last rotated with never-rotated as the oldest', () => {
    const names = (s: { key: 'name' | 'rotated'; direction: 'ascending' | 'descending' }) => sortRows(kitSecrets(), s).map((r) => r.name);
    expect(names(DEFAULT_SORT)[0]).toBe('app-logs/token');
    expect(names({ key: 'name', direction: 'descending' })[0]).toBe('vault/approle-secret-id');
    const oldest = names({ key: 'rotated', direction: 'ascending' });
    expect(oldest.slice(0, 2)).toEqual(['billing-api/webhook-secret', 'cache-redis/password']);
    expect(names({ key: 'rotated', direction: 'descending' })[0]).toBe('dev-db/password');
    expect(names({ key: 'rotated', direction: 'descending' }).at(-1)).toBe('billing-api/webhook-secret');
  });

  it('counts like the kit: "11 secrets", "3 of 11 secrets", "1 secret"', () => {
    expect(countText(11, 11)).toBe('11 secrets');
    expect(countText(3, 11)).toBe('3 of 11 secrets');
    expect(countText(1, 1)).toBe('1 secret');
    expect(countText(0, 1)).toBe('0 of 1 secret');
  });
});

describe('stored secrets: cells', () => {
  it('Used by: a connection with its env, an MCP server, a store sign-in, a setting', () => {
    expect(ownerCell(row('dev-db/password'), ctx)).toMatchObject({ kind: 'connection', label: 'dev-db', mono: true, env: 'dev', link: null });
    expect(ownerCell(row('github-mcp/token'), ctx)).toMatchObject({ label: 'github-mcp', caption: 'MCP server', link: { route: '/connectors', fragment: 'mcp-servers' } });
    expect(ownerCell(row('vault/approle-secret-id'), ctx)).toMatchObject({ label: 'Vault store', mono: false, caption: 'sign-in', link: { route: null, fragment: 'store-vault' } });
    expect(ownerCell(local('tg/token', 'telegram', 1, null, { ownerKind: 'notification' }), ctx)).toMatchObject({ label: 'telegram', caption: 'notifications', link: { route: '/settings/notifications' } });
    // A connection Connections didn't name: no marker rather than a guess.
    expect(ownerCell(local('x/password', 'unknown', 1, null), ctx).env).toBeNull();
    expect(ownerCell(local('t/x', 'tunnel', 1, null, { ownerKind: 'tunnel' }), ctx).link).toBeNull();
  });

  it('Source: encrypted here, the store, and what is wrong in words with a status', () => {
    expect(sourceCell(row('dev-db/password'))).toEqual({ label: 'Encrypted here', icon: 'lock-keyhole', tone: null });
    expect(sourceCell(row('stage-db/password'))).toEqual({ label: 'Vault', icon: 'vault', tone: null });
    expect(sourceCell(row('billing-api/token'))).toEqual({ label: 'GCP SM', icon: 'cloud', tone: null });
    expect(sourceCell(row('billing-api/webhook-secret'))).toEqual({ label: 'AWS SM · not connected', icon: null, tone: 'danger' });
    expect(sourceCell(local('a/b', 'a', null, null, { state: 'needs-value' }))).toMatchObject({ label: 'Encrypted here · needs a value', tone: 'warning' });
    expect(sourceCell(local('a/b', 'a', 3, null, { state: 'testing' }))).toMatchObject({ label: 'Encrypted here · new value failed its test', tone: 'warning' });
    expect(sourceCell(reference('a/b', 'a', 'vault', 'vault://kv/a', { state: 'changed-outside' }))).toMatchObject({ label: 'Vault · changed outside GateRook', tone: 'warning' });
  });

  it('Version: v3 here, the store’s version of a reference, Unknown or None', () => {
    expect(versionCell(row('dev-db/password'))).toEqual({ text: 'v3', mono: true });
    expect(versionCell(row('stage-db/password'))).toEqual({ text: 'v7', mono: true });
    expect(versionCell(row('billing-api/webhook-secret'))).toEqual({ text: 'Unknown', mono: false });
    expect(versionCell(local('a/b', 'a', null, null))).toEqual({ text: 'None', mono: false });
    expect(versionCell(reference('a/b', 'a', 'aws-sm', 'aws-sm://r/n', { remoteVersion: 'a1b2c3d4-e5f6' }))).toEqual({ text: 'a1b2c3d4-e5f6', mono: true });
  });

  it('Last rotated: relative up to six weeks, the date after, Never and Unknown', () => {
    expect(rotatedText(row('app-logs/token'), NOW)).toBe('41 days ago');
    expect(rotatedText(row('dev-db/password'), NOW)).toBe('2 days ago');
    expect(rotatedText(row('cache-redis/password'), NOW)).toBe('Jul 14');
    expect(rotatedText(local('a/b', 'a', 1, null), NOW)).toBe('Never');
    expect(rotatedText(row('billing-api/webhook-secret'), NOW)).toBe('Unknown');
  });

  it('actions: Rotate only with a reason when the source can’t, Test for bound references, Connect store for unbound ones', () => {
    expect(rowActions(row('dev-db/password'), ctx)).toEqual({ rotate: { available: true, reason: null }, replace: 'value', test: false, connectStore: null, usage: 'connection:dev-db' });
    expect(rowActions(row('app-logs/token'), ctx).rotate).toEqual({ available: false, reason: "This connector can't issue a new value for itself. Replace it with a new value." });
    expect(rowActions(row('stage-db/password'), ctx)).toMatchObject({ rotate: null, replace: 'reference', test: true, connectStore: null });
    expect(rowActions(row('billing-api/webhook-secret'), ctx)).toMatchObject({
      test: false,
      connectStore: { kind: 'aws-sm', title: 'AWS Secrets Manager', anchor: 'store-aws' },
      usage: 'connection:billing-api',
    });
    expect(rowActions(row('vault/approle-secret-id'), ctx).usage).toBe('connection:prod-replica connection:stage-db');
    expect(rowActions(row('github-mcp/token'), ctx).usage).toBe('connection:github-mcp');
    expect(rowActions(local('t/x', 'tunnel', 1, null, { ownerKind: 'tunnel' }), ctx).usage).toBeNull();
  });

  it('store anchors: the kit’s #store-vault for the first of a kind, a name for the next', () => {
    const extra: SecretStore = { ...kitStores()[0], id: 'st-vault-2', name: 'vault-eu' };
    const anchors = storeAnchors([...kitStores(), extra]);
    expect(anchors.get('vault')).toBe('store-vault');
    expect(anchors.get('gcp-acme-stage')).toBe('store-gcp');
    expect(anchors.get('vault-eu')).toBe('store-vault-vault-eu');
  });
});

describe('row dialogs', () => {
  it('names who is tested, the versions, and whether prod needs a comment', () => {
    const dev = subjectOf(row('dev-db/password'), ctx);
    expect(dev).toMatchObject({ name: 'dev-db/password', usedBy: 'dev-db', version: 3, prod: false, testable: true });
    expect(replaceHint(dev)).toBe('Saving creates version 4. Version 3 stays in use until then.');
    expect(rotateLead(dev)).toBe('The connector sets a new value at the source, stores it as version 4 and tests dev-db. Version 3 keeps working until the test passes.');
    const prod = subjectOf(local('prod-replica/password', 'prod-replica', 1, null), ctx);
    expect(prod.prod).toBe(true);
    // A store's sign-in is tested against the store: the kit names its host.
    expect(subjectOf(row('vault/approle-secret-id'), ctx).usedBy).toBe('vault.acme.internal');
    expect(replaceHint(subjectOf(local('a/b', 'a', null, null), ctx))).toBe('Saving creates a new version and puts it in use.');
    expect(subjectOf(local('t/x', 'tunnel', 1, null, { ownerKind: 'tunnel' }), ctx).testable).toBe(false);
  });

  it('says what a Replace did, and why a failed test kept the old version', () => {
    const s = subjectOf(row('dev-db/password'), ctx);
    expect(replacedToast(s, { version: 4, tested: true, testMessage: 'Connected in 12 ms' })).toEqual({
      tone: 'success',
      title: 'New value saved',
      body: 'Encrypted with the master key. dev-db works with version 4. The previous version is retired.',
    });
    expect(replacedToast(s, { version: 4, tested: null, testMessage: null }).body).toBe('Encrypted with the master key. The previous version is retired.');
    expect(replaceFailedText(s, 'password authentication failed for user "app"')).toEqual({
      title: "dev-db didn't accept the new value",
      body: 'password authentication failed for user "app". Version 3 stays in use. Check the value and save again, or save it without the test.',
    });
  });

  it('references: the scheme is checked here, the rest by the core; the result says nothing about the value', () => {
    expect(refProblem('')).toMatch(/^Enter a reference/);
    expect(refProblem('https://vault/x')).toBe('A reference starts with vault://, gcp-sm:// or aws-sm://.');
    expect(refProblem('vault://kv/stage/db#password')).toBeNull();
    const found = { found: true, resolvedVersion: '7', created: null, type: 'json', keys: ['password', 'user'], latencyMs: 18 };
    expect(refTestToast('stage-db/password', found)).toEqual({ tone: 'success', title: 'Reference stage-db/password works', body: 'Resolved in 18 ms. Version v7. The value was neither shown nor kept.' });
    expect(refTestLine(found)).toEqual({ ok: true, text: 'Found · v7 · keys password, user. The value was neither shown nor kept.' });
    const missing = { found: false, resolvedVersion: null, created: null, type: null, keys: [], error: 'No secret at kv/stage/dbx.', reason: 'not-found' };
    expect(refTestToast('stage-db/password', missing)).toMatchObject({ tone: 'danger', title: "Reference stage-db/password doesn't resolve", body: 'No secret at kv/stage/dbx.' });
    expect(refTestLine(missing)).toEqual({ ok: false, text: 'No secret at kv/stage/dbx.' });
  });
});

describe('master key', () => {
  it('names the OS protection of each platform, and says when there is none', () => {
    expect(osProtectionText('keychain')).toBe('On · the file is also encrypted with your macOS Keychain');
    expect(osProtectionText('dpapi')).toMatch(/^On · .*DPAPI/);
    expect(osProtectionText('off')).toBe('Off · the file is protected by its permissions only');
    expect(osProtectionText('unavailable')).toBe('Unavailable · the file is protected by its permissions only');
  });

  it('turns the core’s problems into the kit’s alerts, in order; a healthy key has none', () => {
    expect(keyAlerts(kitMasterKey())).toEqual([]);
    const all = keyAlerts(kitMasterKey({ mode: '0644', osProtection: 'unavailable', problems: ['perms', 'no-os-protection', 'synced-folder', 'backup-needed'], warnings: ['new-key-created'] }));
    expect(all.map((a) => [a.code, a.tone, a.action])).toEqual([
      ['new-key-created', 'warning', null],
      ['perms', 'danger', 'fix-permissions'],
      ['no-os-protection', 'warning', null],
      ['synced-folder', 'warning', 'data-folder'],
    ]);
    expect(all[1].title).toBe('Permissions are 0644: other users on this computer can read the key');
    expect(all[2].title).toBe('OS protection unavailable');
    expect(keyAlerts(kitMasterKey({ osProtection: 'off', problems: ['no-os-protection'] }))[0].title).toBe('OS protection is off');
  });

  it('places the file, its folder and the backup', () => {
    expect(keyFolder('/Users/you/.gaterook/master.keyset')).toBe('/Users/you/.gaterook/');
    expect(keyFileName('C:\\Users\\you\\.gaterook\\master.keyset')).toBe('master.keyset');
    expect(backupFileName(NOW)).toBe('master-keyset-2026-10-22.json');
    expect(backupLead(8)).toBe("Without a copy, the 8 secrets stored here can't be recovered if this computer is lost.");
    expect(backupLead(0)).toMatch(/later/);
    expect(keysText(kitMasterKey())).toBe('1 in the keyset');
    expect(keysText(kitMasterKey({ keys: [{ id: 2, status: 'enabled', primary: true }, { id: 1, status: 'disabled', primary: false }] }))).toBe(
      '2 in the keyset · the older one goes at the next backup',
    );
  });

  it("says when the key was made and rotated, as the kit's Created row (step API)", () => {
    const date = (at: string) => (at.startsWith('2026-07-14') ? 'Jul 14' : 'Oct 2');
    expect(createdRow(kitMasterKey({ createdAt: '2026-07-14T08:02:00Z', rotatedAt: null }), date)).toEqual({
      at: '2026-07-14T08:02:00Z',
      date: 'Jul 14',
      rotation: 'never rotated',
    });
    expect(createdRow(kitMasterKey({ createdAt: '2026-07-14T08:02:00Z', rotatedAt: '2026-10-02T09:00:00Z' }), date)?.rotation).toBe('rotated Oct 2');
    // A core that doesn't say: the Keys row stays.
    expect(createdRow(kitMasterKey(), date)).toBeNull();
  });

  it('says where the backup went, with the passphrase and a synced folder', () => {
    expect(backedUpToast({ path: '/Users/you/Downloads/master-keyset-2026-10-22.json', passphrase: true, warnings: [] })).toEqual({
      tone: 'success',
      title: 'Master key backed up, encrypted with your passphrase',
      body: 'Saved to /Users/you/Downloads/master-keyset-2026-10-22.json. Move it to your password manager or an encrypted drive, then delete it from this computer.',
      timeout: 0,
    });
    const synced = backedUpToast({ path: '/x/y.json', passphrase: false, warnings: ['synced-folder', 'no-passphrase'] });
    expect(synced.tone).toBe('warning');
    expect(synced.title).toBe('Master key backed up');
    expect(synced.body).toMatch(/syncs to other devices/);
  });
});

describe('master key rotation', () => {
  it('explains the four steps before it starts, with the number of data keys', () => {
    const v = rotationView({ state: 'idle', done: 0, total: 0, targetKeyId: null, error: null }, null, 8);
    expect(v.steps.map((s) => [s.label, s.state])).toEqual([
      ['Add a new key', 'upcoming'],
      ['Re-wrap 8 data keys', 'upcoming'],
      ['Disable the old key', 'upcoming'],
      ['Back up the new key', 'upcoming'],
    ]);
  });

  it('shows the kit’s "Re-wrapping data keys · 5 of 8" while it runs', () => {
    const v = rotationView({ state: 'running', done: 5, total: 8, targetKeyId: 3920481776, error: null }, 2146530891, 8);
    expect(v.label).toBe('Re-wrapping data keys · 5 of 8');
    expect(v.fraction).toBe(0.625);
    expect(v.valueText).toBe('5 of 8 data keys re-wrapped');
    expect(v.steps.map((s) => s.state)).toEqual(['complete', 'current', 'upcoming', 'upcoming']);
    expect(v.steps[0]).toMatchObject({ description: 'Key ', keyId: 3920481776, keyAfter: ' is primary' });
    expect(v.steps[1].description).toBe('5 of 8 done');
    expect(v.steps[2]).toMatchObject({ description: 'Key ', keyId: 2146530891 });
  });

  it('ends with the backup as the next step, or says where it stopped', () => {
    const done = rotationView({ state: 'done', done: 8, total: 8, targetKeyId: 1, error: null }, 2, 8);
    expect(done.steps.map((s) => s.state)).toEqual(['complete', 'complete', 'complete', 'current']);
    expect(done.tone).toBe('success');
    expect(done.fraction).toBe(1);
    const failed = rotationView({ state: 'failed', done: 3, total: 8, targetKeyId: 1, error: 'the core is stopping' }, 2, 8);
    expect(failed.label).toBe('Rotation stopped · 3 of 8 data keys re-wrapped');
    expect(failed.steps[1]).toMatchObject({ state: 'error', description: 'the core is stopping. It resumes when the core starts again; calls keep working.' });
    expect(rotationView({ state: 'done', done: 0, total: 0, targetKeyId: 1, error: null }, null, 0).fraction).toBe(1);
  });
});

describe('external stores', () => {
  const [vault, gcp] = kitStores();

  it('status, description and sign-in in the kit’s words', () => {
    expect(storeStatus(vault)).toEqual({ tone: 'success', label: 'Connected · 18 ms' });
    expect(storeStatus({ ...vault, state: 'not-checked', latencyMs: null })).toEqual({ tone: 'neutral', label: 'Not checked yet' });
    expect(storeStatus({ ...vault, state: 'failing' })).toEqual({ tone: 'danger', label: 'Failing' });
    expect(storeStatus({ ...vault, verified: false })).toEqual({ tone: 'warning', label: 'Changed outside GateRook' });
    expect(storeDescription(vault)).toBe('Or OpenBao, its open-source fork');
    expect(storeDescription(gcp)).toBe('Project acme-stage');
    expect(text(storeSignIn(vault))).toBe('AppRole gaterook gets a token, renewed every hour. Secret ID: vault/approle-secret-id');
    expect(text(storeSignIn(gcp))).toBe('Impersonates gw-secrets@acme-stage.iam.gserviceaccount.com from your gcloud credentials. No key file.');
    const aws: SecretStore = {
      ...vault,
      kind: 'aws-sm',
      config: { region: 'eu-central-1', credentials: { source: 'profile', profile: 'acme-stage', roleArn: 'arn:aws:iam::412398765012:role/r', externalId: 'gw-ext-3f9a0e1b2c3d' } },
      signIn: [],
    };
    expect(text(storeSignIn(aws))).toBe('AWS profile acme-stage assumes arn:aws:iam::412398765012:role/r with ExternalId gw-ext-3f9a0e1b2c3d.');
    expect(storeDescription(aws)).toBe('Read with AssumeRole and an ExternalId · eu-central-1');
    expect(text(storeSignIn({ ...vault, config: { address: 'https://v', auth: { method: 'cli-token' } } }))).toBe('Your vault login on this computer (~/.vault-token)');
  });

  it('counts SSH certificate connections without hidden Vault database credentials', () => {
    expect(storeIssuesCount(vault)).toBe(2);
    expect(storeIssuesCount({ ...vault, issues: 1 })).toBe(0);
    expect(storeIssuesCount({ ...vault, issues: 0 })).toBe(0);
    expect(storeIssuesCount({ ...gcp, issues: 2 })).toBe(2);
  });

  it('counts connected kinds and lists the references waiting for a store', () => {
    expect(storesSummary(kitStores())).toMatch(/2 of 3 connected\.$/);
    expect(storesSummary([])).toMatch(/0 of 3 connected\.$/);
    expect(waitingFor('aws-sm', kitSecrets())).toEqual(['billing-api/webhook-secret']);
    expect(text(waitingText(['billing-api/webhook-secret']))).toBe("billing-api/webhook-secret points here and can't be resolved until you connect.");
    expect(text(waitingText(['a', 'b', 'c']))).toBe("a, b and c point here and can't be resolved until you connect.");
  });

  it('Disconnect names what fails next and what is destroyed', () => {
    expect(text(disconnectLead(vault, 'HashiCorp Vault'))).toBe(
      'prod-replica and stage-db fail on their next call: their credentials come from HashiCorp Vault. Its sign-in secret vault/approle-secret-id is destroyed.',
    );
    expect(text(disconnectLead(gcp, 'GCP Secret Manager'))).toBe('billing-api fails on its next call: its credentials come from GCP Secret Manager.');
    expect(disconnectedToast('HashiCorp Vault', { referencesNotConnected: ['stage-db/password'], leasesRevoked: 1, secretsDestroyed: [] }, 2)).toEqual({
      tone: 'warning',
      title: 'HashiCorp Vault disconnected',
      body: "2 connections can't get credentials until you connect it again.",
    });
  });

  it('test and connect results', () => {
    const ok = { ok: true, latencyMs: 18, server: null, identity: null, permissions: [{ ref: 'vault://kv/stage/db', capabilities: ['read'] }], hints: [], checks: [], error: null, reason: null };
    expect(storeTestToast('HashiCorp Vault', ok)).toEqual({ tone: 'success', title: 'HashiCorp Vault works', body: 'Answered in 18 ms. 1 reference checked.' });
    expect(storeTestToast('HashiCorp Vault', { ...ok, hints: [{ severity: 'warning', code: 'root', text: 'This is a root token.' }] }).tone).toBe('warning');
    expect(storeTestToast('HashiCorp Vault', { ...ok, ok: false, error: 'Vault is sealed.' })).toMatchObject({ tone: 'danger', body: 'Vault is sealed.' });
    const aws: SecretStore = { ...gcp, kind: 'aws-sm', references: [{ secretId: 'x', name: 'billing-api/webhook-secret', ref: 'aws-sm://…', kind: 'reference', owner: 'billing-api' }] };
    expect(connectedToast('AWS Secrets Manager', aws, ok)).toEqual({ tone: 'success', title: 'AWS Secrets Manager connected', body: 'billing-api/webhook-secret resolves now.' });
    expect(connectedToast('AWS Secrets Manager', aws, { ...ok, ok: false, error: 'AccessDenied' })).toMatchObject({ tone: 'warning', title: 'AWS Secrets Manager connected, but its test failed' });
  });
});

describe('short-lived credentials', () => {
  it('groups by mechanism and identity in the kit’s order, with envs, lifetimes and the latest issue', () => {
    const groups = shortLivedGroups(kitShortLived(), ENVS, kitSecrets());
    expect(groups.map((g) => g.title)).toEqual(['GCP service account impersonation', 'AWS AssumeRole with ExternalId', 'SSH certificates from Vault CA']);
    const ssh = groups[2];
    expect(ssh.connections).toEqual([{ name: 'dev-vm', env: 'dev' }, { name: 'prod-vm', env: 'prod' }]);
    expect(ssh.lastIssuedAt).toBe('2026-10-22T12:24:00Z');
    expect(ssh.lifetime).toBe('5 min');
    expect(groups[1].assumedWith).toBe('aws-stage/access-key');
    // Without step API's fields, no ExternalId or Principal row.
    expect(groups.map((g) => [g.externalId, g.principal])).toEqual([[null, null], [null, null], [null, null]]);
    // Decision 10 (§8.6.5): a token is renewed, not issued for every call.
    expect(groups[0].description).not.toMatch(/per call/);
    expect(shortLivedSummary(groups)).toMatch(/4 connections work this way\.$/);
  });

  it("names the role's ExternalId and the certificate's principal when the core says them (step API)", () => {
    const items = kitShortLived().map((c) =>
      c.mechanism === 'AWS AssumeRole' ? { ...c, externalId: 'gw-ext-7c41d2' } : c.connection === 'dev-vm' ? { ...c, principal: 'deploy' } : c,
    );
    const groups = shortLivedGroups(items, ENVS, kitSecrets());
    expect(groups[1]).toMatchObject({ externalId: 'gw-ext-7c41d2', principal: null });
    expect(groups[2]).toMatchObject({ externalId: null, principal: 'deploy' });
  });

  it('keeps a mechanism it doesn’t know, under its own name', () => {
    const [g] = shortLivedGroups([{ connection: 'x', mechanism: 'Azure managed identity', identity: 'mi-1', lastIssuedAt: null, lifetime: 3600 }], ENVS);
    expect(g).toMatchObject({ title: 'Azure managed identity', identity: 'mi-1', lifetime: '1 h', lastIssuedAt: null });
  });
});
