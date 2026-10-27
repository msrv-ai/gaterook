// Editorial source for the static integration guides. Configuration and tool names
// are checked against connectors/*/src/main/resources/connectors and the connection wizard.
// Example responses are illustrative fixtures, not measurements or live resources.
const sqlTools = [
  { name: 'sql_list_schemas', description: 'Discover the schemas, databases and tables visible to the connection user.', mode: 'Read only' },
  { name: 'sql_describe_table', description: 'Inspect columns, types, indexes and foreign keys before writing a query.', mode: 'Read only' },
  { name: 'sql_query', description: 'Run a read query or EXPLAIN in a read-only transaction, with endpoint limits.', mode: 'Read only' },
  { name: 'sql_execute', description: 'Propose INSERT, UPDATE or DELETE. You review and approve the exact SQL before execution.', mode: 'Approval' },
];

function sqlGuide({ slug, name, type, host, port, schema, intro, cases, faq }) {
  const connectionName = slug === 'postgresql' ? 'stage-db' : `stage-${slug}`;
  const table = type === 'postgres' ? 'public.orders' : 'orders';
  return {
    slug, name, type, category: 'Databases', icon: 'database',
    title: `${name} MCP connector for AI | GateRook`,
    description: `Connect ${name} to an MCP-compatible AI app with GateRook. Explore schemas, run read-only SQL, review changes and keep database credentials out of prompts.`,
    headline: `Connect ${name} to your AI, with control over every query.`,
    intro, connectionName,
    config: [
      { field: 'Connector type', value: type === 'postgres' ? 'PostgreSQL' : 'MySQL', help: name === 'MariaDB' ? 'MariaDB uses the built-in MySQL connector and its database settings.' : 'Choose the built-in connector in Connections.' },
      { field: 'Name / environment', value: `${connectionName} / stage`, help: 'A recognizable connection name becomes the connection argument in tool calls.' },
      { field: 'Host / port', value: `${host} / ${port}`, help: 'Use a host reachable from the computer running GateRook, including its VPN.' },
      { field: 'Database', value: 'orders', help: type === 'postgres' ? 'Required for PostgreSQL. This is the database to connect to.' : 'Optional default database; when omitted, qualify tables with the database name.' },
      { field: 'User / password', value: 'gateway_reader / Enter in GateRook (write-only)', help: 'Start with a database account that can only read. Choose Encrypted here or a configured secret store.' },
      { field: 'SSL mode', value: 'verify-full', help: 'Checks both the certificate authority and host name. Add a PEM CA certificate for a private CA.' },
      { field: type === 'postgres' ? 'Schema allowlist' : 'Database allowlist', value: schema, help: 'Limit which schemas or databases this connection exposes; environment policies can tighten the scope.' },
    ],
    steps: [
      { title: 'Prepare a database login', text: `Create or select a ${name} user with read access to the intended tables. Keep the database reachable from your desktop. A read-only login adds a database-level boundary alongside the endpoint policy.` },
      { title: `Choose ${type === 'postgres' ? 'PostgreSQL' : 'MySQL'} and configure the connection`, text: `Open Connections, choose Add connection, then ${type === 'postgres' ? 'PostgreSQL' : 'MySQL'}. Set the name, environment, host, port and database. Enter credentials in the connection form, separately from anything you paste into an AI conversation.` },
      { title: 'Set TLS, scope and query limits', text: `Keep verify-full for a server with TLS, add its private CA when needed, and restrict the ${type === 'postgres' ? 'schema' : 'database'} allowlist. Advanced settings include statement timeout, pool size and connection timeout. Policies may impose stricter limits.` },
      { title: 'Test the connection before saving', text: 'Continue to Test and review connectivity and the privileges reported for the login. Finish Add to endpoint to save the connection. Leave mutation tools Off unless you intend to review proposed changes.' },
    ],
    tools: sqlTools,
    example: {
      prompt: `Check the status and total of order 48213 in ${name}. Read only.`,
      tool: 'sql_query',
      args: { connection: connectionName, sql: `SELECT id, status, total FROM ${table} WHERE id = 48213`, limit: 10 },
      result: { columns: ['id', 'status', 'total'], rows: [[48213, 'pending', '149.00']], rowCount: 1, truncated: false },
    },
    useCases: cases,
    boundaries: [
      'sql_query runs reads in a read-only transaction. sql_execute is a separate tool and requires approval for changes; a read-only database login still cannot write after approval.',
      'The endpoint caps rows and execution time. Do not treat a truncated result as the full dataset.',
      'Unknown user-defined functions are not automatically trusted as reads: they require approval, and are refused on prod unless allowed by policy.',
      'A query result can contain application data. Choose tables, columns and access scope deliberately before exposing them to an AI app.',
    ],
    faq: [
      { q: `Is GateRook a ${name} MCP server or an AI client?`, a: `GateRook connects to ${name} and exposes selected database tools through an MCP endpoint. Your AI app is the MCP client. It receives the endpoint configuration, while the database password stays with the GateRook connection.` },
      { q: 'Can the AI change database records?', a: 'Only when you expose sql_execute in Approval mode, the policy permits the statement, the database user has permission, and you approve the exact action. There is no mode for unattended writes.' },
      ...faq,
      { q: 'Do I need to expose the database to the public internet?', a: 'No. The local GateRook core connects from your computer, so an existing VPN and private network route can be used. The database must be reachable by that computer.' },
    ],
    related: slug === 'mysql' ? ['postgresql', 'mariadb', 'redis'] : slug === 'mariadb' ? ['mysql', 'postgresql', 'http'] : ['mysql', 'mongodb', 'redis'],
  };
}

