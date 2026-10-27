# 08 · Kubernetes connector

> Research snapshot: 2026-10-23. Recorded tests used Java 25, Maven 3.9.11, Spring Boot 4.1.2, Fabric8 8.0.0 with the JDK HTTP client, kube-apiserver 1.37.0 plus etcd, kubectl 1.37.0, and Fabric8's mock server. The API-server fixture had no controllers or kubelet. **UNVERIFIED** distinguishes behavior not demonstrated by those tests. See the [architecture](../ARCHITECTURE.md), [SSH trust-boundary research](06-ssh.md), and [MCP approval contract](01-mcp-protocol.md).

## 1. Decision and alternatives

Use Fabric8 8.0.0 with `kubernetes-httpclient-jdk`, excluding the default Vert.x 5 provider. Expose four tools: `k8s_get`, `k8s_logs`, `k8s_rollout_restart`, and `k8s_apply`. A named get includes describe-style events and rollout details, avoiding a separate describe tool.

Lists use the server's Table representation where available, preserving familiar kubectl columns and CRD printer columns. Mutations use the same concrete operations as Kubernetes clients: restart patches the pod-template restart annotation; apply uses server-side dry-run, reviewable diff, approval, and server-side apply with concurrency preconditions.

Start configuration from `Config.empty()`. Default Fabric8 configuration can load ambient kubeconfig, environment settings, service-account credentials, and proxy state. Parse the selected kubeconfig explicitly, control exec plugins, and disable implicit retry loops. The recorded default retry path stretched an error to about nineteen seconds.

Alternatives considered were Fabric8 7.x (Jackson 2), the official generated client (another JSON/HTTP stack), a handwritten REST client (substantial kubeconfig/discovery/SSA/Table work), and invoking kubectl (platform binary and credential/file management). Client-side merging was rejected for previews because it cannot reproduce server defaulting, admission mutation, or SSA semantics.

## 2. Dependencies and compatibility

| Component | Evaluated version | Constraint |
| --- | --- | --- |
| Fabric8 BOM/client | 8.0.0 | Java 17 baseline and Jackson 3. |
| Fabric8 JDK HTTP client | 8.0.0 | Explicit provider; exclude `kubernetes-httpclient-vertx-5`, not the older module name. |
| Jackson | Boot-managed 3.1.7 | Fabric8 was built with 3.2.3/annotations 2.22; reviewed paths passed with Boot's 3.1.7/2.21, but untested paths may differ. |
| SnakeYAML Engine | 3.1.1 in evaluated graph | Restrictive manifest parser settings. |
| java-diff-utils | 4.17 | Unified review diffs. |
| kubernetes-server-mock / kube-api-test | 8.0.0 | Fast HTTP fixtures and real API-server tests. |

Do not remove unused-looking model jars without coverage: the DSL can link their classes lazily. Enforce the intended production transport dependency tree. Reduced-runtime analysis identified `java.desktop`, `java.net.http`, and `java.sql` alongside java.base, but the packaged/native runtime still needs behavior tests.

Fabric8 can read EC private keys using JDK cryptography when `clientKeyAlgo` is correctly set. The evaluated default assumed RSA, causing EC failures. Bouncy Castle is not required solely for that kubeconfig path.

## 3. Read tools

### k8s_get

Accept a resolved kind, optional name, namespace, label/field selectors, bounded page size, continuation, and wide/events options. Resolve plural, singular, Kind, short names, and group-qualified resource names against a stored discovery snapshot. Reject subresources such as exec/proxy rather than passing arbitrary paths through.

For lists, request `meta.k8s.io` Table and expose only appropriate columns/cells plus safe object identity. Wide mode includes higher-priority columns. Do not return `row.object`: PartialObjectMetadata can contain sensitive annotations and managed fields. When Table is unsupported, use a controlled summary fallback.

“All namespaces” means an individual request for each explicitly permitted namespace with one shared result budget, not a cluster-wide fetch followed by filtering. Bind a continuation to namespace/kind/selectors and policy context as needed to prevent scope confusion.

A named get returns a normalized object, recent events selected by object UID, and workload rollout status. Remove managedFields and `kubectl.kubernetes.io/last-applied-configuration`. If Secret reads are explicitly permitted, mask both data and stringData while preserving only safe key names. Events use lastTimestamp with an eventTime fallback. Pods additionally expose useful readiness, restart, image, and prior termination information.

Rollout summaries should distinguish observed generation, updated/ready/available replicas, conditions, paused state, and controller strategy. Do not claim a rollout finished merely because the restart PATCH succeeded.

### k8s_logs

Use bounded non-following log reads with mandatory tailLines, optional sinceSeconds, previous/container/timestamps, a total deadline, and a byte budget. A workload reference may select one appropriate pod; return the selected pod explicitly. Ambiguous container selection should produce the server's sanitized diagnostic.

The evaluated Fabric8 fluent log methods could not combine all required options, so the proposal used its authenticated HTTP client directly. Stream into a bounded tail buffer rather than buffering the entire body. Kubernetes `limitBytes` preserves the beginning of the returned stream, which can discard the newest lines after tailLines; the proposal used a separate larger server ceiling and retained only a smaller local tail. Real-kubelet behavior was not established by the API-server-only fixture.

