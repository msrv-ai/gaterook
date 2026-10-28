import { EnvPolicy } from '../../core/api/policies.api';
import { kitPolicies, kitSchemas } from './policies.fixtures';
import {
  Display,
  PLAIN,
  PoliciesByEnv,
  chain,
  changes,
  editableKeys,
  effectiveValue,
  envDefault,
  explicitKeys,
  explicitValue,
  fallback,
  forbidden,
  formatNumber,
  fromDraft,
  inheritors,
  initialForm,
  isLocked,
  keySchema,
  kindOf,
  origin,
  originCounts,
  parseNumber,
  patch,
  same,
  toDraft,
} from './policy-model';

/** The policy arithmetic against the core's own schema and the demo world with the kit's values. */

const schemas = kitSchemas();
/** The kit asks for the SQL statement timeout in seconds; the core keeps milliseconds. */
const display = (s: string, k: string): Display => (s === 'sql' && k === 'statementTimeoutMs' ? { unit: 's', scale: 1000 } : PLAIN);

function withEnv(policies: PoliciesByEnv, env: 'dev' | 'stage' | 'prod', over: Partial<EnvPolicy>): PoliciesByEnv {
  return { ...policies, [env]: { ...policies[env], ...over } };
}

describe('policy model', () => {
  it('walks stage to dev; dev and prod stand alone', () => {
    const p = kitPolicies();
    expect(chain(p, 'stage')).toEqual(['stage', 'dev']);
    expect(chain(p, 'prod')).toEqual(['prod']);
    expect(chain(p, 'dev')).toEqual(['dev']);
    expect(inheritors(p, 'dev')).toEqual(['stage']);
    expect(inheritors(p, 'prod')).toEqual([]);
    // A cycle in a hand-edited row is cut, as the core cuts it.
    const cycle = withEnv(p, 'dev', { inherits: 'stage' });
    expect(chain(cycle, 'stage')).toEqual(['stage', 'dev']);
  });

  it('takes an environment default over the default over the constant; a list without one is empty', () => {
    expect(envDefault(keySchema(schemas, 'sql', 'rowLimit'), 'prod')).toBe(100);
    expect(envDefault(keySchema(schemas, 'sql', 'rowLimit'), 'stage')).toBe(1000);
    expect(envDefault(keySchema(schemas, 'sql', 'denyDdl'), 'stage')).toBe(true);
    expect(envDefault(keySchema(schemas, 'sql', 'readOnlyTransactions'), 'dev')).toBe(true);
    expect(envDefault(keySchema(schemas, 'sql', 'allowlist'), 'dev')).toEqual([]);
    expect(envDefault(keySchema(schemas, 'approvals', 'comment'), 'prod')).toBe('required');
    expect(envDefault(keySchema(schemas, 'limits', 'toolTimeoutSeconds'), 'dev')).toBe(30);
    expect(envDefault(keySchema(schemas, 'limits', 'toolTimeoutSeconds'), 'stage')).toBe(30);
    expect(envDefault(keySchema(schemas, 'limits', 'toolTimeoutSeconds'), 'prod')).toBe(5);
  });

  it('edits every key but the locked ones, each as a number, checkbox, select or list', () => {
    const keys = editableKeys(schemas);
    expect(keys).toContain('sql.rowLimit');
    expect(keys).toContain('approvals.announceIn');
    expect(keys).not.toContain('sql.readOnlyTransactions');
    expect(keys).not.toContain('http.writeApproval');
    expect(keys).not.toContain('ssh.runbookApproval');
    expect(isLocked(keySchema(schemas, 'masking', 'secrets'))).toBe(true);
    expect(kindOf(keySchema(schemas, 'sql', 'rowLimit'))).toBe('number');
    expect(kindOf(keySchema(schemas, 'approvals', 'timeoutMinutes'))).toBe('select');
    expect(kindOf(keySchema(schemas, 'approvals', 'comment'))).toBe('select');
    expect(kindOf(keySchema(schemas, 'sql', 'denyDdl'))).toBe('boolean');
    expect(kindOf(keySchema(schemas, 'masking', 'pii'))).toBe('list');
  });

  it('says where each value comes from', () => {
    const p = kitPolicies();
    const o = (env: 'dev' | 'stage' | 'prod', s: string, k: string) => origin(p, schemas, env, s, k);
    expect(o('dev', 'sql', 'rowLimit')).toEqual({ kind: 'default' });
    expect(o('dev', 'sql', 'allowlist')).toEqual({ kind: 'set' });
    expect(o('stage', 'sql', 'rowLimit')).toEqual({ kind: 'set' });
    expect(o('stage', 'sql', 'statementTimeoutMs')).toEqual({ kind: 'inherited', from: 'dev' });
    expect(o('stage', 'sql', 'allowlist')).toEqual({ kind: 'inherited', from: 'dev' });
    expect(o('stage', 'sql', 'denyDdl')).toEqual({ kind: 'env-default' });
    expect(o('stage', 'approvals', 'timeoutMinutes')).toEqual({ kind: 'env-default' });
    // Never inherited (`x-inherit: false`): stage's own, set or not.
    expect(o('stage', 'cloud', 'allowedServices')).toEqual({ kind: 'set' });
    expect(o('stage', 'k8s', 'namespaces')).toEqual({ kind: 'own' });
    expect(o('prod', 'sql', 'rowLimit')).toEqual({ kind: 'env-default' });
    expect(o('prod', 'sql', 'allowlist')).toEqual({ kind: 'set' });
    expect(o('prod', 'ssh', 'commandTimeoutSeconds')).toEqual({ kind: 'default' });
  });

  it('knows what an environment would have without its own value', () => {
    const p = kitPolicies();
    expect(fallback(p, schemas, 'stage', 'sql', 'rowLimit')).toEqual({ value: 1000, origin: { kind: 'inherited', from: 'dev' } });
    expect(fallback(p, schemas, 'stage', 'sql', 'allowlist')).toEqual({ value: ['public.*', 'reporting.*'], origin: { kind: 'inherited', from: 'dev' } });
    expect(fallback(p, schemas, 'stage', 'http', 'maxResponseKb')).toEqual({ value: 256, origin: { kind: 'env-default' } });
    expect(fallback(p, schemas, 'stage', 'cloud', 'allowedServices')).toEqual({ value: [], origin: { kind: 'own' } });
    expect(fallback(p, schemas, 'prod', 'sql', 'allowlist')).toEqual({ value: [], origin: { kind: 'default' } });

    // Stage takes the schema's default, which follows dev only while dev has that same value.
    const devSets = withEnv(p, 'dev', { sections: { ...p.dev.sections, ssh: { ...(p.dev.sections['ssh'] as object), commandTimeoutSeconds: 60 } } });
    expect(fallback(devSets, schemas, 'stage', 'ssh', 'commandTimeoutSeconds')).toEqual({ value: 30, origin: { kind: 'default' } });

    // A dev row that doesn't verify counts as setting nothing (PolicyService.explicitOf).
    const unverified = withEnv(p, 'dev', { verified: false });
    expect(fallback(unverified, schemas, 'stage', 'sql', 'allowlist').value).toEqual([]);
  });

  it('starts the form from what is stored; numbers and selects that follow dev start empty', () => {
    const p = kitPolicies();
    const stage = initialForm(p, schemas, 'stage', display);
    expect(stage['sql.rowLimit']).toBe('500');
    expect(stage['sql.statementTimeoutMs']).toBe('');
    expect(stage['approvals.timeoutMinutes']).toBe('10');
    expect(stage['approvals.comment']).toBe('');
    expect(stage['sql.allowlist']).toEqual(['public.*', 'reporting.*']);
    expect(stage['sql.denyDdl']).toBe(true);
    expect('sql.readOnlyTransactions' in stage).toBe(false);

    const dev = initialForm(p, schemas, 'dev', display);
    expect(dev['sql.statementTimeoutMs']).toBe('5');
    expect(dev['limits.rateLimitPerToken']).toBe('120');
    expect(dev['masking.pii']).toEqual(['email', 'phone', 'card', 'iban']);

    const prod = initialForm(p, schemas, 'prod', display);
    expect(prod['sql.rowLimit']).toBe('100');
    expect(prod['approvals.timeoutMinutes']).toBe('10');
    expect(prod['approvals.comment']).toBe('required');
  });

  it('finds nothing to save in a form nobody touched', () => {
    const p = kitPolicies();
    for (const env of ['dev', 'stage', 'prod'] as const) {
      expect(changes(p, schemas, env, initialForm(p, schemas, env, display), display)).toEqual([]);
    }
  });

  it('saves only what changes; the value an environment would have anyway is no value', () => {
    const p = kitPolicies();
    const prod = initialForm(p, schemas, 'prod', display);
    const timeout = changes(p, schemas, 'prod', { ...prod, 'approvals.timeoutMinutes': '15' }, display);
    expect(timeout).toEqual([{ section: 'approvals', key: 'timeoutMinutes', from: undefined, to: 15, before: 10, after: 15 }]);
    expect(patch(timeout)).toEqual({ approvals: { timeoutMinutes: 15 } });

    const stage = initialForm(p, schemas, 'stage', display);
    // Typing dev's 1,000 rows back unsets stage's 500: stage follows dev again.
    const rows = changes(p, schemas, 'stage', { ...stage, 'sql.rowLimit': '1,000' }, display);
    expect(rows).toEqual([{ section: 'sql', key: 'rowLimit', from: 500, to: undefined, before: 500, after: 1000 }]);
    expect(patch(rows)).toEqual({ sql: { rowLimit: null } });
    expect(changes(p, schemas, 'stage', { ...stage, 'sql.rowLimit': '' }, display)).toEqual(rows);
    // The inherited 5 s typed in is no change.
    expect(changes(p, schemas, 'stage', { ...stage, 'sql.statementTimeoutMs': '5' }, display)).toEqual([]);

    const dev = initialForm(p, schemas, 'dev', display);
    const seconds = changes(p, schemas, 'dev', { ...dev, 'sql.statementTimeoutMs': '7.5', 'masking.pii': ['email'] }, display);
    expect(patch(seconds)).toEqual({ sql: { statementTimeoutMs: 7500 }, masking: { pii: ['email'] } });
  });

  it('reads and writes numbers the way the kit shows them', () => {
    expect(parseNumber('1,000')).toBe(1000);
    expect(parseNumber(' 1 000 ')).toBe(1000);
    expect(parseNumber('2.5')).toBe(2.5);
    expect(parseNumber('1e3')).toBeNaN();
    expect(parseNumber('ten')).toBeNaN();
    expect(formatNumber(10000)).toBe('10,000');
    expect(toDraft('number', 3000, { unit: 's', scale: 1000 })).toBe('3');
    expect(toDraft('number', undefined)).toBe('');
    expect(toDraft('select', 15)).toBe('15');
    expect(fromDraft('number', '2.4', keySchema(schemas, 'sql', 'rowLimit'))).toBe(2);
    expect(fromDraft('number', 'abc', keySchema(schemas, 'sql', 'rowLimit'))).toBeUndefined();
    expect(fromDraft('select', '15', keySchema(schemas, 'approvals', 'timeoutMinutes'))).toBe(15);
    expect(fromDraft('select', 'required', keySchema(schemas, 'approvals', 'comment'))).toBe('required');
    expect(fromDraft('select', '', keySchema(schemas, 'approvals', 'comment'))).toBeUndefined();
  });

  it('counts where the values of an environment come from', () => {
    const p = kitPolicies();
    expect(originCounts(p, schemas, 'dev')).toEqual({ set: 4, envDefault: 0, inherited: 0, from: null });
    expect(originCounts(p, schemas, 'stage')).toMatchObject({ set: 6, envDefault: 3, from: 'dev' });
    expect(originCounts(p, schemas, 'prod')).toMatchObject({ set: 2, inherited: 0, from: null });
  });

  it('lists what an environment stores and compares values however they were typed', () => {
    const p = kitPolicies();
    expect(explicitKeys(p.stage).map((k) => `${k.section}.${k.key}`)).toEqual([
      'sql.rowLimit',
      'limits.maxResponseKb',
      'cloud.allowedServices',
      'cloud.regions',
      'cloud.resources',
      'http.allowedBaseUrls',
    ]);
    expect(explicitValue(p.stage, 'sql', 'rowLimit')).toBe(500);
    expect(effectiveValue(p.stage, 'sql', 'statementTimeoutMs')).toBe(5000);
    expect(same({ a: 1, b: [1, 2] }, { b: [1, 2], a: 1 })).toBe(true);
    expect(same(undefined, null)).toBe(true);
    expect(same([1, 2], [2, 1])).toBe(false);
    expect(forbidden({ 'x-forbidden-env-values': { prod: [true] } }, 'prod', true)).toBe(true);
    expect(forbidden({ 'x-forbidden-env-values': { prod: [true] } }, 'dev', true)).toBe(false);
  });
});
