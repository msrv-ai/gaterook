# Control AI access to Cloud Firestore

GateRook's MCP Firewall lets AI apps read Cloud Firestore documents with bounded queries and holds every change for your review. With an impersonated service account, each token is short-lived and reaches Firestore only. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect with a Google identity

The built-in **Cloud Firestore** connector (`firestore`) calls Firestore's REST API (v1) for one database of one project. There is no Firebase or Firestore SDK in GateRook; requests go only to `firestore.googleapis.com`, the token goes nowhere else, and redirects are never followed. Choose the **Project**, the **Database** (`(default)` unless you use a named database) and the credentials, as for the GCP connector:

- **Google sign-in on this computer** (Application Default Credentials, `gcloud auth application-default login`) or a **service account key** (stored encrypted in GateRook);
- optionally a **service account to impersonate**: GateRook borrows its token for the **Token lifetime** (15 minutes by default); and a **Billed project** for `x-goog-user-project`.

A key's or an impersonated service account's token carries only the `datastore` scope, so it reaches Datastore and Firestore and no other Google API. Your own sign-in keeps the scopes it was given. The identity's IAM roles are the boundary behind GateRook's checks: give a service account `roles/datastore.viewer` to read, or `roles/datastore.user` when agents may propose changes. On prod, **Test connection** warns about your own sign-in or a stored key used without impersonation.

While you fill in the form, GateRook fills in the project of a pasted key, or the one gcloud works in on this computer. Once the form can sign in, it lists the project's databases in Native mode (filling in the only one when the project has no `(default)`) and the database's top-level collections.

**Collections** lists the collections agents may use. An entry without a slash is a collection ID (`orders`, `audit-*`): it allows every collection with that ID, wherever it is, and collection-group queries on it. An entry with a slash is a path (`users/*/orders`, or `/orders` for the top-level collection only), in which `*` matches one ID and `**` any number; a path never allows a collection-group query. Empty, every collection the credentials may read. Test connection names the identity (the service account, or for your own sign-in the email Google's token information reports) and lists the top-level collections agents may use.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `firestore_list_collections` | Collection IDs at the root or under one document, as far as the list allows | Read only |
| `firestore_get_document` | One document by its path, its fields as plain JSON | Read only |
| `firestore_query` | Documents of one collection or collection group, with filters, order and a limit | Read only |
| `firestore_write` | One create, update or delete of one document | Approval |

Paths are collection and document IDs in turn (`users/alice/orders/o-1`). GateRook checks every ID before it becomes part of a URL and percent-encodes it itself: an ID that is `.` or `..`, reserved (`__…__`), longer than 1,500 bytes, or holds `/`, `\`, `#`, `?`, `%`, a control character or an invisible formatting character is refused. Field paths follow Firestore's syntax (`address.city`; `` `a.b` `` for a name with dots), and names that aren't simple identifiers are quoted for you.

Filter values are plain JSON or one Firestore typed value. A string, number, `true` or `false`, `null`, list or object is converted: an integer of at most 64 bits becomes an `integerValue`, any other number a `doubleValue`. An object with exactly one key that names a Firestore type, such as `{"timestampValue": "2026-01-02T03:04:05Z"}` or `{"referenceValue": "users/alice"}`, is that typed value, checked strictly. `== null` and `== NaN` become Firestore's unary filters, and a reference must name a document of the connection's own database. A query is sent with its whole ordering, as Firestore would complete it: your fields, then the fields of inequality filters, then the document name. Its `nextCursor` starts after the last document shown, so no document is skipped or repeated between pages. Cursors are sealed by GateRook and belong to one connection and one request.

Answers show documents as plain JSON with their path, ID and times. An integer is a JSON number only up to 2^53, otherwise its text; timestamps are RFC 3339 text; bytes show their first 256 bytes in hex with their length; references show their document's path; geo points are `{latitude, longitude}`; long strings are cut to the endpoint's cell size. A query's limit is capped at the endpoint's rows; it asks Firestore for one document more to tell whether there is a next page. Documents are read one at a time and stop at the endpoint's response size, with `nextCursor` after the last one shown. A single document larger than that is cut to the fields that fit, and the answer says how many were left out.

`firestore_write` runs exactly one write through `documents:commit`, with a precondition that makes its outcome exact. Create fails if the document exists. Update sets only the fields named (a map field is replaced whole) and fails if the document doesn't exist. Delete fails if there is nothing to delete, and Firestore keeps the document's subcollections. The approval shows the exact request body; what runs is the approved body, read back and checked again, not the call's arguments. A write is never retried: a connection lost after the request was sent, a timeout or a server error reports the outcome as unknown. A write larger than the endpoint's request size is refused.

The collection list is enforced when GateRook classifies a call, without I/O, and again when the call runs. Masking applies to answers; filters run in Firestore and compare the values stored there.

## Verification limits

The connector's tests run against a fake of Firestore's REST API and of Google's token, impersonation and token-information endpoints: request shapes and headers, scopes, value conversion, sealed cursors, the collection list, approval-only writes and error mapping. No Firestore emulator test exists. JVM tests do not establish native-image or installer compatibility, nor behaviour against Google's service itself.

Google documents the [Firestore REST API](https://cloud.google.com/firestore/docs/reference/rest), [structured queries](https://cloud.google.com/firestore/docs/reference/rest/v1/StructuredQuery), [Firestore IAM roles](https://cloud.google.com/firestore/docs/security/iam) and [service account impersonation](https://cloud.google.com/iam/docs/service-account-impersonation).