const databases = [
  sqlGuide({
    slug: 'mysql', name: 'MySQL', type: 'mysql', host: 'mysql.stage.internal', port: 3306, schema: 'orders',
    intro: 'Use your MCP-compatible AI app to inspect a MySQL schema, investigate an order or explain a query without passing it a database password. GateRook provides the built-in MySQL connector, a scoped MCP endpoint and a visible approval step for changes.',
    cases: [
      { title: 'Understand an unfamiliar schema', text: 'Ask the agent to list the available tables, describe their columns and follow foreign keys before it proposes a join. The database allowlist keeps the exploration inside the databases you selected.' },
      { title: 'Investigate an application issue', text: 'Read the status of a specific order or compare grouped counts with a limited SELECT. Pair the endpoint with Redis or HTTP access to compare persisted state with the cache or API response.' },
      { title: 'Review a proposed correction', text: 'Let the agent prepare a targeted UPDATE through sql_execute. GateRook shows the exact statement for approval; the database account and environment policy still decide which changes are possible.' },
    ],
    faq: [{ q: 'Does the MySQL connector also support MariaDB?', a: 'Yes. The MySQL dialect explicitly handles MySQL and MariaDB, including their different statement timeout settings. Use MySQL in the connection picker for either server; the MariaDB guide describes that setup.' }],
  }),
  sqlGuide({
    slug: 'postgresql', name: 'PostgreSQL', type: 'postgres', host: 'postgres.stage.internal', port: 5432, schema: 'public',
    intro: 'Give your AI app a controlled path to PostgreSQL through MCP. Explore tables, inspect indexes and foreign keys, run limited SELECT queries, and review proposed changes in GateRook. Credentials belong to the connection, while the endpoint decides which tools the agent can use.',
    cases: [
      { title: 'Trace relationships in a database', text: 'Use schema discovery and table descriptions to inspect primary keys, foreign keys and indexes. The agent can explain a relationship using the actual schema instead of guessing table names from your prompt.' },
      { title: 'Investigate query performance', text: 'Ask for EXPLAIN of a specific SELECT and compare it with the available indexes. Start with a bounded query and a statement timeout; database work still consumes resources even when it only reads.' },
      { title: 'Separate environments', text: 'Keep a stage connection and a production read connection on separate endpoints or with distinct modes. The visible environment and per-connection permissions make the intended target clear in each call.' },
    ],
    faq: [{ q: 'How do I restrict access to a PostgreSQL schema?', a: 'Set the connection Schema allowlist, keep the database role privileges narrow, and apply the environment policy. The default schema is public unless changed in Advanced settings.' }],
  }),
  sqlGuide({
    slug: 'mariadb', name: 'MariaDB', type: 'mysql', host: 'mariadb.stage.internal', port: 3306, schema: 'orders',
    intro: 'Connect MariaDB to an MCP-compatible AI app using GateRook’s built-in MySQL connector. The SQL implementation recognizes MariaDB and applies its database behavior, including MariaDB statement timeouts. You get schema discovery, read queries and explicit approval for changes through the same endpoint model.',
    cases: [
      { title: 'Connect an existing MariaDB application', text: 'Reuse a narrowly scoped MariaDB login and select MySQL in the connection wizard. The server stays on your existing network; GateRook supplies the MCP tools your AI app can discover.' },
      { title: 'Explain a data discrepancy', text: 'Ask the agent to inspect a table definition and run a limited query for a specific record. Compare the result with an API response on the same endpoint without giving the agent either service credential.' },
      { title: 'Inspect schema during a migration', text: 'Compare table definitions, indexes and read results across separately named connections. Keep writes disabled while investigating differences between MySQL and MariaDB environments.' },
    ],
    faq: [{ q: 'Why is there no separate MariaDB type in the picker?', a: 'MariaDB is handled by the built-in MySQL connector. Choose MySQL and enter the MariaDB server address, database and credentials. GateRook recognizes the server from its version information.' }, { q: 'Which MariaDB generation is targeted?', a: 'The connector implementation targets MariaDB 10.6 and later. Run Test connection against your actual server before exposing its tools to an endpoint.' }],
  }),
  {
    slug: 'redis', name: 'Redis', type: 'redis', category: 'Databases', icon: 'database',
    title: 'Redis MCP connector for AI | GateRook',
    description: 'Connect Redis to an AI app through MCP. Scan allowed keys, inspect values and TTLs, and troubleshoot cache data with the read-only GateRook connector.',
    headline: 'Let your AI inspect Redis, one scoped read at a time.',
    intro: 'Investigate cache entries, key types and expiry through your MCP-compatible AI app. GateRook exposes two read tools for Redis: key scanning and value inspection. Set allowed key patterns, exclude sensitive namespaces and keep the Redis password in the connection.',
    connectionName: 'stage-cache',
    config: [
      { field: 'Name / environment', value: 'stage-cache / stage', help: 'Choose Redis in Connections, then name the resource.' },
      { field: 'Host / port', value: 'redis.stage.internal / 6379', help: 'Connect to a standalone Redis server or a replica, reachable from this computer.' },
      { field: 'Database', value: '0', help: 'Select the logical database index. The default is 0.' },
      { field: 'ACL user / password', value: 'gateway_reader / Enter in GateRook (write-only)', help: 'Credentials are optional for a server without authentication. An empty ACL user uses password authentication for the default user.' },
      { field: 'Use TLS / TLS verification', value: 'On / Verify the CA and host name', help: 'Use the TLS setting appropriate to the server; add its private CA if necessary. Production defaults to TLS.' },
      { field: 'Keys the agent may read', value: 'cache:order:*', help: 'Redis glob allowlist. An empty allowlist permits every key that is not denied.' },
      { field: 'Keys the agent never reads', value: 'session:*, *token*', help: 'The denylist excludes sensitive key namespaces. Policies may add more exclusions.' },
    ],
    steps: [
      { title: 'Choose a Redis server and ACL', text: 'Use Redis 6 or later in standalone mode, or a directly reachable replica. Prepare a narrowly scoped read ACL. Cluster routing and Sentinel discovery are not supported by this connector.' },
      { title: 'Add and authenticate the connection', text: 'Open Connections, select Add connection and Redis. Set the host, port, database, environment and TLS settings. Enter the ACL user and password in GateRook when authentication is required.' },
      { title: 'Restrict keys and result size', text: 'In Advanced, add key allowlist and denylist patterns. Set command timeout and maximum value size for the data you expect. Keep session and token namespaces outside the exposed scope.' },
      { title: 'Test and save', text: 'Run Test connection and review the reported server and access checks. Finish Add to endpoint, then expose only redis_scan and redis_get to the AI app.' },
    ],
    tools: [
      { name: 'redis_scan', description: 'Scan keys by Redis glob, with optional type and TTL. Continue using nextCursor.', mode: 'Read only' },
      { name: 'redis_get', description: 'Read a string, hash, list, set, sorted set or stream with type, TTL and bounded values.', mode: 'Read only' },
    ],
    example: { prompt: 'Check the cached state and expiry of order 48213. Do not change any keys.', tool: 'redis_get', args: { connection: 'stage-cache', key: 'cache:order:48213', limit: 10 }, result: { key: 'cache:order:48213', type: 'hash', exists: true, ttlMs: 120000, persistent: false, value: { status: 'pending', total: '149.00' }, returned: 2, truncated: false } },
    useCases: [
      { title: 'Find a stale cache entry', text: 'Read the cached status and TTL of a known order, then compare it with a SQL query. The agent can explain a mismatch without deleting or replacing the key.' },
      { title: 'Explore a permitted namespace', text: 'Scan cache:order:* with a bounded count, inspect key types and follow the returned cursor. A scan is incremental and may repeat keys across pages.' },
      { title: 'Understand queues and counters', text: 'Inspect a bounded page of a list, sorted set or stream and explain its values. The connector exposes read operations, not a general Redis command terminal.' },
    ],
    boundaries: ['Redis Cluster and Sentinel are not supported. Connect to a standalone server or directly to a replica.', 'There are no SET, DEL, FLUSHALL or arbitrary-command tools. This connector only reads.', 'SCAN can repeat keys across pages and does not produce a transactional snapshot.', 'The optional no-touch behavior needs Redis 7.2 or later. Result size, element count and command timeouts bound each read.'],
    faq: [
      { q: 'Can the AI delete a Redis key?', a: 'No. The built-in connector only publishes redis_get and redis_scan. It has no mutation tool or arbitrary command input.' },
      { q: 'Does this work with Redis Cluster or Sentinel?', a: 'Not with cluster routing or Sentinel discovery. Use a supported standalone server or a replica that GateRook can reach directly.' },
      { q: 'Can I exclude session keys?', a: 'Yes. Add session:* and any other sensitive patterns to the key denylist. An allowlist such as cache:order:* narrows the scope further.' },
    ], related: ['mysql', 'postgresql', 'mongodb'],
  },
  {
    slug: 'mongodb', name: 'MongoDB', type: 'mongodb', category: 'Databases', icon: 'database',
    title: 'MongoDB MCP connector for AI | GateRook',
    description: 'Connect MongoDB to AI through MCP. Find documents, run read-only aggregations and scope collections with GateRook, without putting credentials in prompts.',
    headline: 'Explore MongoDB with AI and a clear collection boundary.',
    intro: 'Use MCP to inspect MongoDB documents and read-only aggregation results from your AI app. GateRook keeps credentials separate from the connection URI, limits exposed collections and makes returned documents and query limits visible.',
    connectionName: 'stage-documents',
    config: [
      { field: 'Name / environment', value: 'stage-documents / stage', help: 'Choose MongoDB as the connector type.' },
      { field: 'Connection string', value: 'mongodb://mongo.stage.internal:27017/orders', help: 'Do not embed a user name or password. A mongodb+srv:// URI enables TLS.' },
      { field: 'User / password', value: 'gateway_reader / Enter in GateRook (write-only)', help: 'Use a MongoDB account with the read role for the intended databases.' },
      { field: 'Auth database / mechanism', value: 'admin / auto', help: 'SCRAM-SHA-256 and SCRAM-SHA-1 can also be selected explicitly.' },
      { field: 'Use TLS / read preference', value: 'On / secondaryPreferred', help: 'Use settings supported by the deployment. Production defaults to TLS and secondaryPreferred.' },
      { field: 'Collections the agent may read', value: 'orders.orders', help: 'Namespace patterns use database.collection or database.*.' },
    ],
    steps: [
      { title: 'Prepare a read account and network route', text: 'Make the MongoDB deployment reachable from the GateRook computer. Use a database user with read privileges and check any deployment IP access rules.' },
      { title: 'Add MongoDB without credentials in the URI', text: 'Choose Connections, Add connection, then MongoDB. Enter the mongodb:// or mongodb+srv:// address separately from the user, password and auth database in Credentials.' },
      { title: 'Scope collections and query execution', text: 'Set the namespace allowlist, query time limit and read preference. On a replica set, secondaryPreferred can direct reads away from the primary when a secondary is available.' },
      { title: 'Test the settings and attach an endpoint', text: 'Run Test connection to check connectivity and the account. Complete Add to endpoint to save. The endpoint can publish mongo_find and mongo_aggregate in Read only mode.' },
    ],
    tools: [{ name: 'mongo_find', description: 'Find documents using a filter, projection, sort, limit and skip.', mode: 'Read only' }, { name: 'mongo_aggregate', description: 'Run a bounded aggregation pipeline without write stages or server-side JavaScript.', mode: 'Read only' }],
    example: { prompt: 'Read the status and total for order 48213 from MongoDB; omit personal fields.', tool: 'mongo_find', args: { connection: 'stage-documents', database: 'orders', collection: 'orders', filter: { orderId: 48213 }, projection: { _id: 0, orderId: 1, status: 1, total: 1 }, limit: 10 }, result: { database: 'orders', collection: 'orders', documents: [{ orderId: 48213, status: 'pending', total: 149 }], returned: 1, truncated: false } },
    useCases: [{ title: 'Investigate one document', text: 'Filter by an application identifier and return only the fields needed for the question. A projection reduces unrelated data in the response.' }, { title: 'Summarize a collection', text: 'Ask for a pipeline using stages such as $match and $group to count orders by status. GateRook rejects stages that would write the result into a collection.' }, { title: 'Correlate records with logs', text: 'Put a limited MongoDB connection and a log connection on a diagnostic endpoint. The AI can compare document state with logged events using the same application identifier.' }],
    boundaries: ['The built-in MongoDB connector is read-only. It exposes no insert, update or delete tool.', 'Aggregation stages $out and $merge, and server-side JavaScript, are refused.', 'With an empty namespace allowlist, the connector permits readable application databases but excludes admin, local, config and system collections.', 'secondaryPreferred can fall back to the primary. Returned reads can be limited by document count, response size or query timeout.'],
    faq: [{ q: 'Can I use a MongoDB Atlas connection string?', a: 'The connector accepts mongodb+srv:// as well as mongodb://. Supply credentials separately, allow the GateRook computer to reach the deployment, and run Test connection against your configuration.' }, { q: 'Can the AI run a MongoDB aggregation?', a: 'Yes, through mongo_aggregate with a bounded pipeline. Write stages and server-side JavaScript are refused.' }, { q: 'How do I expose only one collection?', a: 'Set Collections the agent may read to an exact namespace such as orders.orders, and keep the database account privileges scoped to that data.' }],
    related: ['postgresql', 'redis', 'loki'],
  },
];

