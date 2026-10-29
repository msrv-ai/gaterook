import { Env, JsonObject, JsonValue } from '../../core/api/types';
import { kitConnections, kitEndpoints, kitPolicies, kitSchemas } from './policies.fixtures';
import {
  allowlistHosts,
  busiestOf,
  changePhrase,
  envConnections,
  followOption,
  hintFor,
  keyInfo,
  lowerFirst,
  meter,
  numberError,
  optionsFor,
  paramParts,
  placeholderFor,
  resetText,
  riskyEndpoints,
  runbookMode,
  savedToast,
  sectionConnections,
  storedPhrases,
  unsavedNote,
  valuesSummary,
} from './policies-view';
import { Hint } from './policy-catalog';
import { Change, PoliciesByEnv, effectiveValue, initialForm, changes as changesOf } from './policy-model';

/** The page's words, computed from the core's schema and the demo world with the kit's values. */

const schemas = kitSchemas();
const info = (s: string, k: string) => keyInfo(schemas, s, k)!;
const text = (h: Hint) => h.map((p) => p.text).join('');
const display = (s: string, k: string) => info(s, k).display;

function hint(env: Env, s: string, k: string, over: { value?: JsonValue; dirty?: boolean; connections?: string[]; policies?: PoliciesByEnv } = {}): string {
  const policies = over.policies ?? kitPolicies();
  return text(
    hintFor({
      policies,
      schemas,
      env,
      info: info(s, k),
      value: 'value' in over ? over.value : effectiveValue(policies[env], s, k),
      dirty: over.dirty ?? false,
      connections: over.connections ?? [],
    }),
  );
}

function change(env: Env, values: Record<string, string | boolean | JsonValue[]>): Change[] {
  const p = kitPolicies();
  return changesOf(p, schemas, env, { ...initialForm(p, schemas, env, display), ...values }, display);
}

