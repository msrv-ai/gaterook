import { McpRegistryServer } from '../../core/api/plugins.api';

export interface OfficialMcpPublisher {
  readonly name: string;
  readonly documentationUrl: string;
}

const OFFICIAL_REGISTRY = 'registry.modelcontextprotocol.io';

/**
 * Exact entries checked against the vendor's documentation and the Official MCP Registry on 2026-11-07.
 * Namespace ownership alone does not establish that a publisher owns the service an integration connects to.
 * Keep this list conservative: add an ID only with vendor evidence; never infer it from a title, URL or _meta.
 * This identifies the publisher, not an audit of the server's behavior or a promise about every official server.
 */
const PUBLISHERS: ReadonlyMap<string, OfficialMcpPublisher> = new Map([
  ['com.stripe/mcp', { name: 'Stripe', documentationUrl: 'https://docs.stripe.com/mcp' }],
  ['app.linear/linear', { name: 'Linear', documentationUrl: 'https://linear.app/docs/mcp' }],
  ['io.github.getsentry/sentry-mcp', { name: 'Sentry', documentationUrl: 'https://mcp.sentry.dev/' }],
  ['io.github.github/github-mcp-server', { name: 'GitHub', documentationUrl: 'https://github.com/github/github-mcp-server' }],
  ['io.github.mongodb-js/mongodb-mcp-server', { name: 'MongoDB', documentationUrl: 'https://www.mongodb.com/docs/mcp-server/' }],
  ['com.notion/mcp', { name: 'Notion', documentationUrl: 'https://developers.notion.com/guides/mcp/get-started-with-mcp' }],
  ['com.atlassian/atlassian-mcp-server', { name: 'Atlassian', documentationUrl: 'https://github.com/atlassian/atlassian-mcp-server' }],
  ['io.github.paypal/paypal-mcp-server', { name: 'PayPal', documentationUrl: 'https://developer.paypal.com/ai-tools/mcp-server' }],
]);

/** Only the canonical registry's verified name can match this locally maintained publisher evidence. */
export function officialMcpPublisher(server: McpRegistryServer, registry: string | null): OfficialMcpPublisher | null {
  return registry === OFFICIAL_REGISTRY ? PUBLISHERS.get(server.name) ?? null : null;
}

/** Bounded exact-name lookups bring known official matches onto the first page without scanning the registry. */
export function matchingOfficialMcpServerNames(query: string, registry: string | null): readonly string[] {
  const q = query.trim().toLowerCase();
  if (registry !== OFFICIAL_REGISTRY || q.length < 3) return [];
  return [...PUBLISHERS.keys()].filter((name) => name.toLowerCase().includes(q));
}