Escape ANSI/control sequences and mask output. Report whether older lines, bytes, or time were truncated. Logs are untrusted content and can contain credentials; they defaulted Off in the recorded design.

## 4. Restart and apply

### Rollout restart

Only Deployment, StatefulSet, and DaemonSet are accepted. During preparation, read the target, reject a paused Deployment, capture its UID, and compute impact. After approval, JSON merge-patch the same object's pod template with `kubectl.kubernetes.io/restartedAt` under a dedicated field manager. Include the captured UID so replacement of the named object is rejected.

Show the actual PATCH path and body, including its UID precondition, as well as a familiar kubectl-equivalent description. The timestamp is generated consistently at execution; any placeholder in the preview must be explicit. A successful response records generation/time and directs the caller to k8s_get for rollout status.

Impact warnings include Recreate downtime, StatefulSet OnDelete behavior, DaemonSet availability, GitOps reconciliation, and current image/replica settings. Percentage maxSurge rounds up and maxUnavailable rounds down, matching controller behavior. Estimates are not availability guarantees.

### Server-side apply

The proposed sequence is:

1. Parse at most twenty YAML/JSON objects within a 256 KiB input limit. Disable duplicate keys, aliases/recursive keys, and custom tags. Require apiVersion, kind, and metadata.name; reject generateName and `kind: List`. Resolve namespace explicitly.
2. Classify every object against namespace, resource, verb, and hard-deny policy before network I/O.
3. Read the current object and perform server-side dry-run using `fieldManager=mcp-gateway`, with force only when explicitly requested.
4. Normalize live and dry-run objects, produce a unified diff, and bind its prepared state/digest to the approval.
5. After approval, apply the prepared intent with a resourceVersion precondition and verify that any permitted retry still represents the approved result.

A dry-run conflict returns the conflicting owners/fields rather than creating an approval for an operation known to fail. No-op diffs return unchanged. If admission cannot support dry-run, refuse to proceed without a trustworthy preview. Bound diff size (research proposal: 2,000 lines) so the human can review it.

Normalize away managedFields, resourceVersion, generation, UID, creationTimestamp, status, and last-applied metadata for the review comparison; retain the removed concurrency identity separately. The original proposal stored each live/dry-run resourceVersion plus a normalized result hash. On a status-only RV conflict, re-dry-run once; retry only if the approved normalized result is unchanged. A semantic change requires new approval. Tests must also cover a create target appearing between preview and execution; absence of an original RV is not authorization to overwrite an unrelated new object.

Multi-object apply is not atomic. Execute in the displayed order and report created/configured/unchanged/failed/skipped per object. Stop on a safety conflict and make partial completion explicit. Force takeover and removal of fields previously owned by GateRook must be visible in the diff and warning text.

## 5. Policy and classification

| Condition | Result |
| --- | --- |
| Namespace, resource, or verb outside environment policy | DENY. |
| Unknown kind in discovery snapshot | DENY with supported-name guidance. |
| Cluster-scoped resource not explicitly allowed | DENY. |
| Apply to Secrets, ServiceAccounts, RBAC objects, Namespaces, CRDs, webhook/APIService objects, PriorityClasses, or Lists | Hard deny under the proposed contract. |
| Production pod spec with privileged, hostNetwork/hostPID/hostIPC, or hostPath access | DENY under production hardening. |
| Allowed restart/apply | MUTATION, always human-approved. |
| Allowed get/logs | READ, still subject to endpoint Approval mode. |

There are no delete, scale, exec, port-forward, proxy, or watch tools. Policy UI must not imply those operations exist. Production namespaces are selected explicitly and never inherited from dev; an empty namespace set grants nothing. Read-only credentials/RBAC remain the server-side boundary even when gateway policy is more restrictive.

The connector marks objects/events/logs as private and untrusted data. Apply is an egress-capable operation because a created workload can contact the network. Both the core and connector must reject execution of a mutation without an approved context.

## 6. Kubeconfig, TLS, and exec plugins

Support explicit server/token, encrypted pasted kubeconfig, or an explicitly selected kubeconfig file. Treat the whole pasted kubeconfig as secret because it can contain keys, bearer values, and refresh tokens. Extract only safe context metadata for selection.

File mode can resolve certificate/key paths relative to that file. Pasted mode must require inline data instead of reading arbitrary referenced paths. Pin the selected server and CA fingerprint during Test connection; a changed file must not silently move a dev connection to a different cluster.

Map the chosen context into `ConfigBuilder(Config.empty())`. Do not use a merge helper that executes credential plugins during parsing. Reject unsupported auth providers and choose RSA/EC key handling explicitly, including PKCS#8 detection.

Exec plugins are local code execution and default off. When enabled, show the exact command, require an explicit executable path, close stdin, use a minimal controlled environment plus noninteractive `KUBERNETES_EXEC_INFO`, bound execution, and cache only until the credential expiry. Audit the configuration decision; never log returned tokens. A GUI application's PATH cannot be assumed to match an interactive shell.

