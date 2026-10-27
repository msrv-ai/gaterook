// The reference modern client (report 01 §8 step 5): @modelcontextprotocol/client 2.3.1 pinned to 2026-07-28 with URL
// elicitation. The SDK fulfils input_required itself through the elicitation/create handler and retries with the
// byte-exact requestState. One JSON object per line on stdout; McpNodeClientsIT reads them and decides the requests.
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';

const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');
let answer = 'accept';

const client = new Client({ name: 'ts-sdk-it', version: '2.3.1' }, {
  versionNegotiation: { mode: { pin: '2026-07-28' } },
  capabilities: { elicitation: { url: {} } },
});
client.setRequestHandler('elicitation/create', async (request) => {
  out({ event: 'elicitation', mode: request.params.mode, url: request.params.url, message: request.params.message });
  return { action: answer };
});

const transport = new StreamableHTTPClientTransport(new URL(process.env.MCP_URL), {
  requestInit: { headers: { Authorization: `Bearer ${process.env.MCP_TOKEN}` } },
});
await client.connect(transport);
out({ event: 'connected', version: client.getNegotiatedProtocolVersion() ?? null, server: client.getServerVersion() ?? null,
  instructions: client.getInstructions() ?? null });

const tools = await client.listTools();
out({ event: 'tools', names: tools.tools.map((t) => t.name) });

const read = await client.callTool({ name: 'sql_query', arguments: { connection: 'dev-db', sql: 'SELECT id, status FROM orders' } });
out({ event: 'read', result: read });

const approved = await client.callTool({ name: 'sql_execute', arguments: { connection: 'dev-db', sql: process.env.MCP_SQL_APPROVE } });
out({ event: 'approved', result: approved });

answer = 'decline';
const declined = await client.callTool({ name: 'sql_execute', arguments: { connection: 'dev-db', sql: process.env.MCP_SQL_DECLINE } });
out({ event: 'declined', result: declined });

await client.close();
out({ event: 'done' });
