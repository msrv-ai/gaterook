import { EndpointDetail, ToolGroup, WiredConnection } from '../../core/api/endpoints.api';
import { setupWiring } from './setup-map/setup-map';
import { toolPolicyGroups } from './tool-policy-list/tool-policy-view';

/** Parts of `GET /api/endpoints/dev` and `/tools` as the GW_DEMO core answers them (2026-10-25). */
const CONNECTIONS: WiredConnection[] = [
  { name: 'dev-db', type: 'postgres', typeName: 'PostgreSQL', env: 'dev', health: 'unknown', problem: null, tools: { read: 3, approval: 1, off: 0 } },
  { name: 'billing-api', type: 'http', typeName: 'HTTP API', env: 'stage', health: 'unreachable', problem: null, tools: { read: 0, approval: 1, off: 1 } },
];

describe('page adapters of the admin API', () => {
  it('setupWiring: the endpoint detail as the wiring card draws it', () => {
    const detail = {
      endpoint: {
        slug: 'dev', url: 'http://127.0.0.1:7465/mcp/dev', env: 'stage', tools: { read: 14, approval: 3, off: 5 },
        tokens: { active: 1, expiringSoon: 1 },
      },
      connections: CONNECTIONS,
      settings: { oauthEnabled: false },
    } as unknown as EndpointDetail;
    expect(setupWiring(detail)).toEqual({
      slug: 'dev',
      url: 'http://127.0.0.1:7465/mcp/dev',
      tools: { read: 14, approval: 3, off: 5 },
      tokens: 1,
      oauth: false,
      policy: 'stage rules · mutations need approval',
      connections: [
        { name: 'dev-db', type: 'postgres', env: 'dev', health: 'unknown', latencyMs: null },
        { name: 'billing-api', type: 'http', env: 'stage', health: 'unreachable', latencyMs: null },
      ],
      clients: [],
    });
  });

  it('toolPolicyGroups: each group gets its connection\'s type; the core group is the built-in tools', () => {
    const groups = [
      { group: 'PostgreSQL', connection: 'dev-db', env: 'dev', tools: [] },
      { group: 'Core', connection: null, env: null, tools: [] },
    ] as unknown as ToolGroup[];
    expect(toolPolicyGroups(groups, CONNECTIONS).map((g) => [g.type, g.detail])).toEqual([
      ['postgres', 'PostgreSQL'],
      [null, 'Answer from the gateway, never from a resource'],
    ]);
  });
});
