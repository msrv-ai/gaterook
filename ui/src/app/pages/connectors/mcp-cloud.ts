import { McpServerAuthConfig } from '../../core/api/plugins.api';

/**
 * Servers that sign in with their cloud's credentials (ARCHITECTURE.md §7.6): Google's MCP servers take a token from
 * Google sign-in on this computer or a service account key, AWS's take requests signed with SigV4. GateRook makes the
 * token or signature itself; the agent never sees either.
 */

/** The secret field of a Google service account key: typed in the review, stored as a secret of the server. */
export const SERVICE_ACCOUNT_KEY = '/auth/credentials/serviceAccountKey';
/** The secret field of an AWS secret access key. */
export const SECRET_ACCESS_KEY = '/auth/credentials/secretAccessKey';

const REGION = '[a-z]{2}(?:-[a-z]+)+-\\d+';
const API_AWS = new RegExp(`^([a-z0-9-]+)\\.(${REGION})\\.api\\.aws$`);
const AMAZONAWS = new RegExp(`^(?:[a-z0-9-]+\\.)*?([a-z0-9-]+)\\.(${REGION})\\.amazonaws\\.com$`);

export interface AwsTarget {
  readonly service: string;
  readonly region: string;
}

/**
 * The service and region an AWS host names, as the core reads it: `aws-mcp.us-east-1.api.aws` → `aws-mcp` in
 * `us-east-1`, `bedrock-agentcore.us-west-2.amazonaws.com` → `bedrock-agentcore` in `us-west-2`; null for another host.
 */
export function awsTarget(host: string): AwsTarget | null {
  const h = host.toLowerCase();
  const m = API_AWS.exec(h) ?? AMAZONAWS.exec(h);
  return m ? { service: m[1], region: m[2] } : null;
}

/** What a server's requests are signed for: the settings' service and region, each else what its URL's host names. */
export function signingTarget(auth: Pick<McpServerAuthConfig, 'service' | 'region'> | null | undefined, url: string): AwsTarget | null {
  let host = '';
  try {
    host = new URL(url).hostname;
  } catch {
    host = '';
  }
  const named = awsTarget(host);
  const service = auth?.service || named?.service;
  const region = auth?.region || named?.region;
  return service && region ? { service, region } : null;
}

/** How a server signs in with its cloud's credentials, in one sentence for the review and its row. */
export function cloudSignInText(auth: McpServerAuthConfig, url: string): string {
  const c = auth.credentials;
  if (auth.type === 'google') {
    const from = c?.source === 'serviceAccountKey' ? 'a service account key' : 'the Google sign-in of this computer';
    return (
      `A Google token from ${from}` +
      (c?.impersonate ? `, acting as ${c.impersonate}` : '') +
      (c?.quotaProject ? `, billed to ${c.quotaProject}` : '') +
      '. GateRook gets a new one before it expires.'
    );
  }
  const target = signingTarget(auth, url);
  const from = c?.source === 'accessKey' ? `the access key ${c.accessKeyId ?? ''}`.trimEnd() : `the profile ${c?.profile || 'default'} of this computer`;
  return (
    `Each request signed with SigV4${target ? ` for ${target.service} in ${target.region}` : ''}, with ${from}` +
    (c?.roleArn ? `, as the role ${c.roleArn}` : '') +
    '.'
  );
}

/** The secret a cloud sign-in needs typed in the review, or null: the service account key, the secret access key. */
export function cloudSecret(auth: McpServerAuthConfig | null | undefined): { readonly label: string; readonly pointer: string; readonly multiline: boolean } | null {
  if (auth?.type === 'google' && auth.credentials?.source === 'serviceAccountKey') {
    return { label: 'Service account key (JSON)', pointer: SERVICE_ACCOUNT_KEY, multiline: true };
  }
  if (auth?.type === 'aws' && auth.credentials?.source === 'accessKey') {
    return { label: 'Secret access key', pointer: SECRET_ACCESS_KEY, multiline: false };
  }
  return null;
}
