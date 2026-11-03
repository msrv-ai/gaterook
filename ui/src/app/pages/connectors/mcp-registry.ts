import { McpRegistryArgument, McpRegistryPackage, McpRegistryRemote, McpRegistryServer } from '../../core/api/plugins.api';
import { joinAnd } from './connectors-view';
import { PastedServer, SSE_NOTE, fromArgv, fromUrl, serverName, valuesNote } from './mcp-paste';

/**
 * How Add MCP server takes an entry of the MCP registry (§7.5): the first remote it can connect to (Streamable HTTP,
 * else an SSE address, as given), else a package it can start over stdio (npm with npx, PyPI with uvx), else nothing,
 * with the reason.
 */
export type RegistryUse =
  | { readonly kind: 'remote'; readonly remote: McpRegistryRemote }
  | { readonly kind: 'package'; readonly pkg: McpRegistryPackage; readonly runtime: 'npx' | 'uvx' }
  | { readonly kind: 'none'; readonly reason: string };

const PACKAGE_KINDS: Readonly<Record<string, string>> = {
  npm: 'an npm package',
  pypi: 'a PyPI package',
  oci: 'a Docker image',
  nuget: 'a NuGet package',
  mcpb: 'an MCP bundle',
  cargo: 'a Cargo crate',
};

export function registryUse(s: McpRegistryServer): RegistryUse {
  const remote = s.remotes.find((r) => r.type === 'streamable-http') ?? s.remotes.find((r) => r.type === 'sse');
  if (remote) return { kind: 'remote', remote };
  for (const pkg of s.packages) {
    if ((pkg.transport ?? 'stdio') !== 'stdio') continue;
    if (pkg.registryType === 'npm') return { kind: 'package', pkg, runtime: 'npx' };
    if (pkg.registryType === 'pypi') return { kind: 'package', pkg, runtime: 'uvx' };
  }
  if (!s.packages.length) return { kind: 'none', reason: 'Its entry names no URL or package to run.' };
  if (s.packages.every((p) => (p.transport ?? 'stdio') !== 'stdio')) {
    return { kind: 'none', reason: 'Its package serves HTTP on this computer. Start it yourself, then add its local URL.' };
  }
  const kinds = [...new Set(s.packages.map((p) => PACKAGE_KINDS[p.registryType] ?? `a ${p.registryType} package`))];
  return { kind: 'none', reason: `It runs as ${joinOr(kinds)}, which GateRook doesn’t start from the registry yet. Paste the configuration from its docs instead.` };
}

/**
 * What an entry fills in Add MCP server, as a pasted configuration would: the name, the URL or the command with the
 * package's pinned version, and the names of the headers or variables the server needs (required or secret ones; the
 * review asks for their values). Parts the person must fill in stay visible — `{tenant_id}` in a URL, `<workspace>`
 * in a command — and Add MCP server refuses to review them until they are replaced.
 */
export function fromRegistry(s: McpRegistryServer): PastedServer | { readonly error: string } {
  const use = registryUse(s);
  if (use.kind === 'none') return { error: use.reason };
  const notes: string[] = s.status === 'deprecated' ? ['Its publisher marked this server deprecated.'] : [];
  const result = use.kind === 'remote' ? fromRemote(s, use.remote) : fromPackage(s, use.pkg, use.runtime);
  return 'error' in result ? result : { ...result, notes: [...result.notes, ...notes] };
}

function fromRemote(s: McpRegistryServer, remote: McpRegistryRemote): PastedServer | { readonly error: string } {
  const headers = remote.headers.filter((h) => h.required || h.secret).map((h) => h.name);
  const notes = headers.length ? [valuesNote(headers.length)] : [];
  const template = templateParts(remote.url);
  if (!template.length) return fromUrl('', remote.url, headers, notes);
  // A template ({tenant_id}) isn't a URL yet: it stays as the registry gives it, with the parts to replace named.
  const sse = /\/sse\/?$/.test(remote.url);
  const described = remote.variables.filter((v) => v.description).map((v) => `{${v.name}}: ${v.description!.replace(/\.+$/, '')}`);
  return {
    name: registryName(s.name),
    transport: 'http',
    url: remote.url,
    command: '',
    variables: headers,
    notes: [
      `Replace ${joinAnd(template)} in the URL with your own ${template.length === 1 ? 'value' : 'values'}.${described.length ? ` ${described.join('. ')}.` : ''}`,
      ...(sse ? [SSE_NOTE] : []),
      ...notes,
    ],
    others: 0,
  };
}