const logsTools = [
  { name: 'logs_search', description: 'Search a bounded time range in the backend query language, with a cursor for older results.', mode: 'Read only' },
  { name: 'logs_tail', description: 'Read new log entries since a cursor, waiting up to eight seconds for another page.', mode: 'Read only' },
];
function logGuide(data) {
  return {
    category: 'APIs & logs', type: 'logs', icon: 'scroll-text', tools: logsTools,
    title: `${data.name} MCP connector for AI | GateRook`,
    description: `Connect ${data.name} to AI through MCP. Search and follow scoped logs with GateRook, using native queries, bounded time ranges and private credentials.`,
    ...data,
    example: {
      prompt: `Find recent order 48213 errors in ${data.name} and explain the sequence.`,
      tool: 'logs_search',
      args: { connection: data.connectionName, query: data.query, from: '-15m', to: 'now', limit: 20 },
      result: { backend: data.backend, entries: [{ timestamp: '2026-10-22T12:29:00Z', severity: 'ERROR', message: 'Payment authorization timed out for order 48213' }], count: 1, truncated: false },
    },
    boundaries: [
      'This integration reads logs. It does not edit entries, change retention or administer the logging service.',
      'The connection scope, environment policy and result limits all apply. A truncated page is only part of the evidence.',
      'Log messages may contain application data or sensitive payloads. Scope the source and review the data your AI app is permitted to receive.',
      ...data.boundaries,
    ],
  };
}

