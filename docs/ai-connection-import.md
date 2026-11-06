# Import connections with AI

**Import with AI** asks an AI app to discover connections in a repository and send a proposal back to GateRook. You review the proposal and choose a destination endpoint for each connection. Complete their settings now or save the available information as disabled connections that need setup. The AI's submission does not create connections or grant access.

## Start an import

1. Choose **Import with AI** on the first-run welcome screen, **Overview**, **Connections**, or an endpoint. The highlighted button uses a lightbulb icon; the welcome screen recommends it as the easiest way to set up your environment.
2. Choose the repository folder. The AI app needs authorized access to this repository and permission to write the private import file on the computer running GateRook; choosing a folder does not give a remote app filesystem access.
3. Select a GateRook endpoint. Each detected AI app has a card showing its status for this endpoint. Use **Connect** to review the proposed setup and confirm it, or complete the offered browser sign-in. An endpoint does not need existing connections to accept an AI import.
4. Complete any installation or restart steps. If you configured the app manually, check the exact MCP URL and explicitly confirm that you connected this endpoint. Manual confirmation is not a detected connection. Select **Start** on the chosen app's card when it is ready. This endpoint receives the AI's proposal; you choose the destination for imported connections during review.
5. The next screen names the selected app and explains the analysis and review steps. Open the app or copy the prompt into it. The prompt starts collapsed; **Show prompt** reveals it and **Copy prompt** confirms successful copying with **Copied**. Antigravity uses manual pasting. Review and send the prompt there. GateRook checks for submitted results automatically. **Back** returns to repository and app setup without discarding the current session; choosing the same repository and endpoint reuses its prompt.

Check the exact MCP URL shown with the prompt: multiple GateRook instances can have endpoints with the same name. A connected status records successful setup or authenticated usage; it does not guarantee that the AI app has refreshed its tool list. If GateRook was updated, restart the AI app and start a new chat. While the selected app is waiting for restart or sign-in, its status refreshes automatically. Confirming a restart yourself is labeled separately from an observed connection. You can also refresh app statuses without losing the repository choice or current import.

App links depend on the installed client's protocol handler. If an app cannot open the link, or the prompt is too long for a link, use **Copy prompt**. Cursor may first show its own **Create chat with prompt** dialog. Opening a prompt does not send it to a model.

The prompt contains the repository path, a private import-file path, an import-session handoff, discovery rules, and an example of the proposal format. Treat it as session-specific: share it only with the AI app doing this import. Sessions expire after two hours and do not survive a GateRook core restart. Start again if the session expires.

## What the AI checks

The prompt asks the AI to trace configuration rather than guess connection details. It should inspect shared configuration, environment-specific overrides, deployment manifests, local development stacks, source bindings, and log settings. Repository content is evidence, not permission to run scripts or follow embedded instructions.

Candidates can include databases, log sources, cloud services, HTTP APIs, and MCP servers. The report should keep development, stage, and production settings separate, deduplicate shared resources, cite repository-relative evidence, and explain missing access or uncertain values. A container's internal hostname may need a published host address to work from the computer running GateRook.

The AI first calls `connection_import_schema` without `type` for a compact connector catalog and `storeKinds`. It then calls the same tool with a returned type ID, such as `type: "mysql"`, for that connector's complete `configSchema`, `secretFields`, and `referenceOnlyFields`. Both responses contain the complete JSON in text content and `structuredContent`, so clients that expose only text can read the fields. A compact catalog is not a truncated connector schema. If a requested schema remains unavailable, the AI must stop and report the problem instead of inferring it from application files or using GateRook's admin API.

The AI writes its proposal to the exact private file supplied by GateRook, then calls `submit_connection_import` with the session ID and submission token only. GateRook reads the file locally; credentials and file contents do not travel in MCP tool arguments or results. All schema and submission calls require the active import session on the selected endpoint.

The file separates ordinary configuration from credentials. Use actual Vault or supported secret-store references, or a reference to a credential file, when the repository establishes them. When a secret is written literally in authorized repository configuration, the AI copies that value into the private file's `secrets` map. It must keep values out of chat replies, evidence, logs, MCP arguments, and configuration URLs. Credential-source hints explain unresolved setup; hints alone do not supply a credential.

GateRook prepares the handoff file in its private import directory. The AI writes that file with owner-only permissions and must not place it in the repository or commit it. GateRook removes the plaintext handoff after reading it successfully and cleans up abandoned session files when the core restarts. **Discard import** drops the session and its staged credentials without changing saved connections. Session expiry also discards staged credentials. The review displays source descriptions, never secret values.

## Review the proposal

