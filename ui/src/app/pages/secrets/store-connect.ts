import { SecretStoreCreate, SecretStoreKind } from '../../core/api/secrets.api';
import { JsonObject } from '../../core/api/types';

/**
 * Connect a store (kit `#dlg-connect-aws`; S1 `POST /api/secret-stores` with the forms of
 * `ai/msrv/gaterook/core/secrets/store/{vault,gcp-sm,aws-sm}.schema.json`): the non-secret fields of all three kinds in
 * one model (the dialog shows the kind's), their rules in the core's words, and the request. Sign-in values (AWS
 * secret access key, AppRole secret ID, Vault token, service account key) are never in the model: the dialog reads
 * them from their fields once, when it sends.
 */
export interface ConnectModel {
  // AWS Secrets Manager
  readonly region: string;
  readonly awsSource: 'profile' | 'accessKey';
  readonly profile: string;
  readonly accessKeyId: string;
  readonly roleArn: string;
  readonly externalId: string;
  // HashiCorp Vault
  readonly address: string;
  readonly namespace: string;
  readonly method: 'approle' | 'token' | 'cli-token';
  readonly roleId: string;
  readonly caCert: string;
  // GCP Secret Manager
  readonly project: string;
  readonly gcpSource: 'adc' | 'serviceAccountKey';
  readonly impersonate: string;
}

export function emptyConnectModel(): ConnectModel {
  return {
    region: 'us-east-1',
    awsSource: 'profile',
    profile: 'default',
    accessKeyId: '',
    roleArn: '',
    externalId: '',
    address: '',
    namespace: '',
    method: 'approle',
    roleId: '',
    caCert: '',
    project: '',
    gcpSource: 'adc',
    impersonate: '',
  };
}

/** AWS regions with Secrets Manager (the kit's select; the core checks the pattern). */
export const AWS_REGIONS: readonly string[] = [
  'us-east-1', 'us-east-2', 'us-west-1', 'us-west-2', 'ca-central-1', 'ca-west-1', 'sa-east-1', 'mx-central-1',
  'eu-central-1', 'eu-central-2', 'eu-west-1', 'eu-west-2', 'eu-west-3', 'eu-north-1', 'eu-south-1', 'eu-south-2',
  'il-central-1', 'me-central-1', 'me-south-1', 'af-south-1', 'ap-east-1', 'ap-south-1', 'ap-south-2',
  'ap-southeast-1', 'ap-southeast-2', 'ap-southeast-3', 'ap-southeast-4', 'ap-southeast-5', 'ap-southeast-7',
  'ap-northeast-1', 'ap-northeast-2', 'ap-northeast-3',
];

/**
 * Where each form key lives in the request, so a 422 the core sends with a JSON Pointer (`/config/credentials/roleArn`
 * or `/credentials/roleArn`) lands under its field. Sign-in values are keyed like their fields.
 */
/** The sign-in values' fields (outside the model). */
export type SecretKey = 'secretAccessKey' | 'secretId' | 'token' | 'serviceAccountKey';

export const FIELD_POINTERS: { readonly [K in keyof ConnectModel | SecretKey]?: string } = {
  region: '/region',
  profile: '/credentials/profile',
  accessKeyId: '/credentials/accessKeyId',
  roleArn: '/credentials/roleArn',
  address: '/address',
  namespace: '/namespace',
  roleId: '/auth/roleId',
  caCert: '/caCert',
  project: '/project',
  impersonate: '/credentials/impersonate',
  secretAccessKey: '/credentials/secretAccessKey',
  secretId: '/auth/secretId',
  token: '/auth/token',
  serviceAccountKey: '/credentials/serviceAccountKey',
};

const REGION = /^[a-z]{2}(-[a-z]+)+-[0-9]+$/;
const ROLE_ARN = /^arn:aws[a-z-]*:iam::\d{12}:role\/[\w+=,.@/-]+$/;
const ACCESS_KEY_ID = /^(AKIA|ASIA)[A-Z0-9]{16}$/;
const PROJECT = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;
const SERVICE_ACCOUNT = /^[a-z][a-z0-9-]*@[a-z0-9.-]+\.iam\.gserviceaccount\.com$/;

/** Whether an address may be used: https, or plain http only to this computer (S1 `StoreAddress`). */
export function addressProblem(address: string): string | null {
  const v = address.trim();
  if (!v) return 'Enter the address of your Vault, for example https://vault.acme.internal.';
  let url: URL;
  try {
    url = new URL(v);
  } catch {
    return `${v} isn't an address. Use https://vault.acme.internal.`;
  }
  if (url.protocol === 'https:') return null;
  if (url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) return null;
  return 'Use https://. Plain http is allowed only to this computer (127.0.0.1).';
}

