import { McpRegistryServer } from '../../core/api/plugins.api';
import { matchingOfficialMcpServerNames, officialMcpPublisher } from './mcp-official-publishers';

const REGISTRY = 'registry.modelcontextprotocol.io';
const server = (name: string, overrides: Partial<McpRegistryServer> = {}): McpRegistryServer => ({
  name, status: 'active', remotes: [], packages: [], ...overrides,
});

describe('Official MCP publisher evidence', () => {
  it('recognizes the exact Stripe entry with vendor documentation', () => {
    expect(officialMcpPublisher(server('com.stripe/mcp'), REGISTRY)).toEqual({
      name: 'Stripe', documentationUrl: 'https://docs.stripe.com/mcp',
    });
  });

  it.each([
    'app.vercel.stripecheckup/stripecheckup', 'com.insightfulpipe/stripe', 'io.github.someone/stripe',
    'com.stripe.fake/mcp', 'com.stripe/mcp-community', 'com.stripe/mcp/extra',
  ])('does not promote %s through its text, remote URL or self-declared metadata', (name) => {
    expect(officialMcpPublisher(server(name, {
      title: 'Official Stripe MCP', websiteUrl: 'https://docs.stripe.com/mcp',
      remotes: [{ type: 'streamable-http', url: 'https://mcp.stripe.com', headers: [], variables: [] }],
      listing: { verification: 'vendor', featured: true },
    }), REGISTRY)).toBeNull();
  });

  it.each([null, 'registry.example.com', 'registry.modelcontextprotocol.io.example.com'])('does not trust IDs from %s', (registry) => {
    expect(officialMcpPublisher(server('com.stripe/mcp'), registry)).toBeNull();
    expect(matchingOfficialMcpServerNames('stripe', registry)).toEqual([]);
  });

  it('keeps extra lookups bounded to curated names matching the search', () => {
    expect(matchingOfficialMcpServerNames('  STRIPE ', REGISTRY)).toEqual(['com.stripe/mcp']);
    expect(matchingOfficialMcpServerNames('github', REGISTRY)).toContain('io.github.github/github-mcp-server');
    expect(matchingOfficialMcpServerNames('mcp', REGISTRY)).not.toContain('app.linear/linear');
    expect(matchingOfficialMcpServerNames('unknown', REGISTRY)).toEqual([]);
    expect(matchingOfficialMcpServerNames('', REGISTRY)).toEqual([]);
    expect(matchingOfficialMcpServerNames('mc', REGISTRY)).toEqual([]);
  });
});
