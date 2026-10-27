import { ConnectionDetail, ConnectionRow, TestOutcome } from '../../core/api/connections.api';
import { SecretRow, ShortLivedCredential } from '../../core/api/secrets.api';

/*
 * Test data only (imported by the specs, never by the page): rows in the shape `GET /api/connections` answers,
 * named after the kit's world (BLUEPRINT §9) so assertions read like the kit page.
 */

export const NOW = Date.parse('2026-10-22T14:30:00+02:00');
export const at = (hhmmss: string): string => new Date(`2026-10-22T${hhmmss}+02:00`).toISOString();

export function row(name: string, over: Partial<ConnectionRow> = {}): ConnectionRow {
  return {
    name,
    type: 'postgres',
    typeName: 'PostgreSQL',
    env: 'dev',
    host: `${name}.internal:5432/app`,
    badges: [],
    secretSource: 'encrypted',
    health: { state: 'healthy', latencyMs: 12, message: null, checkedAt: at('14:29:00') },
    usedBy: ['dev'],
    verified: true,
    version: 1,
    ...over,
  };
}

/** The kit's twelve connections, with the states its page shows (one slow, one unreachable). */
export function kitRows(): ConnectionRow[] {
  return [
    row('app-logs', { type: 'logs', typeName: 'Logs', host: 'loki.dev.internal:3100' }),
    row('aws-stage', { type: 'aws', typeName: 'AWS', env: 'stage', host: 'eu-central-1', usedBy: ['dev', 'stage-readonly'] }),
    row('billing-api', {
      type: 'http',
      typeName: 'HTTP API',
      env: 'stage',
      host: 'https://billing.stage.acme.dev',
      secretSource: 'gcp-sm',
      usedBy: ['dev', 'stage-readonly'],
      health: { state: 'unreachable', latencyMs: null, message: 'Connection refused. Check the stage VPN or the billing service, then test again.', checkedAt: at('14:29:41') },
    }),
    row('cache-redis', { type: 'redis', typeName: 'Redis', host: 'cache.dev.internal:6379', health: { state: 'degraded', latencyMs: 1800, message: null, checkedAt: at('14:29:42') } }),
    row('dev-cluster', { type: 'kubernetes', typeName: 'Kubernetes', host: 'k8s.dev.internal:6443' }),
    row('dev-db', { host: 'db.dev.internal:5432/app' }),
    row('dev-vm', { type: 'ssh', typeName: 'SSH', host: '10.20.0.14:22', secretSource: null }),
    row('orders-mongo', { type: 'mongodb', typeName: 'MongoDB', host: 'mongo.dev.internal:27017/orders' }),
    row('prod-logs', { type: 'logs', typeName: 'Logs', env: 'prod', host: 'projects/acme-prod', secretSource: null, usedBy: ['incident-prod'] }),
    row('prod-replica', { env: 'prod', host: 'replica.prod.internal:5432/app', secretSource: 'vault', badges: ['Read replica'], usedBy: ['incident-prod'] }),
    row('prod-vm', { type: 'ssh', typeName: 'SSH', env: 'prod', host: '10.40.0.21:22', secretSource: null, badges: ['Runbooks only'], usedBy: ['incident-prod'] }),
    row('stage-db', { env: 'stage', host: 'db.stage.internal:5432/app', secretSource: 'vault', usedBy: ['stage-readonly'] }),
  ];
}

export function secret(owner: string, name: string, over: Partial<SecretRow> = {}): SecretRow {
  return {
    id: name,
    name,
    owner,
    ownerKind: 'connection',
    field: '/credentials/password',
    usedBy: [],
    source: 'local',
    version: 1,
    remoteVersion: null,
    rotatedAt: null,
    state: 'stored',
    canRotate: false,
    rotateReason: null,
    ...over,
  };
}

export const SECRETS: SecretRow[] = [
  secret('dev-db', 'dev-db/password', { version: 3 }),
  secret('stage-db', 'stage-db/password', { source: 'vault', state: 'reference', ref: 'vault://kv/stage/db#password', store: 'vault' }),
  secret('billing-api', 'billing-api/token', { source: 'gcp-sm', state: 'reference', ref: 'gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest', store: 'gcp' }),
  secret('billing-api', 'billing-api/webhook-secret', { source: 'aws-sm', state: 'not-connected', ref: 'aws-sm://eu-central-1/stage/billing-api#webhook_secret', store: null }),
];

export const SHORT_LIVED: ShortLivedCredential[] = [
  { connection: 'dev-vm', mechanism: 'SSH CA certificate', identity: 'vault ssh-client-signer', lastIssuedAt: at('14:20:00'), lifetime: 300 },
  { connection: 'prod-replica', mechanism: 'Vault dynamic user', identity: 'vault://database/creds/prod-replica-readonly', lastIssuedAt: at('14:20:00'), lifetime: 3600 },
];

export function detail(name: string, over: Partial<ConnectionDetail> = {}): ConnectionDetail {
  return {
    name,
    type: 'postgres',
    typeName: 'PostgreSQL',
    env: 'dev',
    config: {},
    version: 1,
    secrets: [{ field: '/credentials/password', secretId: 's1', name: `${name}/password`, state: 'stored', version: 3, source: 'local' }],
    health: { state: 'healthy', latencyMs: 12, message: null, checkedAt: at('14:29:00') },
    spark24h: [],
    usedBy: ['sql_query', 'sql_execute', 'sql_list_schemas', 'sql_describe_table'].map((tool) => ({ tool, endpoint: 'dev', mode: tool === 'sql_execute' ? 'APPROVAL' : 'READ_ONLY' })),
    verified: true,
    ...over,
  };
}

export function outcome(over: Partial<TestOutcome> = {}): TestOutcome {
  return {
    ok: true,
    latencyMs: 12,
    serverVersion: null,
    identity: null,
    permissions: null,
    hints: [],
    checks: [],
    error: null,
    needsConfirmation: null,
    confirmationId: null,
    suggestedPolicyChange: null,
    details: null,
    ...over,
  };
}