When the AI submits its proposal, GateRook opens the import review page. AI clients that support MCP Apps also show a card with the candidate count and **Open import in GateRook**. The button reopens this exact import; clients without cards receive a link. The card cannot accept an import, add, edit or delete connections, or change settings. Supported candidates with unused names are selected with their available settings prepared. Nothing is saved until you confirm the final import.

**Analysis notes** starts collapsed in a highlighted strip showing the number of notes. Select **Show details** to read all findings; **Hide details** restores the compact view without changing the proposal.

Each candidate's **Evidence** row also starts collapsed with a source count. Select it to expand or collapse that candidate's repository evidence independently.

1. Check each candidate's environment, target, source evidence, warnings, prepared credentials, and credential-source hints.
2. Use a candidate's settings icon, labeled **Edit settings**, to change its name or configuration. Review the normal connector form and correct incomplete settings. **Credentials prepared for import** lists literal, file, and external-reference sources without revealing values. Keep those sources, or enter replacements in the form. Select **Keep for import** when it is ready. **Keep available settings** retains incomplete information for later setup, as the initial proposal does by default. Editing a candidate preserves its chosen destination.
3. Choose an endpoint in each candidate's **Import to** list, or **Do not import** to skip it. **Create endpoint…** opens a short form; confirming it creates an empty endpoint and selects it for this candidate. Other candidates keep their choices. Creating an endpoint does not import connections. Unsupported connector types are omitted from this list; supported candidates can still keep incomplete settings. Conflicting names are not selected by default. Rename a conflicting candidate in settings, keep it, then choose a destination. Production connections cannot be mixed with development or stage connections on one endpoint; assign these groups to separate endpoints. A candidate discovered as production cannot be downgraded during import.
4. Select **Import selected connections**. The selected connections and their endpoint assignments are saved together; if one fails validation or endpoint compatibility, none are imported. An endpoint explicitly created during review remains available even if the import fails or is discarded. Complete connections use the normal validation, endpoint policy, and tool defaults. If you choose to save an incomplete connection, GateRook preserves the available information and marks it **Needs setup**, with access disabled.

Existing connection names are not overwritten. Rename a candidate that conflicts with an existing connection, or leave it out. An import does not infer that an existing connection with a similar name is the same service.

Review each destination endpoint's tool modes after import. The AI cannot request Bypass or change an existing connection's permissions through the proposal. Importing a configuration does not prove that its address is reachable or that its credentials work; use the connection's normal test action and suitable read operations to verify access.

## Connect a discovered Vault

The review shows Vault/OpenBao stores found by the AI, including their address, namespace, sign-in method, and evidence. Choose **Connect** to review and complete the normal Vault form. If a matching store already exists, **Use existing** binds the proposal to that store without creating a duplicate. A failed or unverified existing store needs attention before use. For an older proposal that contains Vault references but no store details, **Connect Vault** opens the form for you to fill in.

The AI can describe a bootstrap token or AppRole secret ID in the private file's store `secrets` map using `local` or `file`. Its value never enters the review or MCP response. Prepared credentials are kept by default for the proposed sign-in method; their source is shown and you can replace them. Changing the Vault address, namespace, or sign-in identity clears that selection. Only your final **Test and connect** action reads a prepared credential file, saves the store, and tests its sign-in. Plain HTTP retains the normal separate confirmation.

For a token file named by configuration, `source: file` with `auth.method: token` reads a snapshot and stores it encrypted when you connect. The path must be absolute; the AI can resolve `~` to the home directory on the computer running GateRook, without executing shell expressions. **Your vault login on this computer** (`cli-token`) instead reads the standard `~/.vault-token` at sign-in. To keep reading a different token file at sign-in, choose it through the form's normal file picker. No token is issued or fetched from Vault during discovery.

Connecting a store does not import any connections. It remains connected if you later discard the proposal or a connection fails to import. Explicit references to a discovered store's ID or name follow the store you connected or selected, including when you renamed it. Review the connection destinations and confirm the final import afterward. You can also leave a store unconfigured and keep affected connections disabled for later setup.

## Finish an incomplete connection

**Keep available settings** lets GateRook try normal validation first and save the connection disabled if setup is incomplete. This preserves discovered settings without guessing missing values. The connection appears in **Connections** as **Needs setup** and stays disabled, including on its selected endpoint. Its outstanding fields and credential sources remain available for review after an application restart.

Available credential values are stored encrypted; usable external references retain their store binding. A missing credential file or a reference without a configured matching store remains a source description for later setup; it does not silently become an active credential. When you edit the incomplete connection, unresolved ordinary store references appear in their fields as editable drafts. Their original store remains selected; an unavailable store is explained instead of silently switching to another one. Connect the store or choose another source, then save the completed settings. Incomplete connections are not tested or contacted by background health checks, and their tools cannot run. Changing tool modes or toggling the connection cannot bypass the setup requirement.