function fromPackage(s: McpRegistryServer, pkg: McpRegistryPackage, runtime: 'npx' | 'uvx'): PastedServer | { readonly error: string } {
  const placeholders: string[] = [];
  const runtimeArgs = args(pkg.runtimeArguments, placeholders);
  const packageArgs = args(pkg.packageArguments, placeholders);
  // The exact version, as the review counts a command pinned: `pkg@1.2.3` for npx, `--from pkg==1.2.3 pkg` for uvx.
  const command = pkg.identifier.replace(/\[[^\]]*\]$/, '');
  const spec = !pkg.version ? [pkg.identifier]
    : runtime === 'npx' ? [`${pkg.identifier}@${pkg.version}`] : ['--from', `${pkg.identifier}==${pkg.version}`, command];
  // npx asks before it installs a package, and a server's stdin isn't a person.
  const yes = runtime === 'npx' && !runtimeArgs.some((a) => a === '-y' || a === '--yes') ? ['-y'] : [];
  const variables = pkg.environmentVariables.filter((v) => v.required || v.secret).map((v) => v.name);
  const optional = pkg.environmentVariables.filter((v) => !v.required && !v.secret).map((v) => v.name);
  const result = fromArgv(registryName(s.name), [runtime, ...yes, ...runtimeArgs, ...spec, ...packageArgs], variables);
  if ('error' in result) return result;
  const blanks = [...new Set(placeholders)];
  const notes = [
    ...(blanks.length ? [`Replace ${joinAnd(blanks)} in the command with your own ${blanks.length === 1 ? 'value' : 'values'}.`] : []),
    ...(pkg.version ? [] : ['The registry names no version: pin one (@1.2.3), or the package can change on every start.']),
    ...(optional.length === 1 ? [`It also reads ${optional[0]}; add it under Variables if you need it.`] : []),
    // A server may document dozens of settings: three names, then a count.
    ...(optional.length > 1
      ? [`It also reads ${optional.length > 3 ? `${optional.slice(0, 3).join(', ')} and ${optional.length - 3} more variables` : joinAnd(optional)}; add the ones you need under Variables.`]
      : []),
  ];
  return { ...result, notes: [...result.notes, ...notes] };
}

/**
 * The arguments that go on the command line: fixed values and defaults as given, required ones without a value as a
 * `<placeholder>` to replace; optional options without a value stay off (a server documents dozens of them).
 */
function args(list: readonly McpRegistryArgument[], placeholders: string[]): string[] {
  const out: string[] = [];
  for (const a of list) {
    let value = a.given ?? null;
    if (value === null && a.required) {
      value = `<${(a.valueHint || a.name || 'value').replace(/^-+/, '')}>`;
      placeholders.push(value);
    }
    if (value === null) continue;
    placeholders.push(...templateParts(value));
    if (a.type === 'named' && a.name) out.push(a.name, value);
    else if (a.type === 'positional') out.push(value);
  }
  return out;
}

/** `{tenant_id}` parts of a URL or value the registry gives as a template. */
export function templateParts(text: string): string[] {
  return [...new Set(text.match(/\{[A-Za-z_][A-Za-z0-9_]*\}/g) ?? [])];
}

/**
 * A server name for an entry without a URL to name it after: the entry's own name (`io.github.someone/linear-mcp` →
 * `linear-mcp`), or the publisher's with `-mcp` when that one is generic (`com.example/mcp` → `example-mcp`).
 */
export function registryName(name: string): string {
  const [namespace, own = ''] = name.split('/');
  const owner = namespace.startsWith('io.github.') ? namespace.slice('io.github.'.length) : (namespace.split('.')[1] ?? namespace);
  return serverName(/^(mcp|mcp[-_]?server|server)$/i.test(own) || !own ? `${owner}-mcp` : own, null);
}

function joinOr(words: readonly string[]): string {
  return words.length <= 1 ? words.join('') : `${words.slice(0, -1).join(', ')} or ${words.at(-1)}`;
}
