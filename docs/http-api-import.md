# Put HTTP APIs behind the MCP Firewall

Import selected API operations into GateRook's MCP Firewall to control which tools AI clients can call, restrict their destinations, and review changes before execution by default. Authentication values stored as secrets stay encrypted in GateRook; the AI calls the exposed tools through an endpoint. See the [security model](security.md) for execution controls, audit protection, and their boundaries.

Open **Connectors → Templates → New template → OpenAPI import**, or choose **Import HTTP API** in the HTTP API connection form. The import wizard turns selected operations in an OpenAPI 3.0 or 3.1 document into MCP tools on a new or existing HTTP API connection.

## Prepare the description

Use a YAML or JSON file, paste its contents, or provide a public HTTPS URL. Swagger UI commonly displays an underlying OpenAPI document; use that document rather than the Swagger UI HTML page. Swagger 2.0 documents must be converted to OpenAPI 3.0 or 3.1 before import.

The HTTP API connection form looks for a document itself once it holds everything it needs to connect: it asks for `openapi.json`, `openapi.yaml` and `v3/api-docs` under the first base URL, and `/openapi.json` at its origin, without credentials, and offers the document's servers as base URLs and its top-level paths as allowed-path globs. It names the sign-in the document declares when it differs from the form's. A certificate that isn't trusted yet stops the look until Test connection has you trust it. Before that, the form fills in the sign-in of well-known APIs by their host, such as `x-api-key` for Anthropic or `api-key` for Azure OpenAI, and offers the usual API key header names. An `http://` base URL needs **Allow plain HTTP** under Advanced settings (never on prod); without it Test connection fails, as calls would.

If the service has no description, select **Create with AI**, enter its website, optional API documentation URL, and the functions you need. **Open Claude Desktop**, **Open Codex**, and **Open Cursor** open a prefilled prompt in the installed app. Review and send it there. **Copy prompt** works with other AI apps and in browser development mode. App links depend on the installed client's protocol handler; use Copy prompt if opening it fails.

Cursor first shows **Create chat with prompt**. Review that dialog and choose **Create Chat** to place the text in its composer. Neither this confirmation nor the GateRook launch button sends the prompt to the model. If a prompt exceeds the app-link length limit, use **Copy prompt** to transfer the complete text.

The prompt asks the AI to find an existing specification first, then use official documentation, authorized source code, or observed requests. The AI needs access to those sources and suitable browser or code tools. It must report missing evidence, preserve unsupported authentication requirements, and avoid inventing endpoints. A website alone cannot establish every API operation, required field, or permission.

Return to GateRook and choose **Upload file** or **Paste YAML or JSON** below the AI prompt. You can upload `openapi.yaml` or paste its complete contents into the text area without Markdown code fences. Switching back and forth preserves the pasted text. The current flow uses a manual handoff; it does not receive the result automatically from an AI app. No skill installation is required. Only the website, documentation URL, and function description you enter go into the prompt. Do not put credentials in those fields; enter credentials in GateRook's connection form.

## Review operations before granting access

1. Read the description. GateRook keeps the inspected bytes for preview and save, so a URL changing later cannot silently change the imported operations.
2. Review the proposed server and authentication. Choose the environment and enter credentials, or use an existing verified HTTP API connection. You can test the connection before proceeding. The file supplies suggestions, never credentials.
3. Review the tools, their names, methods, paths, and reasons for skipped operations. Filter and select the operations you need. Up to 50 available operations are selected by default; larger imports require a selection. Use a distinct tool prefix for each API. After changing it, refresh the preview before saving.
4. Mark any GET or HEAD operation that changes state as **Changes state**. Such tools require approval. Other methods require approval by default. Read operations start Off in production.
5. Save the tools. A new connection is created at this point, followed by its OpenAPI template. Saving an HTTP connection adds its configured base URLs, including their paths, to **Policies → its environment → HTTP egress → Allowed base URLs**. Existing entries are preserved. This also applies when editing a connection; save an older connection again to add any missing URLs. If template saving fails, the wizard keeps the connection and reuses it on retry. It does not create a duplicate or silently remove the connection.
6. Add the connection to an endpoint, then enable the imported tools you want its AI apps to use. Importing a template does not grant endpoint access automatically.

## Supported import profile

- OpenAPI 3.0 and 3.1, YAML or JSON, up to 20 MB, with local `$ref` references only. YAML and JSON files have the same limits: nesting up to 200 levels, numbers up to 1,000 characters, text values up to 5,000,000 characters and keys up to 50,000; YAML keys must be plain values, not lists or maps. A file outside these limits is refused with the reason.
- An operation whose schema holds a number that GateRook can't check arguments against (a `minimum` or `maximum` that isn't a number, a `multipleOf` beyond the range of a double, or a length or count limit beyond the range of a 32-bit integer) is skipped with that reason instead of becoming a tool that fails every call.
- Up to 200 selected operations per template; GET, HEAD, POST, PUT, PATCH, and DELETE.
- Path, query, and header parameters, with JSON or URL-encoded request bodies. Unsupported parameter serialization and body types are reported per operation.
- Bearer, Basic, and API keys in a header or query parameter. The connection must match an operation's effective security requirements, including per-operation overrides. Alternative security schemes are supported when one matches; combinations of different schemes are skipped.
- Root-level servers under the connection's configured base URLs. Path-level or operation-level server overrides are skipped; put those operations in a separate document with the appropriate root server.

Cookie parameters and cookie sessions, multipart bodies, external references, and OAuth/OIDC login flows are not configured by this importer. Describe them faithfully in an AI-generated file so GateRook can explain why an operation is skipped. URL downloads use the public HTTPS egress guard, never local addresses or connection credentials. For private documentation, obtain an authorized local file and import it.

Parsing checks GateRook's supported profile and compiles tool inputs; it does not prove that the remote API behaves as described. Review the AI's coverage report and test suitable read operations after enabling access. Existing HTTP allowlists, environment policies, secret storage, and approval rules apply to imported tools.
