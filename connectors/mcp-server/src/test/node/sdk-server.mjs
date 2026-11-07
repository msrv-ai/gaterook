// A server of the TypeScript MCP SDK 2.3.1 (both protocol eras on one entry), for the interop test of the gateway's
// upstream client (SdkServersTest, profile mcp-servers). The package resolves from the repository's node_modules.
//
//   node sdk-server.mjs stdio        serves MCP on stdin/stdout (serveStdio: the opening message picks the era)
//   node sdk-server.mjs http         listens on 127.0.0.1, prints "listening <port>" (createMcpHandler, with the
//                                    stateless 2025 fallback)
import http from 'node:http';
import { McpServer, createMcpHandler } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod';

function factory() {
  const server = new McpServer({ name: 'sdk-fixture', version: '2.3.1-fixture' },
    { capabilities: { tools: { listChanged: true } }, instructions: 'SDK fixture instructions.' });
  server.registerTool('echo', {
    description: 'Echoes text back.',
    inputSchema: { text: z.string() },
    annotations: { readOnlyHint: true, destructiveHint: false },
  }, async ({ text }) => ({ content: [{ type: 'text', text }] }));
  server.registerTool('region', {
    description: 'Answers the region it was asked about.',
    inputSchema: { region: z.string().meta({ 'x-mcp-header': 'Region' }) },
    annotations: { readOnlyHint: true },
  }, async ({ region }) => ({ content: [{ type: 'text', text: `region ${region}` }] }));
  return server;
}

const mode = process.argv[2] ?? 'stdio';
if (mode === 'stdio') {
  serveStdio(factory);
} else {
  const handler = createMcpHandler(factory, { legacy: 'stateless' });
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = chunks.length ? Buffer.concat(chunks) : undefined;
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) {
      if (typeof v === 'string') headers.set(k, v);
    }
    const abort = new AbortController();
    res.on('close', () => abort.abort());
    const request = new Request(`http://127.0.0.1${req.url}`, {
      method: req.method, headers, body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
      signal: abort.signal,
    });
    const response = await handler.fetch(request);
    res.writeHead(response.status, Object.fromEntries(response.headers.entries()));
    if (!response.body) {
      res.end();
      return;
    }
    const reader = response.body.getReader();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        res.write(value);
      }
    } catch {
      // The client went away.
    }
    res.end();
  });
  server.listen(0, '127.0.0.1', () => {
    process.stdout.write(`listening ${server.address().port}\n`);
  });
}
