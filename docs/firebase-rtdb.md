# Control AI access to the Firebase Realtime Database

GateRook's MCP Firewall lets AI apps read a Firebase Realtime Database within bounded answers and holds every change for your review. The [security model](security.md) explains execution controls, explicit Bypass, and the append-only, tamper-evident audit trail.

## Connect with a Google identity

The built-in **Firebase Realtime Database** connector (`firebase-rtdb`) calls the database's REST API with a Google OAuth access token. There is no Firebase SDK in GateRook. **Database URL** is the URL the Firebase console shows: `https://<database>.firebaseio.com` or `https://<database>.<region>.firebasedatabase.app`. GateRook accepts exactly these hosts, which Google runs for its databases, with nothing after the host. It never accepts Firebase Hosting (`web.app`, `firebaseapp.com`), a custom domain, or a shallower or deeper name under the two domains, and the token goes nowhere but the database's host. Redirects are never followed.

The credentials are those of the Cloud Firestore and GCP connectors, without a billed project: Google sign-in on this computer or a service account key, optionally impersonating a service account for the **Token lifetime**. A key's or an impersonated service account's token carries the scopes `firebase.database` and `userinfo.email`. Your own Google sign-in keeps the scopes it was given when you signed in. If the database refuses it, impersonate a service account, or sign in again with those scopes added, such as `gcloud auth application-default login --scopes=openid,https://www.googleapis.com/auth/userinfo.email,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/firebase.database`.

Firebase documents that requests with a Google OAuth token bypass the database's security rules, so the identity's IAM roles are the boundary behind GateRook's checks: give a service account `roles/firebasedatabase.viewer` to read, or `roles/firebasedatabase.admin` when agents may propose changes. On prod, **Test connection** warns about your own sign-in or a stored key used without impersonation.

While you fill in the form, GateRook offers the default database URL of the project gcloud works in on this computer, or of a pasted key's project, without filling it in. Once the form can sign in, it lists the project's databases from the Firebase Realtime Database Management API, with a read-only token of the `firebase.readonly` scope used for nothing else, and the database's top-level keys for **Paths**. Listing databases needs `roles/firebasedatabase.viewer`.

**Paths** lists the top-level keys agents may use, with everything under them (`*` matches any characters, as in `public-*`). Empty, the whole database. Test connection names the identity (the service account, or for your own sign-in the email Google's token information reports) and the top-level paths agents may use.

## Bound reads and review exact changes

| Tool | What it does | Default mode |
| --- | --- | --- |
| `rtdb_get` | The data at one path: its keys only (`shallow`), or a query of its children | Read only |
| `rtdb_write` | One set (`PUT`), update (`PATCH`) or delete (`DELETE`) at one path | Approval |

A path is keys joined by slashes (`/users/alice`). GateRook checks every key before it becomes part of a URL and percent-encodes it itself: a key that is empty, longer than 768 bytes, or holds `.`, `$`, `#`, `[`, `]`, `/`, `\`, `?`, `%`, a control character or an invisible formatting character is refused, as is a path deeper than the database's 32 levels.

At the root, `rtdb_get` reads only the top-level keys (`shallow=true`) unless you ask otherwise; on a connection limited to some paths, the root can only be read that way, and only the keys on the list show. `orderBy` takes `$key`, `$value`, `$priority` or a child (`score`, `address/city`), and `limitToFirst`, `limitToLast`, `startAt`, `endAt` and `equalTo` need it; their values are sent as JSON, a limit is capped at the endpoint's rows, and `shallow` can't be combined with a query. The REST API doesn't sort its answer. The answer is read token by token: long strings are cut to the endpoint's cell size, and an answer larger than the endpoint's response size is refused, without reading the rest, with advice to narrow it (`shallow`, a limit, a deeper path).

`rtdb_write` sends exactly one request, with `print=silent`. Set replaces the data at its path with a value that isn't `null`. Update changes the children its value names and keeps the others; a key may be a path further down (`profile/city`), and `null` removes that child. Delete removes the data at its path and everything under it. Neither set nor delete may reach the root, and every path written, each key of an update included, must start with a top-level key on the connection's list. The approval shows the exact request: method, URL, headers with the token masked, and body. What runs is the approved request, read back and checked again, not the call's arguments. A write is never retried: a connection lost after the request was sent, a timeout or a server error reports the outcome as unknown. A value larger than the endpoint's request size is refused.

The path list is enforced when GateRook classifies a call, without I/O, and again when the call runs.

## Verification limits

The connector's tests run against a fake of the database's REST API and of Google's token, impersonation and token-information endpoints: request URLs and headers, scopes, the bounded reading of answers, the path list, approval-only writes and error mapping. The host predicate of connectors/cloud-auth is tested with accepted and refused names. No emulator test exists. JVM tests do not establish native-image or installer compatibility, nor behaviour against Google's service itself, including how a database treats your own sign-in's scopes.

Firebase documents the [Realtime Database REST API](https://firebase.google.com/docs/reference/rest/database), [authenticating REST requests](https://firebase.google.com/docs/database/rest/auth), [retrieving data](https://firebase.google.com/docs/database/rest/retrieve-data) and [predefined IAM roles](https://firebase.google.com/docs/projects/iam/roles-predefined-product).
