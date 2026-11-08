import { McpRegistryPage, McpRegistryServer } from '../../core/api/plugins.api';
import { OfficialMcpPublisher, matchingOfficialMcpServerNames, officialMcpPublisher } from './mcp-official-publishers';
import { RegistryUse, registryUse } from './mcp-registry';

/** One result of Find a server: the entry, the name it is shown under, how it would be added and where it runs. */
export interface RegistryResult {
  readonly server: McpRegistryServer;
  readonly title: string;
  readonly use: RegistryUse;
  /** The host of its URL, or the package command (`npx @sentry/mcp-server@0.42.0`). */
  readonly target: string;
  readonly officialPublisher: OfficialMcpPublisher | null;
}

export function registryResult(server: McpRegistryServer, registry: string | null = null): RegistryResult {
  const use = registryUse(server);
  let target = '';
  if (use.kind === 'remote') {
    try {
      target = new URL(use.remote.url).host;
    } catch {
      target = use.remote.url;
    }
  } else if (use.kind === 'package') {
    target = `${use.runtime} ${use.pkg.identifier}${use.pkg.version ? `@${use.pkg.version}` : ''}`;
  }
  return { server, title: server.title || server.name.split('/')[1] || server.name, use, target,
    officialPublisher: officialMcpPublisher(server, registry) };
}

/** Keep upstream order within each group and one row per server, including across pages. */
export function rankRegistryResults(rows: readonly RegistryResult[]): readonly RegistryResult[] {
  const seen = new Set<string>();
  return rows.filter((row) => {
    if (seen.has(row.server.name)) return false;
    seen.add(row.server.name);
    return true;
  }).sort((a, b) => Number(!!b.officialPublisher) - Number(!!a.officialPublisher));
}

/** Bring known official publishers onto the first page, without changing the configured registry. */
export async function completeRegistryResults(
  page: McpRegistryPage,
  query: string,
  search: (name: string) => Promise<McpRegistryPage>,
): Promise<readonly RegistryResult[]> {
  const shown = new Set(page.servers.map((server) => server.name));
  const officialPages = await Promise.all(matchingOfficialMcpServerNames(query, page.registry)
    .filter((name) => !shown.has(name))
    .map(async (name) => {
      try {
        const officialPage = await search(name);
        return officialPage.registry === page.registry ? officialPage.servers.filter((server) => server.name === name) : [];
      } catch {
        return [];
      }
    }));
  return rankRegistryResults([...page.servers, ...officialPages.flat()]
    .map((server) => registryResult(server, page.registry)));
}
