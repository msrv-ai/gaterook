# Control AI access to search engines

GateRook's MCP Firewall lets AI apps search the indexes of Algolia, Meilisearch, Typesense and Azure AI Search. Agents can run full-text queries with filters, facets, sorting and paging, run vector queries where the engine has them, and fetch documents by id. Every write and delete of documents waits for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail. OpenSearch and Elasticsearch have connectors of their own with the Query DSL.

## Connect an engine

Each engine is a built-in connector over its REST API, without a vendor SDK. All four share the `search_*` tools.

| Engine | Type | Address | Credentials | Read-only credentials |
| --- | --- | --- | --- | --- |
| **Algolia** | `algolia` | The application's hosts: `<APP>-dsn.algolia.net` for reads, `<APP>.algolia.net` for writes | The application ID and an API key | The Search API key, or a key with `search`, `browse` and `listIndexes` |
| **Meilisearch** and Meilisearch Cloud | `meilisearch` | The address (port 7700 for a server of your own) | An API key (`Authorization: Bearer`), or none for a server without a master key | The Default Search API Key, or a key with `search` and `documents.get` |
| **Typesense** and Typesense Cloud | `typesense` | The address (port 8108 for a server of your own) | An API key (`X-TYPESENSE-API-KEY`) | A key with `documents:search`, `documents:get` and `collections:get` |
| **Azure AI Search** | `azure-ai-search` | `https://<service>.search.windows.net`, `.search.azure.us` or `.search.azure.cn`; API version `2025-09-01` | An admin or query key (`api-key`) | A query key |

For Meilisearch and Typesense, the **Server URL** takes `https://` and the port, optionally with a path prefix. Plain `http://` goes only to this computer. Algolia's and Azure's hosts follow from the application ID and the service endpoint; agents never choose a host or a path. Requests go through GateRook's egress guard, as for the [vector databases](vector-databases.md#connect-a-store). Algolia's and Azure's hosts are public, and private networks stay reachable for Meilisearch and Typesense. TLS is verified with the system's CAs or a private CA under **Advanced**. Keys are stored encrypted, travel in headers, and are tainted so that any echo is masked. Azure AI Search signs in with keys; Microsoft Entra ID is not supported yet.

**Indexes** (**Collections** on Typesense) lists what agents may use; `*` matches any characters. Empty, every index the credentials see. A key that may not list indexes — Algolia's Search API key without `listIndexes`, Meilisearch's search keys, a scoped Typesense key, an Azure query key — needs the names listed without `*`: GateRook then shows those names and checks the key with one search.

**Test connection** reports:

- the engine and its version where it says one;
- the indexes agents may use;
- what the key may do, as far as the engine tells:

| Engine | What GateRook learns about the key |
| --- | --- |
| Algolia | The key's own ACL (`GET /1/keys/<key>`, the one request with the key in its path, sent only to the application's host). A key without write ACLs is reported as read only. |
| Azure AI Search | An admin key may list indexes; a query key may not, and may only search and read documents. |
| Typesense | A key that may read `/debug` is an admin key. |
| Meilisearch | Nothing without reading every key, which GateRook doesn't do. |

## Bound reads

| Tool | What it does | Default mode |
| --- | --- | --- |
| `search_list_indexes` | Indexes agents may use, with their size where the engine lists it | Read only |
| `search_describe_index` | One index: its fields or settings (searchable, filterable, sortable, facetable, ranking), its embedders or vector fields, how many documents it holds | Read only |
| `search_query` | `query` with a `filter`, `facets`, `sort`, `fields`, `searchFields`, `limit` and `offset`; a `vector` with `vectorField` on Meilisearch, Typesense and Azure | Read only |
| `search_get_documents` | Up to 100 documents by id, and the ids that don't exist | Read only |
| `search_index_documents` | Up to 100 documents into one index: replaced whole, or merged into existing ones | Approval |
| `search_delete_documents` | Up to 1,000 documents of one index, by id | Approval |

A hit comes back as `{"id", "score", "document"}`. Facets come back as `{field: {value: count}}`. `total` is the engine's count of matches (`totalIsEstimate` for Meilisearch's estimate), and `nextOffset` is the offset of the next page. Highlights and ranking details are left out. An answer stays within the endpoint's response size; hits are dropped from its end and `truncated` says how many.