/** The rule of one field for one kind, in the core's words; null when it is fine or not on this kind's form. */
export function fieldProblem(kind: SecretStoreKind, key: keyof ConnectModel, m: ConnectModel): string | null {
  const v = (m[key] ?? '').trim();
  switch (kind) {
    case 'aws-sm':
      if (key === 'region') return REGION.test(v) ? null : 'Choose the region of the secrets.';
      if (key === 'roleArn') {
        if (!v) return 'Enter the role the gateway assumes, for example arn:aws:iam::412398765012:role/gw-secrets-reader.';
        return ROLE_ARN.test(v) ? null : `${v} isn't a role ARN. It looks like arn:aws:iam::412398765012:role/gw-secrets-reader.`;
      }
      if (key === 'profile' && m.awsSource === 'profile') return v ? null : 'Name the profile in ~/.aws, for example default.';
      if (key === 'accessKeyId' && m.awsSource === 'accessKey') {
        if (!v) return 'Enter the access key ID.';
        return ACCESS_KEY_ID.test(v) ? null : 'An access key ID starts with AKIA or ASIA and has 20 characters.';
      }
      return null;
    case 'vault':
      if (key === 'address') return addressProblem(v);
      if (key === 'roleId' && m.method === 'approle') return v ? null : 'Enter the AppRole role ID.';
      if (key === 'caCert' && v && !v.includes('-----BEGIN CERTIFICATE-----')) return 'Paste a PEM certificate (-----BEGIN CERTIFICATE-----), or leave it empty.';
      return null;
    case 'gcp-sm':
      if (key === 'project') {
        if (!v) return 'Enter the project of the secrets, for example acme-stage.';
        return PROJECT.test(v) ? null : `${v} isn't a project ID: 6–30 lowercase letters, digits and dashes.`;
      }
      if (key === 'impersonate' && v && !SERVICE_ACCOUNT.test(v)) return `${v} isn't a service account. It looks like gw-secrets@acme-stage.iam.gserviceaccount.com.`;
      return null;
  }
}

/** A new ExternalId for an AWS role (report 09, as the core makes one: `gw-ext-` and 12 hex digits). Not a secret. */
export function externalId(bytes: Uint8Array = crypto.getRandomValues(new Uint8Array(6))): string {
  return `gw-ext-${Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')}`;
}

/**
 * The store's name (S1 wants a unique one; the kit's dialog doesn't ask): the kind, made specific where it can
 * (`gcp-acme-stage`, `aws-eu-central-1`, `vault`), with `-2`, `-3` when taken.
 */
export function storeName(kind: SecretStoreKind, m: ConnectModel, taken: readonly string[]): string {
  const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '');
  const base = kind === 'vault' ? 'vault' : kind === 'gcp-sm' ? `gcp-${slug(m.project) || 'secrets'}` : `aws-${slug(m.region) || 'secrets'}`;
  const name = base.slice(0, 60);
  if (!taken.includes(name)) return name;
  for (let i = 2; ; i++) if (!taken.includes(`${name}-${i}`)) return `${name}-${i}`;
}

/** The request for `POST /api/secret-stores`: only the fields of the kind's form and sign-in method. */
export function connectRequest(kind: SecretStoreKind, name: string, m: ConnectModel, secrets: { readonly [pointer: string]: string }): SecretStoreCreate {
  const t = (s: string) => s.trim();
  let config: JsonObject;
  if (kind === 'aws-sm') {
    const credentials: JsonObject = { source: m.awsSource };
    if (m.awsSource === 'profile') credentials['profile'] = t(m.profile);
    else credentials['accessKeyId'] = t(m.accessKeyId);
    if (t(m.roleArn)) {
      credentials['roleArn'] = t(m.roleArn);
      credentials['externalId'] = m.externalId;
    }
    config = { region: t(m.region), credentials };
  } else if (kind === 'vault') {
    const auth: JsonObject = { method: m.method };
    if (m.method === 'approle') auth['roleId'] = t(m.roleId);
    config = { address: t(m.address).replace(/\/+$/, ''), auth };
    if (t(m.namespace)) config['namespace'] = t(m.namespace);
    if (t(m.caCert)) config['caCert'] = m.caCert.trim();
  } else {
    const credentials: JsonObject = { source: m.gcpSource };
    if (t(m.impersonate)) credentials['impersonate'] = t(m.impersonate);
    config = { project: t(m.project), credentials };
  }
  return Object.keys(secrets).length ? { kind, name, config, secrets } : { kind, name, config };
}
