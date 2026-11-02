import { awsTarget, cloudSecret, cloudSignInText, signingTarget } from './mcp-cloud';

/** Servers that sign in with their cloud's credentials (ARCHITECTURE.md §7.6), in the review's words. */
describe('MCP server cloud sign-ins', () => {
  it('reads the service and region of AWS hosts as the core does', () => {
    expect(awsTarget('aws-mcp.us-east-1.api.aws')).toEqual({ service: 'aws-mcp', region: 'us-east-1' });
    expect(awsTarget('bedrock-agentcore.us-west-2.amazonaws.com')).toEqual({ service: 'bedrock-agentcore', region: 'us-west-2' });
    expect(awsTarget('mcp.linear.app')).toBeNull();
    expect(signingTarget({ region: 'eu-west-2' }, 'https://aws-mcp.us-east-1.api.aws/mcp')).toEqual({ service: 'aws-mcp', region: 'eu-west-2' });
    expect(signingTarget({}, 'https://mcp.example.com/mcp')).toBeNull();
  });

  it('says how a server signs in, and which key the review asks for', () => {
    expect(cloudSignInText({ type: 'google', credentials: { source: 'serviceAccountKey', impersonate: 'gw@acme.iam.gserviceaccount.com' } }, ''))
      .toBe('A Google token from a service account key, acting as gw@acme.iam.gserviceaccount.com. GateRook gets a new one before it expires.');
    expect(cloudSignInText({ type: 'aws', credentials: { source: 'profile' }, service: 'bedrock-agentcore', region: 'us-west-2' }, 'https://x.dev/mcp'))
      .toBe('Each request signed with SigV4 for bedrock-agentcore in us-west-2, with the profile default of this computer.');
    expect(cloudSignInText({ type: 'aws', credentials: { source: 'profile', profile: 'ops', roleArn: 'arn:aws:iam::123456789012:role/gw' } }, 'https://aws-mcp.eu-west-1.api.aws/mcp'))
      .toBe('Each request signed with SigV4 for aws-mcp in eu-west-1, with the profile ops of this computer, as the role arn:aws:iam::123456789012:role/gw.');
    expect(cloudSecret({ type: 'google', credentials: { source: 'adc' } })).toBeNull();
    expect(cloudSecret({ type: 'aws', credentials: { source: 'accessKey' } })?.pointer).toBe('/auth/credentials/secretAccessKey');
    expect(cloudSecret({ type: 'oauth' })).toBeNull();
  });
});