| Engine | Search request | Notes |
| --- | --- | --- |
| Algolia | `POST /1/indexes/<index>/query` with `filters`, `facets`, `offset` and `length` | It sorts by replica indices, so `sort` is refused: search the replica |
| Meilisearch | `POST /indexes/<uid>/search` with `filter`, `facets`, `sort` and `showRankingScore` | A vector needs `vectorField`, the embedder's name; with `query` too it's a hybrid search |
| Typesense | `POST /multi_search` | It searches the collection's string fields unless `searchFields` names others; a key that may not read the schema needs `searchFields`. Up to 3 sort fields |
| Azure AI Search | `POST /indexes/<name>/docs/search` with `count`, `top` and `skip` | A vector query needs `vectorField` |

### Filters

The four engines take the same filter language as the [vector databases](vector-databases.md#filters), MongoDB's form. GateRook writes it in each engine's syntax, quoting every value itself:

| Engine | Written as | Limits |
| --- | --- | --- |
| Algolia | `filters`: facet filters for text and booleans, numeric comparisons for numbers | No `$and` inside an `$or`, no `$nin` inside an `$or`, no `$exists`, numbers only with `$gt`…`$lte` |
| Meilisearch | `filter`: `=`, `IN`, `EXISTS`, `AND`, `OR` on filterable attributes | — |
| Typesense | `filter_by`, text in backticks | A value with a backtick is refused, no `$exists` |
| Azure AI Search | OData `$filter`: single quotes doubled, `search.in` with a delimiter no value contains, and collection fields through `any` and `all` when the index's definition is readable | — |

## Review exact changes

`search_index_documents` writes up to 100 documents, at most 1 MiB, to one index that exists. Each document carries its id in the engine's id field: Algolia's `objectID`, Meilisearch's primary key, Typesense's `id`, Azure's key field. GateRook looks up the last two before the approval: an Azure query key can't read them, so writes need an admin key.

| `mode` | Algolia | Meilisearch | Typesense | Azure AI Search |
| --- | --- | --- | --- | --- |
| `replace` (default): a document with an existing id is replaced whole | `updateObject` | add or replace | `upsert` | `upload` |
| `merge`: fields merged into the existing document, which is created when missing | `partialUpdateObject` | add or update | `emplace` | `mergeOrUpload` |

`search_delete_documents` deletes by id only; there is no delete by filter and no index drop. The approver sees:

- the exact request, with the key as `‹secret›`;
- each document or id;
- which ids already exist or don't.

What runs is the approved payload, once:

- Algolia and Meilisearch apply changes as tasks: GateRook waits a few seconds for each and says whether it ran. A Meilisearch task that fails changed nothing.
- Typesense and Azure answer per document; GateRook reports the ones they refused. If they refused every document, nothing changed.
- An answer lost after sending is an unknown outcome and never retried.

## Verify

The unit tests run in every build:

- the filter language in each engine's syntax, and the schemas;
- Algolia and Azure AI Search against fakes of their documented APIs: the exact hosts, headers, request bodies and filters; the key's ACL; a query key told from an admin key; partial batch results.

The integration test runs against Meilisearch 1.56.2 and Typesense 30.2 of the [test services](../test-services/README.md). It makes an index and runs:

- an approved write, filtered and faceted searches, lookups by id, a merge and a delete;
- the Default Search API Key and a scoped Typesense key on listed indexes;
- a wrong key failing the test.

Start them with `scripts/test-services.sh up search` and run `StoresIT` with `-Pservices,search-it`.

Not verified:

- Algolia's and Azure AI Search's live services, and Meilisearch Cloud and Typesense Cloud;
- vector queries on the engines;
- Azure's semantic ranking.

JVM checks do not establish native-image or installer compatibility.

The vendors document their APIs: [Algolia](https://www.algolia.com/doc/rest-api/search), [Meilisearch](https://www.meilisearch.com/docs/reference/api/overview), [Typesense](https://typesense.org/docs/latest/api/) and [Azure AI Search](https://learn.microsoft.com/rest/api/searchservice/).