const logs = [
  logGuide({
    slug: 'loki', name: 'Loki', backend: 'loki', connectionName: 'stage-logs',
    headline: 'Bring Loki logs into an AI investigation through MCP.',
    intro: 'Search Grafana Loki with LogQL from your MCP-compatible AI app. GateRook connects to the Loki API, attaches the configured tenant and credentials, and returns bounded log entries for the agent to inspect alongside database or API results.',
    config: [
      { field: 'Connector type', value: 'Loki', help: 'A built-in variant of the Logs connector.' },
      { field: 'Name / environment', value: 'stage-logs / stage', help: 'Use the connection name to target log tool calls.' },
      { field: 'URL', value: 'https://loki.stage.internal', help: 'The Loki API base URL. A Grafana Cloud Logs endpoint can also be configured.' },
      { field: 'Tenant', value: 'orders-team', help: 'Optional X-Scope-OrgID. Leave empty for single-tenant Loki.' },
      { field: 'Credentials mode', value: 'bearer', help: 'Choose none, bearer or basic to match the server. Grafana Cloud commonly uses basic authentication with its numeric user ID.' },
      { field: 'Token or password', value: 'Enter in GateRook (write-only)', help: 'Store the credential in the connection. Add a PEM certificate when Loki uses a private CA.' },
    ],
    steps: [{ title: 'Find the Loki API endpoint', text: 'Use the Loki service URL, not a Grafana dashboard URL. Confirm that the GateRook computer can reach it and obtain credentials for the intended tenant.' }, { title: 'Configure the Loki connection', text: 'In Connections, choose Add connection and Loki. Set the name, environment, URL, optional tenant and the matching authentication mode. Enter the token or password in the connection form.' }, { title: 'Test a bounded log query', text: 'Run Test connection, then finish Add to endpoint. Start with a specific stream selector and a short time range in Playground before connecting the AI app.' }],
    query: '{app="orders"} |= "48213" |= "ERROR"',
    useCases: [{ title: 'Trace a failed order', text: 'Search a single application stream for an order identifier, then compare the event with its SQL record. The response includes timestamps, severity and log messages.' }, { title: 'Follow a new failure', text: 'Use logs_tail and its returned cursor to collect subsequent entries. Each call has a bounded wait rather than an unending live stream.' }, { title: 'Work across tenants deliberately', text: 'Keep tenant-specific Loki connections clearly named and expose only the relevant connection on an endpoint.' }],
    boundaries: ['Loki queries use LogQL and need a valid stream selector. An empty query is not supported.', 'The tenant is configured on the connection; it is not a free-form tenant selector given to the AI.'],
    faq: [{ q: 'Is Grafana itself required?', a: 'GateRook talks to the Loki API. You can use a standalone Loki service or a compatible Grafana Cloud Logs endpoint; a dashboard URL is not the API address.' }, { q: 'What query language does the AI use?', a: 'logs_search uses LogQL for a Loki connection. Begin with a stream selector such as {app="orders"} and add line filters for the event you are investigating.' }, { q: 'Can I tail logs through MCP?', a: 'Yes. logs_tail returns bounded pages of new entries and a cursor for the next call, with a wait of up to eight seconds per call.' }],
    related: ['cloudwatch', 'cloud-logging', 'opensearch'],
  }),
  logGuide({
    slug: 'cloudwatch', name: 'AWS CloudWatch Logs', backend: 'cloudwatch', connectionName: 'stage-cloudwatch',
    headline: 'Investigate CloudWatch logs from your AI app.',
    intro: 'Connect AWS CloudWatch Logs to an MCP endpoint with a fixed region and explicit log groups. GateRook supports Logs Insights queries or FilterLogEvents patterns and can use an AWS profile or access key, with optional role assumption.',
    config: [
      { field: 'Connector type', value: 'AWS CloudWatch', help: 'Select this Logs variant for log search, rather than the separate AWS infrastructure connector.' },
      { field: 'Name / environment', value: 'stage-cloudwatch / stage', help: 'A dedicated connection for the selected log groups.' },
      { field: 'Region', value: 'eu-central-1', help: 'The AWS region containing the configured log groups.' },
      { field: 'Log groups', value: '/services/orders-stage', help: 'List the log group names or supported log group ARNs the agent may query.' },
      { field: 'Credentials source / profile', value: 'profile / orders-reader', help: 'The profile must exist on the GateRook computer. Access-key credentials are also supported.' },
      { field: 'Role ARN', value: 'arn:aws:iam::123456789012:role/gw-readonly', help: 'Optional AssumeRole target. Its trust policy must allow the selected source identity.' },
      { field: 'Search API', value: 'insights', help: 'Use Logs Insights QL; the alternative filter mode expects FilterLogEvents patterns.' },
    ],
    steps: [{ title: 'Prepare AWS log access', text: 'Choose an existing local AWS profile or provide an access key in GateRook. Ensure the identity can read the selected log groups and run the configured search API. If using AssumeRole, configure the role trust and permissions.' }, { title: 'Set region, groups and credentials', text: 'Open Connections, Add connection and AWS CloudWatch. Enter the region, explicit log groups, credential source and optional role ARN. Keep unrelated groups out of this connection.' }, { title: 'Choose the search language and test', text: 'Select Logs Insights or Filter patterns, then run Test connection and finish Add to endpoint. The example below uses Insights. Start with a short time window and verify the result in Playground.' }],
    query: 'fields @timestamp, @message | filter @message like /48213/ and @message like /ERROR/ | sort @timestamp desc | limit 20',
    useCases: [{ title: 'Inspect a service failure', text: 'Search the log group of the affected service and correlate messages by order or request identifier, keeping the time window close to the incident.' }, { title: 'Investigate a Lambda error', text: 'Create a connection scoped to the relevant Lambda log group, then let the AI inspect returned events alongside a separately scoped AWS connection.' }, { title: 'Keep log access distinct from infrastructure access', text: 'Expose CloudWatch log search without granting the AI general AWS inventory or resource-action tools.' }],
    boundaries: ['Logs Insights queries are billed by AWS for scanned data. Narrow the time range and log groups.', 'The query language depends on Search API. A Logs Insights query is not a FilterLogEvents pattern.'],
    faq: [{ q: 'Is this the same as the AWS connector?', a: 'No. AWS CloudWatch is a Logs backend that exposes logs_search and logs_tail. The separate AWS connector covers its own infrastructure tools.' }, { q: 'Can I use an AWS profile instead of pasting a secret key?', a: 'Yes. Select profile as the credentials source and specify a profile available on the computer running GateRook. Optional role assumption uses that source identity.' }, { q: 'Will a query search my entire AWS account?', a: 'The connection has an explicit region and log group list. Keep that list narrow; tool calls can also target a configured source.' }],
    related: ['aws', 'loki', 'opensearch'],
  }),
  logGuide({
    slug: 'cloud-logging', name: 'Google Cloud Logging', backend: 'gcp-logging', connectionName: 'stage-cloud-logs',
    headline: 'Connect Google Cloud Logging to AI with scoped MCP access.',
    intro: 'Search Google Cloud logs using the Logging query language through GateRook. Select projects or log views, configure Application Default Credentials or a service account key, and optionally impersonate a dedicated service account for the connection.',
    config: [
      { field: 'Connector type', value: 'GCP Cloud Logging', help: 'The Google Cloud backend of the Logs connector.' },
      { field: 'Name / environment', value: 'stage-cloud-logs / stage', help: 'Keep environments and access scopes recognizable.' },
      { field: 'Resource names', value: 'projects/orders-stage', help: 'Use project, folder, organization, billing account or supported log view resource names. A log view provides the tightest scope.' },
      { field: 'Credentials source', value: 'adc', help: 'Use Application Default Credentials on this computer, or select serviceAccountKey and enter the JSON key in GateRook.' },
      { field: 'Impersonate', value: 'gw-reader@orders-stage.iam.gserviceaccount.com', help: 'Optional service account impersonation. The source identity needs permission to impersonate the target.' },
      { field: 'Always add this filter', value: 'resource.type="k8s_container" AND resource.labels.namespace_name="orders"', help: 'A fixed filter applied in addition to the agent query.' },
    ],
    steps: [{ title: 'Prepare the Google Cloud identity', text: 'Use gcloud auth application-default login on the GateRook computer for ADC, or prepare a service account key for the connection. Grant read access only to the intended logs. Configure impersonation permissions if you use a target service account.' }, { title: 'Choose resources and an enforced filter', text: 'Open Connections, Add connection and GCP Cloud Logging. Select the resource names, identity source and optional impersonation target. Add an always-on scope filter for the service or namespace.' }, { title: 'Test and save the connection', text: 'Run Test connection and finish Add to endpoint. Verify a short Logging query language search in Playground before using it from an AI app.' }],
    query: 'severity>=ERROR AND SEARCH("48213")',
    useCases: [{ title: 'Read a Kubernetes workload’s logs', text: 'Use a fixed k8s_container and namespace filter, then investigate specific error messages without opening unrelated application logs.' }, { title: 'Investigate structured events', text: 'Ask the AI to inspect returned jsonPayload or other structured fields and compare events by an application identifier.' }, { title: 'Use a dedicated log view', text: 'Point the connection to an existing log view to narrow its accessible data independently of the query supplied by the agent.' }],
    boundaries: ['Google Cloud IAM and log-view permissions remain authoritative. Configuring a resource does not grant access to it.', 'The connection includes a quota-per-minute setting for entries.list calls. Broad searches can still consume project API quota.'],
    faq: [{ q: 'Must I upload a service account JSON key?', a: 'No. ADC is supported, and the connection can optionally impersonate a service account. A JSON key is an alternative credential source.' }, { q: 'Can I force every query to one namespace?', a: 'Yes. Set Always add this filter to the namespace or resource condition. The connector applies that scope together with the agent’s query.' }, { q: 'Does this use SQL?', a: 'No. A GCP Cloud Logging connection uses the Logging query language. The AI should form its filter for that backend.' }],
    related: ['gcp', 'kubernetes', 'loki'],
  }),
  logGuide({
    slug: 'opensearch', name: 'OpenSearch', backend: 'opensearch', connectionName: 'stage-search-logs',
    headline: 'Search OpenSearch logs with AI through a scoped MCP endpoint.',
    intro: 'Use the OpenSearch backend of GateRook’s Logs connector to investigate indexed log events. Configure the permitted index patterns, timestamp and message fields, then let the AI search with Lucene query syntax through logs_search.',
    config: [
      { field: 'Connector type', value: 'OpenSearch', help: 'A Logs backend for searching log indices.' },
      { field: 'Name / environment', value: 'stage-search-logs / stage', help: 'A dedicated log connection, separate from other environments.' },
      { field: 'URL', value: 'https://search.stage.internal', help: 'The OpenSearch service origin, without a dashboard path.' },
      { field: 'Indices', value: 'logs-orders-*', help: 'Only the selected index patterns are exposed. Cross-cluster patterns are not supported.' },
      { field: 'Timestamp / message / severity fields', value: '@timestamp / message / log.level', help: 'Map these to the actual fields in your log documents.' },
      { field: 'Credentials mode', value: 'basic', help: 'Available modes are none, basic, apiKey, bearer and awsSigV4. Enter the credential in GateRook.' },
      { field: 'Always add this query', value: 'service.name:orders', help: 'Optional fixed Lucene query combined with the agent query.' },
    ],
    steps: [{ title: 'Identify the log index mapping', text: 'Choose the log indices and check the timestamp, message, severity and label fields. This integration is designed around log events rather than arbitrary index administration.' }, { title: 'Configure endpoint and authentication', text: 'Open Connections, Add connection and OpenSearch. Set the URL and allowed indices. Choose the authentication mode supported by the deployment; for AWS signing, configure the AWS region, service and source credentials.' }, { title: 'Restrict the query and test', text: 'Set a fixed scope query if needed, configure any private CA, then run Test connection. Complete Add to endpoint and verify a short Lucene search in Playground.' }],
    query: 'message:48213 AND log.level:ERROR',
    useCases: [{ title: 'Investigate indexed application errors', text: 'Search a log index for a specific order or request identifier and let the AI explain the returned sequence of events.' }, { title: 'Adapt to an existing log schema', text: 'Map the connector’s timestamp, message, severity and label fields to the fields your pipeline already writes.' }, { title: 'Connect an AWS-hosted deployment', text: 'Use awsSigV4 authentication with the appropriate es or aoss service setting and credentials authorized for the deployment.' }],
    boundaries: ['This is a log-search integration, not an arbitrary OpenSearch request or index-management tool.', 'Queries use Lucene query_string syntax. Indices are restricted to the connection’s patterns; cross-cluster patterns are excluded.', 'Choose authentication supported by your server. Native OpenSearch API-key support depends on server capabilities; the schema documents OpenSearch 3.7 or later for that mode.'],
    faq: [{ q: 'Can the AI create or delete an index?', a: 'No. This Logs backend only publishes logs_search and logs_tail. It does not expose index administration.' }, { q: 'Is the example Elasticsearch documentation?', a: 'No. This guide describes the OpenSearch backend implemented in GateRook. It does not promise compatibility with every Elasticsearch deployment.' }, { q: 'What if my logs use different field names?', a: 'Configure the timestamp, message, severity and label fields to match your indexed documents before testing the connection.' }],
    related: ['loki', 'cloudwatch', 'http'],
  }),
];

