import { McpProfile } from '../../core/api/plugins.api';
import { fromProfile, profileForRegistryName, profileLogo, profileStarts } from './mcp-profiles';

/** Profiles as `GET /api/mcp-profiles` answers, and what they fill in Add MCP server. */
function profile(p: Partial<McpProfile> & { readonly id: string }): McpProfile {
  return {
    name: p.id,
    publisher: p.id,
    category: 'Cloud platform',
    description: '',
    documentation: `https://docs.example/${p.id}`,
    registryName: null,
    checked: '2026-11-11',
    transport: 'http',
    url: null,
    headers: [],
    command: [],
    placeholders: [],
    pins: [],
    signIn: 'OAuth.',
    notes: [],
    tools: [],
    ...p,
  };
}

const SUPABASE = profile({
  id: 'supabase',
  name: 'Supabase',
  registryName: 'com.supabase/mcp',
  url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true',
  placeholders: [{ name: 'project_ref', description: 'Your project’s reference, from its dashboard URL.' }],
  pins: [
    { in: 'query', name: 'read_only', setTo: 'true', purpose: 'Supabase lists only its read tools.', missing: 'Without read_only=true …', set: null },
    { in: 'query', name: 'project_ref', setTo: null, purpose: 'One project only.', missing: 'Without project_ref …', set: null },
  ],
});

const CLOUDFLARE = profile({
  id: 'cloudflare-api',
  name: 'Cloudflare API',
  url: 'https://mcp.cloudflare.com/mcp',
  tools: [{ tool: 'execute', start: 'approval', kind: 'code', reason: 'It runs code against the Cloudflare API.' }],
});

describe('Verified MCP profiles in Add MCP server', () => {
  it('fills the setup URL with its pins and leaves the person’s part to replace', () => {
    expect(fromProfile(SUPABASE)).toEqual({
      name: 'supabase-mcp',
      transport: 'http',
      url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true',
      command: '',
      variables: [],
      notes: [
        'Replace {project_ref} in the URL with your own value. {project_ref}: Your project’s reference, from its dashboard URL.',
        'read_only=true: Supabase lists only its read tools.',
      ],
      others: 0,
    });
  });

  it('says which tools start in Approval or Off', () => {
    const filled = fromProfile(CLOUDFLARE);
    expect('error' in filled ? null : filled.notes).toEqual(['Its profile starts execute in Approval.']);
    expect('error' in filled ? null : filled.name).toBe('cloudflare-api-mcp');
    expect(profileStarts([
      { tool: 'execute', start: 'approval', kind: 'code', reason: '' },
      { tool: 'railway-agent', start: 'off', kind: 'delegating', reason: '' },
      { tool: 'run_script', start: 'approval', kind: 'code', reason: '' },
    ])).toBe('execute and run_script in Approval and railway-agent Off');
    expect(profileStarts([])).toBe('');
    const many = ['a', 'b', 'c', 'd'].map((tool) => ({ tool, start: 'approval' as const, kind: 'code' as const, reason: '' }));
    expect(profileStarts([...many, { tool: 'agent', start: 'off', kind: 'delegating', reason: '' }])).toBe('4 tools in Approval and agent Off');
  });

  it('fills a setup command as a pasted one, pinned to its version', () => {
    const firebase = profile({
      id: 'firebase',
      transport: 'stdio',
      command: ['npx', '-y', 'firebase-tools@15.0.0', 'mcp'],
    });
    const filled = fromProfile(firebase);
    expect('error' in filled ? filled.error : filled.command).toBe('npx -y firebase-tools@15.0.0 mcp');
    expect('error' in filled ? null : filled.transport).toBe('stdio');
    expect('error' in filled ? null : filled.name).toBe('firebase-mcp');
  });

  it('sends a header switch as a fixed header and says so', () => {
    const devops = profile({
      id: 'azure-devops',
      name: 'Azure DevOps',
      url: 'https://mcp.dev.azure.com/{organization}',
      headers: [{ name: 'X-MCP-Readonly', text: 'true' }],
      pins: [{ in: 'header', name: 'X-MCP-Readonly', setTo: 'true', purpose: 'Only its read tools.', missing: '', set: null }],
    });
    const filled = fromProfile(devops);
    expect('error' in filled ? null : filled.fixedHeaders).toEqual([{ name: 'X-MCP-Readonly', text: 'true' }]);
    expect('error' in filled ? null : filled.notes).toContain('Header X-MCP-Readonly set to true: Only its read tools.');
    const supabase = fromProfile(SUPABASE);
    expect('error' in supabase ? null : supabase.fixedHeaders).toBeUndefined();
  });

  it('refuses a profile over HTTP without a URL', () => {
    expect(fromProfile(profile({ id: 'broken' }))).toEqual({ error: 'The profile of broken names no URL.' });
  });

  it('finds the profile of a Popular card by its exact registry name, and a bundled mark by id', () => {
    expect(profileForRegistryName([SUPABASE, CLOUDFLARE], 'com.supabase/mcp')).toBe(SUPABASE);
    expect(profileForRegistryName([SUPABASE, CLOUDFLARE], 'com.supabase/mcp-other')).toBeNull();
    expect(profileForRegistryName(null, 'com.supabase/mcp')).toBeNull();
    expect(profileLogo(SUPABASE)).toBe('mcp-logos/supabase.png');
    expect(profileLogo(profile({ id: 'netlify' }))).toBe('mcp-logos/netlify.png');
    expect(profileLogo(profile({ id: 'azure-devops' }))).toBe('mcp-logos/azure-devops.svg');
    expect(profileLogo(profile({ id: 'digitalocean' }))).toBeNull();
  });
});
