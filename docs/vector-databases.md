# Control AI access to vector databases

GateRook's MCP Firewall lets AI apps read the collections of Pinecone, Qdrant, Milvus and Zilliz Cloud, Weaviate and Chroma. Agents can fetch records by id, page through the records a metadata filter matches, and search nearest neighbours. Every upsert and delete waits for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect a store

Each store is a built-in connector over its REST API; GateRook uses no vendor SDK. All five share the `vector_*` tools, so an agent learns them once and names the connection it means.

| Store | Type | Address | Credentials | Read-only credentials |
| --- | --- | --- | --- | --- |
| **Pinecone** | `pinecone` | Pinecone's control plane, then each index's own host | The project's API key (`Api-Key`), API version `2025-10` | A key with the ControlPlaneViewer and DataPlaneViewer roles |
| **Qdrant** and Qdrant Cloud | `qdrant` | The REST address, port 6333 by default | An API key (`api-key`), or none for a server that checks none | Qdrant's `read_only_api_key`, or a JWT without write access |
| **Milvus** and Zilliz Cloud | `milvus` | The REST address (port 19530) or a cluster's public endpoint; one database | A user and password, an API key or token (`Authorization: Bearer`), or none | A user whose role may only read |
| **Weaviate** and Weaviate Cloud | `weaviate` | The REST address (port 8080 for a server of your own) | An API key (`Authorization: Bearer`), or none | A read-only user or role |
| **Chroma** and Chroma Cloud | `chroma` | The address (`https://api.trychroma.com` for Chroma Cloud, port 8000 for a server of your own); one tenant and database | An API key in `X-Chroma-Token` (Chroma Cloud), a bearer token, or none | Chroma doesn't scope keys |

The **Server URL** is the address with `https://` and its port, optionally with a path prefix. Plain `http://` goes only to this computer. Pinecone takes no URL: GateRook asks its control plane where an index lives, and sends the key only to a host of the form `<index>-<project>.svc.<region>.pinecone.io`.

Requests go through GateRook's egress guard:

- It judges every address a connection really goes to, so a name that resolves elsewhere later can't redirect a request.
- It follows no redirect and retries nothing.
- Private networks stay reachable. Link-local and cloud metadata addresses and GateRook's own ports are refused.

TLS is verified with the system's CAs, or with a private CA you paste under **Advanced**. Credentials are stored encrypted and travel only in headers. A prod connection must sign in.

**Collections** (**Indexes** on Pinecone) lists what agents may use; `*` matches any characters, as in `support_*`. Empty, every collection the credentials see. GateRook checks the list for every collection a call names and filters listings by it.

**Test connection** reports:

- the server and its version where it says one;
- the collections agents may use;
- what the credentials may do, as far as the store tells. Milvus names the user's roles, `root` is its superuser, and Weaviate names the user. Qdrant, Pinecone and Chroma don't say, and a server that checks no credentials is reported as open to anyone who reaches it.

It never writes to find out.

## Bound reads

| Tool | What it does | Default mode |
| --- | --- | --- |
| `vector_list_collections` | Collections agents may use, with dimension, metric and size where the store lists them cheaply | Read only |
| `vector_describe_collection` | One collection: its vectors (dimension, metric, named vectors), its fields or indexed payload, its namespaces, tenants or partitions, how many records it holds, and how it searches text | Read only |
| `vector_get` | Records by id (up to 100), or a page of the records a filter matches (all of them without one), with `nextCursor` | Read only |
| `vector_query` | The `topK` records nearest to a vector, to a stored record (`id`), or to a text the store embeds or matches itself | Read only |
| `vector_upsert` | Up to 100 records into one collection | Approval |
| `vector_delete` | Up to 1,000 records of one collection, by id | Approval |

A record comes back in one shape whatever the store calls it (point, entity, object, vector, embedding): `{"id", "score", "metadata", "document", "vector"}`. `score` is the store's own: Qdrant's and Pinecone's similarity, Milvus's distance by the index's metric, Weaviate's distance, or its BM25 or hybrid score. Vectors come back only with `includeVectors`.

An answer stays within the endpoint's response size. Records are dropped from its end and `truncated` says how many, so an answer never ends inside a record. `topK` and `limit` are capped by the endpoint's row limit.

### Searching

| Store | By vector | By a stored record | By text |
| --- | --- | --- | --- |
| Pinecone | `/query` | `/query` with its id | Indexes with integrated embedding (`/records/…/search`) |
| Qdrant | `/points/query` | `/points/query` with its id (the record itself is left out) | Not supported: pass a vector or an id |
| Milvus | `/entities/search` on the vector field (`vectorName` picks one) | GateRook reads the entity's vector, then searches with it | A collection's BM25 function (`textMode: keyword`) or text embedding function (`semantic`) |
| Weaviate | GraphQL `nearVector` (`vectorName` picks a named vector) | `nearObject` | `nearText` with a vectorizer, `bm25` (`keyword`), `hybrid` |
| Chroma | `/query` with `query_embeddings` | GateRook reads the record's embedding, then queries with it | Not supported: pass a vector or an id |

