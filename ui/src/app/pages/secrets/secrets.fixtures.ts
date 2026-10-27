import { MasterKeyStatus, SecretRow, SecretStore, ShortLivedCredential } from '../../core/api/secrets.api';
import { Env } from '../../core/api/types';

/**
 * The kit's world of design/pages/secrets.html (BLUEPRINT §9) as the core sends it (S1 shapes), for tests only:
 * 14:30 +02:00 on 2026-10-22, 11 secrets, Vault and GCP SM connected, AWS SM not, five short-lived credentials.
 */
export const NOW = Date.parse('2026-10-22T12:30:00Z');

export function local(name: string, owner: string, version: number | null, rotatedAt: string | null, extra: Partial<SecretRow> = {}): SecretRow {
  return {
    id: `id-${name}`,
    name,
    owner,
    ownerKind: 'connection',
    field: '/credentials/password',
    usedBy: ['dev'],
    source: 'local',
    version,
    remoteVersion: null,
    rotatedAt,
    state: 'stored',
    canRotate: false,
    rotateReason: "This connector can't issue a new value for itself. Replace it with a new value.",
    ref: null,
    store: null,
    ...extra,
  };
}

export function reference(name: string, owner: string, source: string, ref: string, extra: Partial<SecretRow> = {}): SecretRow {
  return {
    id: `id-${name}`,
    name,
    owner,
    ownerKind: 'connection',
    field: '/credentials/password',
    usedBy: ['stage-readonly'],
    source,
    version: null,
    remoteVersion: null,
    rotatedAt: null,
    state: 'reference',
    canRotate: false,
    rotateReason: 'References rotate in their store.',
    ref,
    store: null,
    ...extra,
  };
}

export function kitSecrets(): SecretRow[] {
  return [
    local('app-logs/token', 'app-logs', 1, '2026-09-11T07:40:00Z'),
    local('aws-stage/access-key', 'aws-stage', 2, '2026-09-22T13:05:00Z'),
    reference('billing-api/token', 'billing-api', 'gcp-sm', 'gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest', {
      remoteVersion: '4',
      rotatedAt: '2026-10-16T09:00:00Z',
      store: 'gcp-acme-stage',
    }),
    reference('billing-api/webhook-secret', 'billing-api', 'aws-sm', 'aws-sm://eu-central-1/stage/billing-api#webhook_secret', { state: 'not-connected' }),
    local('cache-redis/password', 'cache-redis', 1, '2026-07-14T08:20:00Z'),
    local('dev-cluster/token', 'dev-cluster', 4, '2026-10-06T06:45:00Z'),
    local('dev-db/password', 'dev-db', 3, '2026-10-20T09:14:00Z', { canRotate: true, rotateReason: null }),
    local('github-mcp/token', 'github-mcp', 1, '2026-10-17T11:30:00Z', { ownerKind: 'mcp-server' }),
    local('orders-mongo/password', 'orders-mongo', 2, '2026-09-29T15:10:00Z'),
    reference('stage-db/password', 'stage-db', 'vault', 'vault://kv/stage/db#password', { remoteVersion: '7', rotatedAt: '2026-10-13T10:00:00Z', store: 'vault' }),
    local('vault/approle-secret-id', 'vault', 5, '2026-10-08T07:00:00Z', {
      ownerKind: 'store',
      field: '/auth/secretId',
      usedBy: ['prod-replica', 'stage-db'],
      rotateReason: "Make a new value in the store's own console, then replace it here.",
    }),
  ];
}

export const ENVS: ReadonlyMap<string, Env> = new Map<string, Env>([
  ['app-logs', 'dev'],
  ['aws-stage', 'stage'],
  ['billing-api', 'stage'],
  ['cache-redis', 'dev'],
  ['dev-cluster', 'dev'],
  ['dev-db', 'dev'],
  ['orders-mongo', 'dev'],
  ['stage-db', 'stage'],
  ['prod-logs', 'prod'],
  ['prod-replica', 'prod'],
  ['dev-vm', 'dev'],
  ['prod-vm', 'prod'],
]);

export function kitStores(): SecretStore[] {
  return [
    {
      id: 'st-vault',
      kind: 'vault',
      name: 'vault',
      config: { address: 'https://vault.acme.internal', auth: { method: 'approle', roleId: 'mcp-gateway' } },
      state: 'connected',
      lastCheckedAt: '2026-10-22T12:29:00Z',
      latencyMs: 18,
      lastError: null,
      references: [
        { secretId: 'id-stage-db/password', name: 'stage-db/password', ref: 'vault://kv/stage/db#password', kind: 'reference', owner: 'stage-db' },
        { secretId: 'id-prod-replica/password', name: 'prod-replica/password', ref: 'vault://database/creds/prod-replica-readonly', kind: 'dynamic', owner: 'prod-replica' },
      ],
      signIn: [{ field: '/auth/secretId', secretId: 'id-vault/approle-secret-id', name: 'vault/approle-secret-id', state: 'stored', version: 5 }],
      issues: 3,
      verified: true,
      version: 1,
    },
    {
      id: 'st-gcp',
      kind: 'gcp-sm',
      name: 'gcp-acme-stage',
      config: { project: 'acme-stage', credentials: { source: 'adc', impersonate: 'gw-secrets@acme-stage.iam.gserviceaccount.com' } },
      state: 'connected',
      lastCheckedAt: '2026-10-22T12:29:00Z',
      latencyMs: 41,
      lastError: null,
      references: [
        { secretId: 'id-billing-api/token', name: 'billing-api/token', ref: 'gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest', kind: 'reference', owner: 'billing-api' },
      ],
      signIn: [],
      issues: 0,
      verified: true,
      version: 0,
    },
  ];
}

export function kitShortLived(): ShortLivedCredential[] {
  return [
    { connection: 'prod-logs', mechanism: 'GCP impersonation', identity: 'gw-reader@acme-prod.iam.gserviceaccount.com', lastIssuedAt: '2026-10-22T12:26:00Z', lifetime: 900 },
    { connection: 'aws-stage', mechanism: 'AWS AssumeRole', identity: 'arn:aws:iam::412398765012:role/gw-readonly', lastIssuedAt: '2026-10-22T10:31:00Z', lifetime: 900 },
    { connection: 'prod-vm', mechanism: 'SSH certificate from Vault CA', identity: 'ssh-client-signer/sign/gw-runbooks', lastIssuedAt: '2026-10-22T11:02:00Z', lifetime: 300 },
    { connection: 'dev-vm', mechanism: 'SSH certificate from Vault CA', identity: 'ssh-client-signer/sign/gw-runbooks', lastIssuedAt: '2026-10-22T12:24:00Z', lifetime: 300 },
    { connection: 'prod-replica', mechanism: 'Vault dynamic database user', identity: 'vault://database/creds/prod-replica-readonly', lastIssuedAt: '2026-10-22T11:22:00Z', lifetime: 600 },
  ];
}

export function kitMasterKey(extra: Partial<MasterKeyStatus> = {}): MasterKeyStatus {
  return {
    path: '/Users/you/.mcp-gateway/master.keyset',
    mode: '0400',
    owner: 'you',
    keys: [{ id: 2146530891, status: 'enabled', primary: true }],
    osProtection: 'keychain',
    protects: 8,
    lastBackupAt: '2026-10-10T16:20:00Z',
    problems: ['backup-needed'],
    warnings: [],
    locked: null,
    rotation: { state: 'idle', done: 0, total: 0, targetKeyId: null, error: null },
    ...extra,
  };
}