The evaluated JDK HTTP provider did **not** implement `tlsServerName`: it logged a warning and still validated the URL host. Reject unsupported overrides or implement and test an explicit alternative; do not offer a setting that silently does nothing. Fabric8's added CA data can augment system trust rather than replace it; a cluster-only trust requirement needs a dedicated tested trust store. Production must not disable TLS verification.

Make proxy selection explicit. An otherwise empty Fabric8 proxy setting can inherit JDK ProxySelector behavior. Disable automatic retries and permit only narrowly justified safe GET retries. Never replay a mutation after an uncertain transport failure.

Short-lived GKE/EKS providers were considered separately. EKS uses a presigned STS identity token with the cluster header; GKE can use explicit impersonated access tokens. These proposals are not a reason to enable ambient cloud authentication.

## 7. Test connection and SPI requirements

Probe version, identity, discovery, and permissions under a bounded client configuration. SelfSubjectReview is available on sufficiently new clusters. SelfSubjectRulesReview can be incomplete; use fixed SelfSubjectAccessReview checks where necessary. A 403 when checking Namespace existence means “not checked,” not “namespace absent.” Unverified JWT expiry decoding can inform UI but does not authenticate a token.

Report excessive access such as Secrets, delete/exec, or system:masters, and missing permissions for enabled tools. Provide least-privilege RBAC examples aligned with the selected namespaces and resources. A successful /version probe alone is not proof of tool authorization.

The research motivated a preparation step separate from pure classification. Preparation may perform bounded I/O and return an exact action, opaque prepared state, digest input, rejection, or no-change result. Execution receives that state, approval identity, deadline, output limit, and cancellation callback. Store non-secret discovery/context/server-pin metadata for pure classification; refresh it through explicit connection checks.

Close clients on configuration/removal, and cancel log HTTP streams on cancellation. The connector performs Kubernetes-specific Secret masking before the core's generic redaction, because generic field detectors alone do not know the Secret data contract.

## 8. Verification strategy and evidence limits

Unit tests cover kind aliases/CRDs, namespace/resource/verb combinations, cluster scope, hard-denied apply kinds, privileged pod specs, YAML aliases/tags/duplicates, object counts, Secret metadata leakage, diff normalization, rollout rounding, tail truncation, kubeconfig files/exec, and ambient-configuration guards.

Fabric8 mock-server tests cover ordinary get/list, Table fallback, events, merge-patch restart, log responses, access reviews, and error mapping. The recorded CRUD mock did not implement SSA and ignored dryRun, so it cannot establish apply correctness.

Use kube-api-test with a real API server and etcd for SSA, dry-run/no-op, field conflicts, RV and UID preconditions, namespace defaulting, Table/CRD discovery, RBAC, and Secret last-applied leakage. Compare normalized diffs with the same-version kubectl. Keep downloaded test binaries pinned and verified; offline caches are disposable test infrastructure.

That fixture has no controllers or kubelet. It cannot establish real rollout progress, previous-container logs, actual kubelet byte truncation, production admission-webhook behavior, GKE/EKS authorization completeness, or live-controller RV churn. Run those separately against an appropriate test cluster before claiming support.

## 9. Risks and sources

The main remaining risks were Jackson version mediation, accidental default HTTP-provider inclusion, ambient credentials/proxies, unsupported TLS-name overrides, SSA field ownership surprises, non-atomic multi-object updates, YAML 1.1/1.2 differences, and incomplete cloud authorization introspection. Product decisions included file-based kubeconfig, masked Secret reads, rollout waiting, and retaining full dry-run snapshots versus hashes.

- [Fabric8 v8 migration](https://github.com/fabric8io/kubernetes-client/blob/v8.0.0/doc/MIGRATION-v8.md) and [changelog](https://github.com/fabric8io/kubernetes-client/blob/v8.0.0/CHANGELOG.md)
- [Fabric8 JDK HTTP builder](https://github.com/fabric8io/kubernetes-client/blob/v8.0.0/httpclient-jdk/src/main/java/io/fabric8/kubernetes/client/jdkhttp/JdkHttpClientBuilderImpl.java)
- [Fabric8 8.0.0 dependency POM](https://repo1.maven.org/maven2/io/fabric8/kubernetes-client-project/8.0.0/kubernetes-client-project-8.0.0.pom)
- [Kubernetes 1.37 kubectl restart implementation](https://github.com/kubernetes/kubernetes/blob/v1.37.0/staging/src/k8s.io/kubectl/pkg/polymorphichelpers/objectrestarter.go)
- [Kubernetes 1.37 OpenAPI](https://github.com/kubernetes/kubernetes/blob/v1.37.0/api/openapi-spec/swagger.json)
- [SelfSubjectReview proposal](https://www.kubernetes.dev/resources/keps/3325/)
- [envtest release manifest](https://raw.githubusercontent.com/kubernetes-sigs/controller-tools/HEAD/envtest-releases.yaml)
- [AWS IAM authenticator token format](https://github.com/kubernetes-sigs/aws-iam-authenticator/blob/master/README.md)
- [MCP protocol schema](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts)
