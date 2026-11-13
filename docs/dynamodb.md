# Control AI access to Amazon DynamoDB

GateRook's MCP Firewall lets AI apps read DynamoDB tables with bounded queries and holds every change for your review. With an IAM role, AWS itself limits each call to what it needs. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect with a role

The built-in **Amazon DynamoDB** connector (`dynamodb`) calls DynamoDB's JSON API in one region, signed with SigV4. There is no DynamoDB SDK in GateRook; requests go only to `dynamodb.<region>.amazonaws.com` (`.amazonaws.com.cn` in China). Choose the region and the credentials:

- an **access key** (stored encrypted in GateRook), or an **AWS profile** of this computer (`~/.aws/config` and `~/.aws/credentials`). A profile may not use SSO, `credential_source`, `web_identity_token_file` or a `role_arn` of its own; a `credential_process` runs with a warning, though never for the form's suggestions;
- optionally a **role to assume**, with an external ID in its trust policy.

With a role, **Session policy** defaults to each call's own permissions: a read assumes the role with a session policy that allows only `DescribeTable`, `DescribeTimeToLive`, `ListTables`, `Query`, `Scan`, `GetItem`, `BatchGetItem` and `PartiQLSelect` on the connection's tables; an approved change gets `PartiQLInsert`, `PartiQLUpdate` and `PartiQLDelete` on its one table. AWS enforces these, whatever the role itself may do. On prod the session policy can't be turned off. Without a role, each call has every permission of the key or profile: **Test connection** says so, as a warning outside dev.

**Tables** lists the tables agents may use (`*` matches any characters, as in `orders-*`). Empty, every table the credentials may read. Test connection reports the identity AWS sees (`sts:GetCallerIdentity`) and the tables agents may use.

While you fill in the form, GateRook suggests the profiles of `~/.aws/config` and fills in the profile's region. Once the form can sign in, with the same credentials and read session as Test connection, it lists the region's tables under **Tables**.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `dynamodb_list_tables` | Names of the tables agents may use | Read only |
| `dynamodb_describe_table` | Key schema, secondary indexes, item count, size, billing mode, stream, TTL | Read only |
| `dynamodb_query` | Items of one partition key, with key condition, filter, projection, a table or an index | Read only |
| `dynamodb_scan` | Items of a whole table or index with an optional filter | Read only; Approval on prod |
| `dynamodb_partiql` | One PartiQL `SELECT` with `?` parameters | Read only |
| `dynamodb_execute` | One PartiQL `INSERT`, `UPDATE` or `DELETE` | Approval |

Expressions are DynamoDB's own (`KeyConditionExpression`, `FilterExpression`, `ProjectionExpression`), with placeholders in `names` (`{"#s": "status"}`) and typed values in `values` (`{":id": {"S": "c-42"}}`). PartiQL parameters are typed values too. GateRook refuses a value that isn't one typed value of a known type.

Answers show items as plain JSON: a number is a JSON number only where JavaScript keeps it exact, otherwise its text; binary values show their first 256 bytes in hex with their length; long strings are cut to the endpoint's cell size. Each answer reports the read capacity it used. DynamoDB's `Limit` counts the items it reads before a filter, so a call reads up to five pages to fill its limit, and returns `nextCursor` where DynamoDB stopped. Cursors are sealed by GateRook and belong to one connection and one request.

GateRook reads each PartiQL statement by its own tokens before it runs. A statement with a comment, a semicolon, a control character or an open quote is refused rather than guessed at. `dynamodb_partiql` runs only a `SELECT`; `dynamodb_execute` runs only `INSERT`, `UPDATE` or `DELETE`, which DynamoDB applies to one item named by its full primary key. The table must be on the connection's list. The approval shows the exact statement, its parameters and its table, and what runs is the approved statement, not the call's arguments. A change is never retried: a connection lost after the request was sent reports the outcome as unknown.

`dynamodb_scan` reads, and pays for, every item of the table up to its limit; prefer `dynamodb_query` when you know the key. A PartiQL `SELECT` without a key condition scans the table as well.

## Verify with moto

For local verification, start the opt-in [moto service](../test-services/README.md) with `scripts/test-services.sh up dynamodb` and run the connector's integration test with `-Pservices`. moto emulates DynamoDB and STS but enforces no IAM policy: the session policies' contents are checked against a fake of the API. JVM checks and emulator tests do not establish native-image or installer compatibility, nor behaviour against AWS itself.

AWS documents the [DynamoDB API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/Welcome.html), [PartiQL for DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-reference.html), [IAM actions for PartiQL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-iam.html) and [session policies](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies.html#policies_session).