describe('Policies page words', () => {
  it('says what a dev setting does, as the kit does on the base environment', () => {
    expect(hint('dev', 'sql', 'statementTimeoutMs')).toBe('The database cancels slower statements.');
    expect(hint('dev', 'sql', 'allowlist', { connections: ['dev-db'] })).toBe('Agents see and query only these. schema.* allows a whole schema.');
    expect(hint('dev', 'limits', 'rateLimitPerToken')).toBe('Across all dev connections.');
  });

  it('says on stage where each value comes from', () => {
    expect(hint('stage', 'sql', 'statementTimeoutMs')).toBe('Inherited from dev: 5 s');
    expect(hint('stage', 'sql', 'rowLimit')).toBe('Set on stage · dev: 1,000 rows');
    expect(hint('stage', 'sql', 'allowlist')).toBe('Inherited from dev. Add or remove a value to set the list for stage.');
    expect(hint('stage', 'sql', 'denyDdl')).toBe('Stage default · dev: off. A DROP TABLE on stage is denied before anyone is asked.');
    expect(hint('stage', 'limits', 'maxResponseKb')).toBe('Set on stage · dev: 512 KB');
    expect(hint('stage', 'approvals', 'timeoutMinutes')).toBe('Stage default · dev: 5 min');
    expect(hint('stage', 'approvals', 'comment')).toBe('Inherited from dev: optional');
    // Never inherited: what it does, as on dev.
    expect(hint('stage', 'k8s', 'namespaces')).toBe('Per environment, never inherited. Agents see nothing outside these namespaces.');
  });

  it('compares prod with dev, since prod inherits nothing', () => {
    expect(hint('prod', 'sql', 'rowLimit')).toBe('Prod default · dev: 1,000 rows');
    expect(hint('prod', 'sql', 'allowlist', { connections: ['prod-replica'] })).toBe('Set on prod · dev: public.*, reporting.*. Only these are readable on prod-replica.');
    expect(hint('prod', 'limits', 'rateLimitPerToken')).toBe('Prod default · dev: 120 calls per min');
    expect(hint('prod', 'ssh', 'commandTimeoutSeconds')).toBe('The session is closed after this.');
  });

  it('on prod, Slack and Telegram never offer Approve, with or without a comment (kit after KIT, Eligibility)', () => {
    expect(hint('prod', 'approvals', 'comment', { value: 'required' })).toBe(
      'Prod default · dev: optional. Approve and run waits for a comment. Slack and Telegram never offer Approve on prod, with or without a comment: they show Deny and Open in app. Require a comment on prod in Settings',
    );
    expect(hint('prod', 'approvals', 'comment', { value: 'optional' })).toContain(
      'Slack and Telegram never offer Approve on prod, with or without a comment: they show Deny and Open in app.',
    );
    expect(hint('prod', 'approvals', 'comment', { value: 'optional' })).not.toContain('waits for a comment');
    expect(hint('stage', 'approvals', 'comment', { value: 'required' })).toContain('Approve and run waits for a comment; Slack and Telegram show Deny and Open in app.');
    expect(hint('stage', 'approvals', 'comment', { value: 'required' })).not.toContain('Require a comment on prod');
  });

  it('marks a change nobody saved and says what the field had', () => {
    expect(hint('prod', 'approvals', 'timeoutMinutes', { value: 15, dirty: true })).toBe('Unsaved · prod default: 10 min');
    expect(hint('stage', 'sql', 'rowLimit', { value: 750, dirty: true })).toBe('Unsaved · dev: 1,000 rows');
    expect(hint('dev', 'limits', 'rateLimitPerToken', { value: 100, dirty: true })).toBe('Unsaved · default: 120 calls per min');
  });

  it('offers to follow the value a select would have, and shows it in an empty number', () => {
    const p = kitPolicies();
    expect(followOption(p, schemas, 'stage', info('approvals', 'comment'))).toBe('Same as dev: optional');
    expect(followOption(p, schemas, 'stage', info('approvals', 'timeoutMinutes'))).toBe('Stage default: 10 min');
    expect(followOption(p, schemas, 'dev', info('approvals', 'comment'))).toBeNull();
    expect(followOption(p, schemas, 'prod', info('approvals', 'comment'))).toBeNull();
    expect(placeholderFor(p, schemas, 'stage', info('sql', 'statementTimeoutMs'))).toBe('5');
    expect(placeholderFor(p, schemas, 'stage', info('sql', 'rowLimit'))).toBe('1,000');
    expect(optionsFor(info('approvals', 'comment'), 'prod').map((o) => o.label)).toEqual(['Optional', 'Recommended', 'Required']);
    expect(optionsFor(info('masking', 'pii'), 'dev').map((o) => `${o.value}:${o.label}`)).toEqual(['email:Email addresses', 'phone:Phone numbers', 'card:Card numbers', 'iban:IBANs']);
    expect(lowerFirst('IBANs')).toBe('IBANs');
    expect(lowerFirst('Optional')).toBe('optional');
  });

  it('sums up where the values of each environment come from', () => {
    const p = kitPolicies();
    expect(valuesSummary(p, schemas, 'dev')).toBe('4 set on dev · base for stage');
    expect(valuesSummary(p, schemas, 'stage')).toBe('3 stage defaults · 6 set on stage · 32 inherited from dev');
    expect(valuesSummary(p, schemas, 'prod')).toBe('24 prod defaults · 2 set on prod');
  });

  it('puts unsaved changes in one line, and the saved ones in a toast', () => {
    const timeout = change('prod', { 'approvals.timeoutMinutes': '15' });
    expect(unsavedNote(schemas, 'prod', timeout)).toBe('Unsaved changes · approval timeout on prod, 10 to 15 min');
    expect(savedToast(kitPolicies(), schemas, 'prod', timeout)).toEqual({
      tone: 'success',
      title: 'Prod policy saved',
      body: 'Approval timeout on prod: 15 min. Requests already waiting keep their timer.',
    });

    const rate = change('dev', { 'limits.rateLimitPerToken': '100' });
    expect(savedToast(kitPolicies(), schemas, 'dev', rate).body).toBe('Rate limit per token on dev: 100 calls per min. Stage follows wherever it sets nothing itself.');

    const two = change('stage', { 'sql.rowLimit': '250', 'sql.denyDdl': false });
    expect(unsavedNote(schemas, 'stage', two)).toBe('Unsaved changes · row limit and DDL on stage');
    const many = change('stage', { 'sql.rowLimit': '250', 'sql.denyDdl': false, 'limits.maxResponseKb': '128', 'http.allowedBaseUrls': [] });
    expect(unsavedNote(schemas, 'stage', many)).toBe('Unsaved changes · 4 values on stage');
    expect(savedToast(kitPolicies(), schemas, 'stage', many).body).toBe('4 values changed on stage. They apply from the next call.');

    expect(changePhrase(info('sql', 'denyDdl'), true, false)).toBe('DDL allowed with approval unless Bypass');
    expect(changePhrase(info('ssh', 'allowExec'), false, true)).toBe('ssh_exec allowed');
    expect(changePhrase(info('sql', 'statementTimeoutMs'), 5000, 3000)).toBe('statement timeout 5 to 3 s');
  });

  it('says in the Reset dialog what goes back where', () => {
    const p = kitPolicies();
    const stage = resetText(p, schemas, 'stage', []);
    expect(stage.title).toBe('Reset the stage policy to dev?');
    expect(stage.trigger).toBe('Reset to dev');
    expect(stage.button).toBe('Reset stage policy');
    expect(stage.description).toBe(
      'The 6 values set on stage go back to dev: row limit 500 to 1,000 rows, max response 256 to 512 KB, allowed services to none, regions to none, resource patterns to none, allowed base URLs to none.',
    );
    expect(stage.toast).toEqual({ tone: 'neutral', title: 'Stage policy reset', body: '6 values on stage now follow dev.' });

    const prod = resetText(p, schemas, 'prod', change('prod', { 'approvals.timeoutMinutes': '15' }));
    expect(prod.title).toBe('Reset the prod policy to its defaults?');
    expect(prod.trigger).toBe('Reset to prod defaults');
    expect(prod.description).toBe('The 2 values set on prod go back to prod defaults: table allowlist to none, masked fields to none. Your unsaved approval timeout is discarded.');

    const dev = resetText(p, schemas, 'dev', []);
    expect(dev.trigger).toBe('Reset to dev defaults');
    expect(dev.description).toMatch(/^The 4 values set on dev go back to dev defaults: .* Stage follows wherever it sets nothing itself\.$/);

    expect(storedPhrases(schemas, p.stage)[0]).toBe('row limit 500 rows');
  });

  it('fills the rate-limit meters from the last minute', () => {
    expect(meter(120, null)).toMatchObject({ value: 0, max: 120, fraction: 0, level: 'ok', icon: null, valueText: '0 of 120 calls in the last minute' });
    const busy = { name: 'sql_query', connection: 'dev-db', calls: 24 };
    expect(meter(30, busy)).toMatchObject({ value: 24, fraction: 0.8, level: 'warn', icon: 'triangle-alert', valueText: '24 of 30 calls in the last minute, near the limit' });
    expect(meter(30, { ...busy, calls: 31 })).toMatchObject({ fraction: 1, level: 'critical', icon: 'circle-alert' });
    expect(meter(undefined, busy)).toBeNull();

    const calls = [
      { token: 'codex-laptop', tool: 'sql_query', connection: 'dev-db' },
      { token: 'codex-laptop', tool: 'sql_query', connection: 'dev-db' },
      { token: 'claude-desktop', tool: 'sql_query', connection: 'orders-mongo' },
      { token: null, tool: 'logs_search', connection: 'app-logs' },
    ];
    expect(busiestOf(calls)).toEqual({
      token: { name: 'codex-laptop', connection: null, calls: 2 },
      tool: { name: 'sql_query', connection: 'dev-db', calls: 2 },
    });
    expect(busiestOf([])).toEqual({ token: null, tool: null });
  });

  it('reads a runbook’s parameters and mode from its definition, and doesn’t guess without one', () => {
    const logs: JsonObject = {
      name: 'compose-logs',
      command: 'docker compose logs --tail {lines} {service}',
      readOnly: true,
      readOnlyConfirmedFor: 'docker compose logs --tail {lines} {service}',
      params: [
        { name: 'service', type: 'enum', required: true, values: ['api', 'worker'] },
        { name: 'lines', type: 'int', required: true, min: 1, max: 500, default: 100 },
      ],
    };
    expect(paramParts(logs)).toEqual([
      { text: 'service', code: true },
      { text: ' one of ', code: false },
      { text: 'api', code: true },
      { text: ', ', code: false },
      { text: 'worker', code: true },
      { text: ' · ', code: false },
      { text: 'lines', code: true },
      { text: ' integer 1 to 500, default 100', code: false },
    ]);
    expect(paramParts({ command: 'uptime' })).toEqual([{ text: 'None', code: false }]);
    expect(runbookMode(null, logs)).toBe('read');
    // Marked read only for another command: the change since then waits for approval.
    expect(runbookMode(null, { ...logs, readOnlyConfirmedFor: 'docker compose logs' })).toBe('approval');
    expect(runbookMode({ read: 0, mutation: 0 }, null)).toBeNull();
    expect(runbookMode({ read: 0, mutation: 1 }, null)).toBe('approval');
    expect(runbookMode({ read: 1, mutation: 0 }, null)).toBe('read');
  });

  it('finds the connections of each section and the endpoints an HTTP allowlist lets out', () => {
    const conns = kitConnections();
    expect(envConnections('stage', conns).map((r) => r.name)).toEqual(['stage-db', 'aws-stage', 'billing-api']);
    expect(sectionConnections('sql', 'dev', conns).map((r) => r.name)).toEqual(['dev-db']);
    const oracle = { ...conns.find((connection) => connection.type === 'postgres' && connection.env === 'dev')!, name: 'orders-oracle', type: 'oracle' };
    expect(sectionConnections('sql', 'dev', [oracle]).map((r) => r.name)).toEqual(['orders-oracle']);
    expect(sectionConnections('k8s', 'dev', conns).map((r) => r.name)).toEqual(['dev-cluster']);
    expect(sectionConnections('ssh', 'stage', conns)).toEqual([]);
    expect(allowlistHosts(['https://billing.stage.acme.dev/v1', 'https://billing.stage.acme.dev', 'not a url'])).toEqual(['billing.stage.acme.dev']);
    expect(riskyEndpoints('stage', conns, kitEndpoints()).map((e) => e.slug)).toEqual(['dev', 'stage-readonly']);
    expect(riskyEndpoints('prod', conns, kitEndpoints())).toEqual([]);
  });

  it('associates SSH log files with SSH authentication/output and Logs time-range policies', () => {
    const conns = kitConnections();
    const ssh = conns.find((connection) => connection.type === 'ssh' && connection.env === 'dev')!;
    const remoteLogs = { ...ssh, type: 'ssh-logs', name: 'remote-log-files' };
    const all = [remoteLogs, ...conns];
    expect(sectionConnections('ssh', 'dev', all).map((row) => row.name)).toEqual([ssh.name, 'remote-log-files']);
    expect(sectionConnections('logs', 'dev', all).some((row) => row.name === 'remote-log-files')).toBe(true);
    expect(envConnections('dev', all).findIndex((row) => row.name === 'remote-log-files'))
      .toBeLessThan(envConnections('dev', all).findIndex((row) => row.type === 'logs'));
  });

  it('applies log policies to concrete log connectors and keeps search databases separate', () => {
    const sample = kitConnections().find((connection) => connection.type === 'logs' && connection.env === 'dev')!;
    const all = ['gcp-logging', 'cloudwatch', 'loki', 'opensearch', 'elasticsearch'].map((type) => ({ ...sample, name: type, type }));
    expect(sectionConnections('logs', 'dev', all).map((row) => row.type)).toEqual(['gcp-logging', 'cloudwatch', 'loki']);
    expect(envConnections('dev', all).map((row) => row.type)).toEqual(['opensearch', 'elasticsearch', 'gcp-logging', 'cloudwatch', 'loki']);
  });

  it('asks for a number in range, in the unit the field shows', () => {
    expect(numberError(info('limits', 'rateLimitPerToken'), '0')).toBe('Enter a whole number from 1 to 10,000 calls per min.');
    expect(numberError(info('limits', 'rateLimitPerToken'), '1.5')).toBe('Enter a whole number from 1 to 10,000 calls per min.');
    expect(numberError(info('limits', 'rateLimitPerToken'), 'many')).toBe('Enter a whole number from 1 to 10,000 calls per min.');
    expect(numberError(info('limits', 'rateLimitPerToken'), '1,000')).toBeNull();
    expect(numberError(info('sql', 'statementTimeoutMs'), '0.05')).toBe('Enter a number from 0.1 to 600 s.');
    expect(numberError(info('sql', 'statementTimeoutMs'), '2.5')).toBeNull();
  });
});
