import { McpProfile, McpProfileTool } from '../../core/api/plugins.api';
import { joinAnd } from './connectors-view';
import { PastedServer, fromArgv } from './mcp-paste';
import { templateParts } from './mcp-registry';

/**
 * The vendor marks the Cloud platforms cards show, bundled with their provenance in `public/mcp-logos/SOURCES.md`; a
 * profile without one shows a cloud icon.
 */
const LOGOS: Readonly<Record<string, string>> = {
  'aws-knowledge': 'mcp-logos/aws.png',
  'azure-devops': 'mcp-logos/azure-devops.svg',
  'azure-devops-remote': 'mcp-logos/azure-devops.svg',
  'cloudflare-api': 'mcp-logos/cloudflare.jpg',
  digitalocean: 'mcp-logos/digitalocean.svg',
  firebase: 'mcp-logos/firebase.png',
  netlify: 'mcp-logos/netlify.png',
  supabase: 'mcp-logos/supabase.png',
  vercel: 'mcp-logos/vercel.png',
};

export function profileLogo(p: Pick<McpProfile, 'id'>): string | null {
  return LOGOS[p.id] ?? null;
}

/** The profile of a Popular card's registry entry, when GateRook has one: the card then sets it up from the profile. */
export function profileForRegistryName(profiles: readonly McpProfile[] | null, registryName: string): McpProfile | null {
  return profiles?.find((p) => p.registryName === registryName) ?? null;
}

/**
 * "execute in Approval" and "railway-agent Off" for the form and the review; above three tools in a group, their count
 * ("17 tools in Approval"): the review lists each one with its reason.
 */
export function profileStarts(tools: readonly McpProfileTool[]): string {
  const named = (names: readonly string[]): string => (names.length > 3 ? `${names.length} tools` : joinAnd(names));
  const approval = tools.filter((t) => t.start === 'approval').map((t) => t.tool);
  const off = tools.filter((t) => t.start === 'off').map((t) => t.tool);
  return joinAnd([
    ...(approval.length ? [`${named(approval)} in Approval`] : []),
    ...(off.length ? [`${named(off)} Off`] : []),
  ]);
}

/**
 * What a profile fills in Add MCP server, as a pasted configuration would: its server name and setup URL, with the
 * vendor's read-only switches and the parts the person replaces (`{project_ref}`), or its setup command. The review
 * then shows the profile again and warns about a switch the URL no longer sets.
 */
export function fromProfile(p: McpProfile): PastedServer | { readonly error: string } {
  const parts = p.transport === 'http' && p.url ? templateParts(p.url) : [];
  const described = p.placeholders.map((h) => `{${h.name}}: ${h.description.replace(/\.+$/, '')}`);
  const notes = [
    ...(parts.length
      ? [`Replace ${joinAnd(parts)} in the URL with your own ${parts.length === 1 ? 'value' : 'values'}.${described.length ? ` ${described.join('. ')}.` : ''}`]
      : []),
    ...p.pins.filter((pin) => pin.setTo !== null).map((pin) =>
      pin.in === 'header' ? `Header ${pin.name} set to ${pin.setTo}: ${pin.purpose}` : `${pin.name}=${pin.setTo}: ${pin.purpose}`),
    ...(p.tools.length ? [`Its profile starts ${profileStarts(p.tools)}.`] : []),
  ];
  if (p.transport === 'http') {
    if (!p.url) return { error: `The profile of ${p.name} names no URL.` };
    return {
      name: serverName(p), transport: 'http', url: p.url, command: '', variables: [], notes, others: 0,
      ...(p.headers?.length ? { fixedHeaders: p.headers } : {}),
    };
  }
  const result = fromArgv(serverName(p), p.command, []);
  return 'error' in result ? result : { ...result, notes: [...result.notes, ...notes] };
}

/** `supabase-mcp`: the profile's id, as Add MCP server names a server after its vendor. */
function serverName(p: McpProfile): string {
  return p.id.endsWith('-mcp') ? p.id : `${p.id}-mcp`;
}