const http = {
  slug: 'http', name: 'HTTP API', type: 'http', category: 'APIs & logs', icon: 'globe',
  title: 'HTTP API MCP connector for AI | GateRook',
  description: 'Connect a REST or HTTP API to AI through MCP. Scope base URLs and paths, keep API keys in GateRook, and approve requests that change data.',
  headline: 'Connect your HTTP API to AI without handing over its API key.',
  intro: 'Expose a selected HTTP service through an MCP endpoint. GateRook adds the connection’s authentication, constrains destinations and paths, and separates read requests from requests that need approval. Use it for internal APIs or services with an HTTP interface.',
  connectionName: 'stage-api',
  config: [
    { field: 'Name / environment', value: 'stage-api / stage', help: 'Choose HTTP API in Connections.' },
    { field: 'Base URLs', value: 'https://api.stage.internal', help: 'Requests must remain under a configured base URL, including any base path.' },
    { field: 'Allowed paths', value: '/v1/orders/**', help: 'Path globs restrict which routes the agent may access.' },
    { field: 'Credentials type', value: 'bearer', help: 'Supported modes: none, bearer, API key in a header, API key in the query, and basic authentication.' },
    { field: 'Token', value: 'Enter in GateRook (write-only)', help: 'The gateway adds Authorization. The agent does not need to supply the service token.' },
    { field: 'GET paths that change state', value: '/v1/orders/*/retry', help: 'Mark side-effecting GET routes. http_get refuses them; http_request requires approval.' },
    { field: 'Test path', value: '/v1/orders?limit=1', help: 'Use a safe GET or HEAD path for the connection test.' },
  ],
  steps: [{ title: 'Choose an API scope and credential', text: 'Identify the HTTPS base URL and the specific routes needed for the task. Obtain a service credential with appropriately limited permissions and identify any GET routes that change state.' }, { title: 'Configure the HTTP connection', text: 'Open Connections, choose Add connection and HTTP API. Set base URLs and allowed paths. Select the authentication type and enter its token, API key or password in the connection form.' }, { title: 'Set transport and test behavior', text: 'Use the server’s TLS configuration, adding a private CA when necessary. Configure a safe test path. Test connection may identify required network allowlist or certificate settings for you to review.' }, { title: 'Test and choose the exposed tools', text: 'Run Test connection, then finish Add to endpoint. Expose http_get for reads. Enable http_request only when you intend to review actions that may change service state.' }],
  tools: [{ name: 'http_get', description: 'Send GET or HEAD to permitted routes with authentication supplied by GateRook. Off by default on prod.', mode: 'Read only' }, { name: 'http_request', description: 'Send a supported HTTP method. Requests classified as mutations require approval.', mode: 'Approval' }],
  example: { prompt: 'Fetch order 48213 from the stage API and report its status. Do not retry or modify it.', tool: 'http_get', args: { connection: 'stage-api', path: '/v1/orders/48213' }, result: { status: 200, ok: true, method: 'GET', url: 'https://api.stage.internal/v1/orders/48213', json: { id: 48213, status: 'pending', total: '149.00' }, truncated: false } },
  useCases: [{ title: 'Ask about an internal API response', text: 'Read an order or service status through a narrowly allowed route. The AI receives the response while GateRook attaches the required authentication.' }, { title: 'Compare API and database state', text: 'Expose an API connection alongside a read-only database connection to investigate differences between the application view and stored records.' }, { title: 'Review a proposed service change', text: 'Use http_request for a supported write method. GateRook shows the actual request for approval before sending a permitted mutation.' }],
  boundaries: ['Allowed base URLs and paths constrain destinations. This connector is not an unrestricted web browser or arbitrary network proxy.', 'A GET route is not automatically harmless: configure any known side-effecting GET paths explicitly.', 'Production disallows query-string API keys, plain HTTP and same-origin redirect following. Redirect following is off by default.', 'HTTP response size and execution limits apply. The remote service’s credential permissions remain authoritative.'],
  faq: [{ q: 'Can I connect an API without a dedicated MCP server?', a: 'Yes. GateRook’s HTTP connector exposes generic HTTP tools over MCP and adds the configured service authentication. Define the permitted base URLs and paths before connecting the AI app.' }, { q: 'Can I create named tools from an OpenAPI definition?', a: 'The core supports OpenAPI templates for an existing HTTP connection, but this desktop build does not yet offer OpenAPI import in Connectors. The setup in this guide uses the available http_get and http_request tools.' }, { q: 'Can the agent see my API key?', a: 'The configured secret is added by GateRook when it sends the request. Do not put it in the prompt or tool arguments. Response content is still service data, so expose only routes appropriate for your AI app.' }, { q: 'What happens to a GET endpoint that changes data?', a: 'Add its path to GET paths that change state. http_get will refuse it, and an otherwise permitted http_request requires human approval.' }],
  related: ['mysql', 'loki', 'mcp-server'],
};

export default [...databases, ...logs, http];