`namespace` is a Pinecone namespace (empty: the default one), a Weaviate tenant, or a Milvus partition. Qdrant and Chroma have none.

### Filters

All five take one filter language, MongoDB's form:

```json
{"city": "Berlin", "year": {"$gte": 2020}, "$or": [{"lang": {"$in": ["en", "de"]}}, {"draft": {"$ne": true}}]}
```

The language:

- **Operators:** `$eq $ne $gt $gte $lt $lte` take text, an integer, a number or a boolean. `$in` and `$nin` take a list of one type. `$exists` takes true or false.
- **Combining:** `$and` and `$or` take a list of filters. Several keys of one object must all match.
- **Limits:** at most 6 levels, 64 conditions and 100 values in a list. There is no `null`: `{"$exists": false}` matches a missing field.

GateRook reads the filter into a tree and writes it in each store's own syntax, quoting every value itself. An agent's text never becomes query syntax:

| Store | Written as |
| --- | --- |
| Pinecone and Chroma | Their native JSON; Chroma has no `$exists` |
| Qdrant | `must`, `should` and `must_not`; a number with a fraction becomes a range of one value |
| Milvus | A boolean expression; `$exists` becomes `is not null` / `is null` |
| Weaviate | A GraphQL `where`, typed by the collection's schema (`valueInt`, `valueNumber`, `valueText`); `$nin` becomes `Not` around `ContainsAny` |

Field names follow each store's rules: payload keys with `.` into objects on Qdrant, field and property names of letters, digits and `_` on Milvus and Weaviate, and metadata keys that don't start with `$` on Pinecone and Chroma. The store evaluates the filter, so a field it doesn't index or a value of the wrong type is refused in its own words.

The store evaluates filters on its side, as a database evaluates `WHERE`. A filter can therefore narrow records by a metadata value that GateRook masks in the answer. Keep secrets out of the metadata agents may read, or out of the collections the connection lists.

## Review exact changes

`vector_upsert` writes up to 100 records, at most 1 MiB, to one collection. Each record has:

- an `id`: text, or an integer where the store's ids are integers (Qdrant, Milvus with Int64 keys); Weaviate ids are UUIDs;
- a `vector`, or named vectors as `{"name": […]}` (Qdrant, Weaviate, Milvus);
- `metadata`;
- a `document`: on Chroma the record's document, on a Pinecone index with integrated embedding the text it embeds, at most 96 records per upsert there.

A record with an existing id replaces it whole. `vector_delete` deletes up to 1,000 records by id and nothing else: no delete by filter, no dropped collection. To clear what a filter matches, page through it with `vector_get`, then delete those ids.

Before the approval, GateRook looks up what the request needs: Pinecone's index host, Chroma's collection id, and Milvus's primary key and vector fields. It then builds the request. The approver sees:

- the exact request: method, URL, the headers GateRook sets with the credentials as `‹secret›`, and the body byte for byte;
- each record or id;
- which of the first 100 ids already exist ("2 records already exist and will be replaced") or don't ("1 of the 3 ids don't exist").

What runs is the approved payload, rebuilt by the same code, once:

- Qdrant writes with `wait=true` and says whether the change was applied.
- Weaviate reports objects it refused one by one.
- A refusal of the store changes nothing.
- An answer lost after sending is reported as an unknown outcome and never retried: read the records back first.

Milvus reads with its collection's consistency level, so a change may show in reads a moment later. A collection must be loaded to be read.

## Verify

The connector's unit tests run in every build:

- the filter language and each store's syntax;
- the URL rules and the schemas;
- the classification, the approval preview, the approved payload, a lost answer and an answer cut to size, against a fake Qdrant;
- Pinecone's host check.

The integration test runs against real servers of the [test services](../test-services/README.md): Qdrant 1.20.0, Milvus 3.0.6, Weaviate 1.40.5, Chroma 1.5.9, and the last image of Pinecone's local emulator, which reached end of life in October 2026 and lacks fetch by metadata. For each store it makes a collection, then runs an approved upsert, reads by id, by filter, by vector and by id, an approved delete with one missing id, and Qdrant's read-only key refusing a write. Start them with `scripts/test-services.sh up search` and run `StoresIT` with `-Pservices,search-it`.

Not verified:

- Pinecone's, Zilliz Cloud's, Qdrant Cloud's, Weaviate Cloud's and Chroma Cloud's services;
- text search through Pinecone's integrated embedding, Milvus's BM25 and text embedding functions and Weaviate's vectorizers;
- multi-tenant Weaviate collections and Milvus partitions.

JVM checks do not establish native-image or installer compatibility.

The vendors document their APIs: [Pinecone](https://docs.pinecone.io/reference/api/introduction), [Qdrant](https://api.qdrant.tech/), [Milvus RESTful API v2](https://milvus.io/api-reference/restful/v2.5.x/About.md), [Weaviate](https://docs.weaviate.io/weaviate/api/rest) and [Chroma](https://docs.trychroma.com/reference).