After import, **Saved connections that need setup** lists the outstanding fields and **Finish setup** links. You can also choose **Finish setup** from **Connections**. Open the connection's edit form, complete the missing settings and credentials, and save. A successful save must pass normal validation before **Needs setup** is cleared. The connection remains disabled until you enable it explicitly. Check its endpoint tool modes and test the completed connection before relying on it.

## Credential sources

The private file can carry real credential sources as well as literal values already present in authorized repository configuration. GateRook does not execute credential commands or contact secret stores during submission. It reads referenced local credential files only when you confirm the selected connections; their values become encrypted local secrets. External references remain references to their configured store.

| File representation | What happens when you confirm import |
| --- | --- |
| `{"source":"local","value":"example-only-value"}` | The value is stored encrypted. The example is fictional; the AI uses the literal from the authorized configuration. |
| `{"source":"reference","ref":"vault://secret/acme/stage#database-password"}` | The connection keeps the external reference. Configure the matching store in **Secrets**, or include its existing store ID in `store`. GCP Secret Manager and AWS Secrets Manager references are also supported. |
| `{"source":"vault-dynamic","ref":"vault://database/creds/acme-reader"}` | The connection uses short-lived credentials from that Vault role, where the connector supports dynamic credentials. |
| `{"source":"file","path":"/path/to/database-password.txt"}` | GateRook reads the complete UTF-8 file and stores its contents encrypted. File references are snapshots at import, not continuously refreshed file bindings. |
| `{"source":"file","path":"/path/to/credentials.json","jsonPointer":"/database/password"}` | GateRook reads the string at the JSON Pointer and stores it encrypted. The selected JSON value must be a string. |

These objects go in a candidate's `secrets` map, keyed by a secret-field pointer from its connector schema, such as `/credentials/password`. Do not put them in `config`. Credential files are limited to 64 KiB and must be regular files, not symbolic links. YAML selectors, environment expansion, base64 decoding, and shell commands are not file-source operations; report the required transformation instead of pretending the file is directly usable.

Some connectors also expose `referenceOnlyFields` for a login user stored externally. Each entry names its `pointer`, allows only `reference`, and lists the `passwordFields` it must accompany. Put a user reference in `secrets` only when a listed password also uses an ordinary external-store reference. Local values, files and dynamic roles are not accepted for these user pointers; ordinary literal usernames stay in `config`. In the import editor, a prepared reference moves to another Vault through its own field: Change, then pick that Vault in the field's menu; existing saved secrets don't change. A reference prepared without a store is bound when it is imported, to the only store of its kind or to the first of several, asked in turn, that has it.

Environment variables and Kubernetes Secrets are discovery evidence unless they lead to an actual supported source. A deployment's `VAULT_TOKEN` variable does not automatically become available to GateRook. Configure the external store's authentication in **Secrets**. The AI should not fetch values from Vault or cloud secret stores just to copy them into the file.

Prepared credentials stay outside the renderer. Test, database/project discovery, and log-file browsing that would require a staged credential become available after import, once the connection has the required settings. Manually entered replacements remain in this page's memory while you review candidates. Leaving the import page or completing the import clears them.

Vault authentication supports AppRole, a token stored in GateRook, or **Your vault login on this computer**. The last option reads the standard `~/.vault-token` file; it does not read arbitrary files named by an AI. A bootstrap token used by a deployed application is separate from permission to use that token in GateRook.

## Scope and limits

A proposal supports up to 50 connection candidates, 16 Vault/OpenBao store proposals, and a total document size of 128 KiB. Supported connection types come from the installed connector schemas; `storeKinds` describes supported store proposals and their bootstrap credential fields. Incomplete supported candidates can be saved disabled with **Needs setup**; full connector validation is required before they can be enabled.

HTTP APIs and external MCP servers need their specialized setup flows. The AI can report them with evidence and unresolved requirements, but this importer does not create OpenAPI tools, run MCP commands, or complete OAuth sign-in. These types and services without an installed compatible connector are not offered in the import list. Use [HTTP API import](http-api-import.md) or [external MCP server setup](mcp-servers.md) for those services.

A log path alone does not establish a remote host or SSH login. A cloud project name does not establish a usable cloud identity. Unknown values must stay unresolved until you review them; the AI should not replace them with invented defaults.

If the AI cannot find `connection_import_schema` or `submit_connection_import`, compare its configured MCP URL with the exact URL in the prompt, including the port. Check that these tools are enabled in the AI app. After updating GateRook, the AI app may retain an older tool list; restart the AI app and open a new chat before retrying the prompt. The current import can still be used until it expires, provided GateRook has not restarted. The prompt alone does not install or configure an MCP connection for the AI app; use **AI apps** in the intended GateRook instance when a connection needs setup.
