#!/usr/bin/env python3
"""Generates the AWS API catalog that the AWS connector's aws_call reads.

Input is a checkout of https://github.com/aws/api-models-aws (Smithy JSON AST models, one per service). Output is
written to connectors/aws/src/main/resources/connectors/aws/api/:

  index.json             every service: the name agents use, aliases, protocol, IAM prefix, operation counts and,
                         for a service aws_call can't call, why not
  services/<name>.json.gz one service: protocol facts, endpoints, operations and the shapes they use, without
                         documentation

The catalog keeps facts, not decisions: which operations are reads, denied or data plane is decided by the connector's
code (ApiRules), which reads the traits recorded here. Endpoints are resolved here, once, by evaluating each model's
endpoint rule set for every region of the aws, aws-cn and aws-us-gov partitions with FIPS and dual-stack off, plus one
placeholder region per partition so a region added later follows its partition's template.

The output is deterministic for a given models commit. Usage:

  git clone --depth 1 https://github.com/aws/api-models-aws.git <dir>
  python3 scripts/aws-api-catalog.py --models <dir>

Standard library only; Python 3.10 or later.
"""

import argparse
import gzip
import json
import os
import re
import subprocess
import sys
import urllib.parse

FORMAT = 1
OUT = os.path.join("connectors", "aws", "src", "main", "resources", "connectors", "aws", "api")

# The partitions GateRook supports (AwsSettings: standard, China, GovCloud), as the SDK's partition data of the pinned
# SDK version lists them. A region missing here still resolves through its partition's template.
PARTITIONS = [
    {
        "name": "aws",
        "regex": r"^(us|eu|ap|sa|ca|me|af|il|mx)\-\w+\-\d+$",
        "dnsSuffix": "amazonaws.com",
        "dualStackDnsSuffix": "api.aws",
        "implicitGlobalRegion": "us-east-1",
        "placeholder": "us-gaterook-9",
        "regions": ["af-south-1", "ap-east-1", "ap-east-2", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
                    "ap-south-1", "ap-south-2", "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4",
                    "ap-southeast-5", "ap-southeast-6", "ap-southeast-7", "ca-central-1", "ca-west-1",
                    "eu-central-1", "eu-central-2", "eu-north-1", "eu-south-1", "eu-south-2", "eu-west-1",
                    "eu-west-2", "eu-west-3", "il-central-1", "me-central-1", "me-south-1", "mx-central-1",
                    "sa-east-1", "us-east-1", "us-east-2", "us-west-1", "us-west-2"],
    },
    {
        "name": "aws-cn",
        "regex": r"^cn\-\w+\-\d+$",
        "dnsSuffix": "amazonaws.com.cn",
        "dualStackDnsSuffix": "api.amazonwebservices.com.cn",
        "implicitGlobalRegion": "cn-northwest-1",
        "placeholder": "cn-gaterook-9",
        "regions": ["cn-north-1", "cn-northwest-1"],
    },
    {
        "name": "aws-us-gov",
        "regex": r"^us\-gov\-\w+\-\d+$",
        "dnsSuffix": "amazonaws.com",
        "dualStackDnsSuffix": "api.aws",
        "implicitGlobalRegion": "us-gov-west-1",
        "placeholder": "us-gov-gaterook-9",
        "regions": ["us-gov-east-1", "us-gov-west-1"],
    },
]

# The names the AWS CLI and boto3 give services whose model directory (the SDK ID slug) is spelled differently.
CLI_NAMES = {
    "api-gateway": "apigateway",
    "app-mesh": "appmesh",
    "application-auto-scaling": "application-autoscaling",
    "application-discovery-service": "discovery",
    "auto-scaling": "autoscaling",
    "auto-scaling-plans": "autoscaling-plans",
    "cloudhsm-v2": "cloudhsmv2",
    "cloudwatch-logs": "logs",
    "cognito-identity-provider": "cognito-idp",
    "config-service": "config",
    "cost-and-usage-report-service": "cur",
    "cost-explorer": "ce",
    "database-migration-service": "dms",
    "direct-connect": "directconnect",
    "directory-service": "ds",
    "directory-service-data": "ds-data",
    "dynamodb-streams": "dynamodbstreams",
    "elastic-beanstalk": "elasticbeanstalk",
    "elastic-load-balancing": "elb",
    "elastic-load-balancing-v2": "elbv2",
    "elastic-transcoder": "elastictranscoder",
    "elasticsearch-service": "es",
    "eventbridge": "events",
    "global-accelerator": "globalaccelerator",
    "iot-1click-devices-service": "iot1click-devices",
    "iot-1click-projects": "iot1click-projects",
    "iot-data-plane": "iot-data",
    "iot-events": "iotevents",
    "iot-jobs-data-plane": "iot-jobs-data",
    "iot-wireless": "iotwireless",
    "kinesis-analytics": "kinesisanalytics",
    "kinesis-analytics-v2": "kinesisanalyticsv2",
    "kinesis-video": "kinesisvideo",
    "lex-model-building-service": "lex-models",
    "lex-runtime-service": "lex-runtime",
    "marketplace-metering": "meteringmarketplace",
    "migration-hub": "mgh",
    "resource-groups-tagging-api": "resourcegroupstaggingapi",
    "route-53": "route53",
    "route-53-domains": "route53domains",
    "s3-control": "s3control",
    "secrets-manager": "secretsmanager",
    "serverlessapplicationrepository": "serverlessrepo",
    "service-catalog": "servicecatalog",
    "service-catalog-appregistry": "servicecatalog-appregistry",
    "sfn": "stepfunctions",
    "storage-gateway": "storagegateway",
}

# IAM service prefixes that differ from the model's ARN namespace (and signing name).
IAM_PREFIXES = {
    "cloudwatch": "cloudwatch",       # arnNamespace and signing name are "monitoring"
    "cloudcontrol": "cloudformation",  # Cloud Control's actions are cloudformation:ListResources and the like
    "iotsecuretunneling": "iot",
}

PROTOCOL_ORDER = ["aws.protocols#awsJson1_0", "aws.protocols#awsJson1_1", "aws.protocols#restJson1",
                  "aws.protocols#awsQuery", "aws.protocols#ec2Query", "aws.protocols#restXml",
                  "smithy.protocols#rpcv2Cbor"]
SUPPORTED_PROTOCOLS = {"awsJson1_0", "awsJson1_1", "restJson1", "awsQuery", "ec2Query", "restXml"}
# restXml services whose addressing and signing rules are their own (virtual hosts, checksums, account host labels).
DEFERRED = {"s3": "S3 needs its own tool: bucket addressing and object reads are not part of aws_call yet.",
            "s3-control": "S3 Control needs account host labels and checksums that aws_call doesn't send yet."}

PRELUDE = {"String": "string", "Blob": "blob", "Boolean": "boolean", "PrimitiveBoolean": "boolean",
           "Byte": "byte", "PrimitiveByte": "byte", "Short": "short", "PrimitiveShort": "short",
           "Integer": "integer", "PrimitiveInteger": "integer", "Long": "long", "PrimitiveLong": "long",
           "Float": "float", "PrimitiveFloat": "float", "Double": "double", "PrimitiveDouble": "double",
           "BigInteger": "bigInteger", "BigDecimal": "bigDecimal", "Timestamp": "timestamp",
           "Document": "document", "Unit": "unit"}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--models", required=True, help="checkout of github.com/aws/api-models-aws")
    ap.add_argument("--out", default=OUT, help="output directory (default: %(default)s)")
    args = ap.parse_args()
    revision, date = git_revision(args.models)
    files = []
    for slug in sorted(os.listdir(os.path.join(args.models, "models"))):
        base = os.path.join(args.models, "models", slug, "service")
        if not os.path.isdir(base):
            continue
        versions = sorted(os.listdir(base))
        # The newest version of a service is the one SDKs ship.
        version_dir = os.path.join(base, versions[-1])
        names = [n for n in os.listdir(version_dir) if n.endswith(".json")]
        if len(names) != 1:
            raise SystemExit(f"{version_dir}: expected one model, found {names}")
        files.append((slug, os.path.join(version_dir, names[0])))

    services = {}
    for slug, path in files:
        with open(path, encoding="utf-8") as fh:
            model = json.load(fh)
        services[slug] = Service(slug, model)

    names = assign_names(services)
    aliases = assign_aliases(services, names)
    os.makedirs(os.path.join(args.out, "services"), exist_ok=True)
    keep = set()
    index = {"format": FORMAT, "source": "https://github.com/aws/api-models-aws", "revision": revision,
             "revisionDate": date, "services": {}}
    for slug in sorted(services, key=lambda s: names[s]):
        svc = services[slug]
        name = names[slug]
        doc = svc.document(name)
        entry = {"sdkId": svc.sdk_id, "model": slug, "version": svc.version, "protocol": svc.protocol,
                 "iam": svc.iam_prefix, "aliases": aliases[slug], "operations": len(svc.operations),
                 "readonly": sum(1 for o in svc.operations.values() if o.get("ro"))}
        if svc.unsupported:
            entry["unsupported"] = svc.unsupported
        index["services"][name] = entry
        if svc.unsupported:
            # aws_call refuses the service by its index entry; its operations and shapes aren't shipped.
            continue
        data = json.dumps(doc, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        file_name = name + ".json.gz"
        keep.add(file_name)
        with open(os.path.join(args.out, "services", file_name), "wb") as fh:
            fh.write(gzip.compress(data, compresslevel=9, mtime=0))
    for stale in os.listdir(os.path.join(args.out, "services")):
        if stale not in keep:
            os.remove(os.path.join(args.out, "services", stale))
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=1, ensure_ascii=False, sort_keys=False)
        fh.write("\n")
    unsupported = sum(1 for s in services.values() if s.unsupported)
    print(f"{len(services)} services ({unsupported} not callable), revision {revision}", file=sys.stderr)


def git_revision(path):
    try:
        rev = subprocess.run(["git", "-C", path, "rev-parse", "HEAD"], check=True, capture_output=True,
                             text=True).stdout.strip()
        date = subprocess.run(["git", "-C", path, "log", "-1", "--format=%cI"], check=True, capture_output=True,
                              text=True).stdout.strip()
        return rev, date
    except (OSError, subprocess.CalledProcessError):
        raise SystemExit(f"{path} is not a git checkout: the catalog records the models commit it came from")


def assign_names(services):
    """The name agents use: the CLI's where it differs from the SDK ID slug, else the slug."""
    names = {}
    for slug in services:
        names[slug] = CLI_NAMES.get(slug, slug)
    seen = {}
    for slug, name in names.items():
        if name in seen:
            raise SystemExit(f"{slug} and {seen[name]} would both be called {name}")
        seen[name] = slug
    return names


def assign_aliases(services, names):
    """Other spellings that name exactly one service: the slug, the SDK ID without spaces, the endpoint prefix and the
    signing name. A spelling two services share, or another service's name, is nobody's alias."""
    candidates = {}
    for slug, svc in services.items():
        own = []
        for alias in (slug, re.sub(r"[^a-z0-9-]", "", svc.sdk_id.lower().replace(" ", "")),
                      re.sub(r"[^a-z0-9-]", "", svc.sdk_id.lower().replace(" ", "-")), svc.endpoint_prefix,
                      svc.signing_name):
            if alias and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", alias) and alias != names[slug] and alias not in own:
                own.append(alias)
        candidates[slug] = own
    claims = {}
    for slug, own in candidates.items():
        for alias in own:
            claims.setdefault(alias, set()).add(slug)
    taken = set(names.values())
    return {slug: sorted(a for a in own if len(claims[a]) == 1 and a not in taken)
            for slug, own in candidates.items()}


class Service:
    def __init__(self, slug, model):
        self.slug = slug
        self.shapes = model["shapes"]
        self.id, self.shape = next((k, v) for k, v in self.shapes.items() if v["type"] == "service")
        self.namespace = self.id.split("#")[0]
        traits = self.shape.get("traits", {})
        api = traits.get("aws.api#service", {})
        self.sdk_id = api.get("sdkId", slug)
        self.version = self.shape.get("version", "")
        self.endpoint_prefix = api.get("endpointPrefix")
        self.signing_name = traits.get("aws.auth#sigv4", {}).get("name")
        self.iam_prefix = IAM_PREFIXES.get(slug) or api.get("arnNamespace") or self.signing_name or slug
        self.xml_namespace = traits.get("smithy.api#xmlNamespace", {}).get("uri")
        protocols = [p for p in PROTOCOL_ORDER if p in traits]
        self.protocol = protocols[0].split("#")[1] if protocols else None
        self.query_compatible = "aws.protocols#awsQueryCompatible" in traits
        self.unsupported = None
        if self.protocol not in SUPPORTED_PROTOCOLS:
            self.unsupported = (f"{self.sdk_id} speaks only {self.protocol}, which aws_call doesn't send yet."
                                if self.protocol else f"{self.sdk_id} declares no protocol.")
        elif slug in DEFERRED:
            self.unsupported = DEFERRED[slug]
        elif self.signing_name is None:
            self.unsupported = f"{self.sdk_id} signs requests with bearer tokens, not AWS credentials."
        self.paginated_defaults = traits.get("smithy.api#paginated", {})
        self.operations = {}
        self.used = set()
        self.endpoint_groups = []
        self._collect_operations()
        if self.unsupported is None:
            self._resolve_endpoints(traits.get("smithy.rules#endpointRuleSet"))

    # ── Operations ─────────────────────────────────────────────────────────────────────────────────────────────

    def _operation_ids(self):
        ids = [o["target"] for o in self.shape.get("operations", [])]

        def resource(rid):
            r = self.shapes[rid]
            for key in ("create", "put", "read", "update", "delete", "list"):
                if key in r:
                    ids.append(r[key]["target"])
            for o in r.get("operations", []) + r.get("collectionOperations", []):
                ids.append(o["target"])
            for sub in r.get("resources", []):
                resource(sub["target"])

        for r in self.shape.get("resources", []):
            resource(r["target"])
        return sorted(set(ids), key=lambda i: i.split("#")[1])

    def _collect_operations(self):
        for oid in self._operation_ids():
            name = oid.split("#")[1]
            o = self.shapes[oid]
            t = o.get("traits", {})
            op = {}
            for key in ("input", "output"):
                target = o.get(key, {}).get("target")
                if target and target != "smithy.api#Unit":
                    op[key[0]] = self._ref(target)
                    self._use(target)
            if "smithy.api#readonly" in t:
                op["ro"] = 1
            if "smithy.api#idempotent" in t:
                op["idem"] = 1
            http = t.get("smithy.api#http")
            if http and self.protocol in ("restJson1", "restXml"):
                op["http"] = [http["method"], http["uri"], http.get("code", 200)]
            page = t.get("smithy.api#paginated")
            if page is not None:
                merged = dict(self.paginated_defaults)
                merged.update(page)
                op["page"] = {k: v for k, v in merged.items() if k in ("inputToken", "outputToken", "items",
                                                                        "pageSize")}
            endpoint = t.get("smithy.api#endpoint")
            if endpoint:
                op["hostPrefix"] = endpoint["hostPrefix"]
            action = t.get("aws.iam#iamAction", {}).get("name") or t.get("aws.iam#actionName")
            if action and action != name:
                op["iam"] = action
            if t.get("smithy.api#auth") == []:
                op["noAuth"] = 1
            if "aws.api#dataPlane" in t:
                op["dataPlane"] = 1
            discovered = t.get("aws.api#clientDiscoveredEndpoint")
            if discovered is not None and discovered.get("required"):
                op["discovery"] = 1
            if "smithy.api#httpChecksumRequired" in t or t.get("aws.protocols#httpChecksum", {}).get(
                    "requestChecksumRequired"):
                op["checksum"] = 1
            if "aws.auth#unsignedPayload" in t:
                op["unsignedPayload"] = 1
            if "smithy.api#deprecated" in t:
                op["deprecated"] = 1
            streams = []
            for key, flag in (("input", "in"), ("output", "out")):
                target = o.get(key, {}).get("target")
                if target and target != "smithy.api#Unit" and self._streams(target):
                    streams.append(flag)
            if streams:
                op["streaming"] = streams
            static = t.get("smithy.rules#staticContextParams")
            if static:
                op["_static"] = {k: v["value"] for k, v in static.items()}
            self.operations[name] = op

    def _streams(self, structure):
        for member in self.shapes[structure].get("members", {}).values():
            target = self.shapes.get(member["target"])
            if target and "smithy.api#streaming" in target.get("traits", {}):
                return True
        return False

    # ── Shapes ─────────────────────────────────────────────────────────────────────────────────────────────────

    def _ref(self, target):
        ns, name = target.split("#")
        if ns == "smithy.api":
            if name not in PRELUDE:
                raise SystemExit(f"{self.slug}: unknown prelude shape {target}")
            return "#" + PRELUDE[name]
        return name if ns == self.namespace else target

    def _use(self, target):
        stack = [target]
        while stack:
            s = stack.pop()
            if s.startswith("smithy.api#") or s in self.used:
                continue
            self.used.add(s)
            d = self.shapes[s]
            for m in d.get("members", {}).values() if d["type"] in ("structure", "union") else []:
                stack.append(m["target"])
            for k in ("member", "key", "value"):
                if k in d:
                    stack.append(d[k]["target"])

    def _member(self, m):
        out = {"s": self._ref(m["target"])}
        t = m.get("traits", {})
        if "smithy.api#httpLabel" in t:
            out["label"] = 1
        if "smithy.api#httpQuery" in t:
            out["query"] = t["smithy.api#httpQuery"]
        if "smithy.api#httpQueryParams" in t:
            out["queryParams"] = 1
        if "smithy.api#httpHeader" in t:
            out["header"] = t["smithy.api#httpHeader"]
        if "smithy.api#httpPrefixHeaders" in t:
            out["prefixHeaders"] = t["smithy.api#httpPrefixHeaders"]
        if "smithy.api#httpPayload" in t:
            out["payload"] = 1
        if "smithy.api#httpResponseCode" in t:
            out["status"] = 1
        if "smithy.api#jsonName" in t:
            out["json"] = t["smithy.api#jsonName"]
        if "smithy.api#xmlName" in t:
            out["xml"] = t["smithy.api#xmlName"]
        if "smithy.api#xmlAttribute" in t:
            out["attr"] = 1
        if "smithy.api#xmlFlattened" in t:
            out["flat"] = 1
        if "smithy.api#xmlNamespace" in t:
            out["ns"] = t["smithy.api#xmlNamespace"]
        if "aws.protocols#ec2QueryName" in t:
            out["ec2"] = t["aws.protocols#ec2QueryName"]
        if "smithy.api#timestampFormat" in t:
            out["ts"] = t["smithy.api#timestampFormat"]
        if "smithy.api#idempotencyToken" in t:
            out["token"] = 1
        if "smithy.api#hostLabel" in t:
            out["host"] = 1
        if "smithy.api#required" in t:
            out["req"] = 1
        return out

    def _shape(self, sid):
        d = self.shapes[sid]
        kind = d["type"]
        t = d.get("traits", {})
        out = {"t": {"enum": "string", "intEnum": "integer"}.get(kind, kind)}
        if "smithy.api#sensitive" in t:
            out["sensitive"] = 1
        if "smithy.api#timestampFormat" in t:
            out["ts"] = t["smithy.api#timestampFormat"]
        if "smithy.api#streaming" in t:
            out["streaming"] = 1
        if "smithy.api#xmlName" in t:
            out["xml"] = t["smithy.api#xmlName"]
        if "smithy.api#xmlNamespace" in t:
            out["ns"] = t["smithy.api#xmlNamespace"]
        if "smithy.api#mediaType" in t:
            out["media"] = t["smithy.api#mediaType"]
        if "smithy.api#error" in t:
            out["error"] = t["smithy.api#error"]
        if kind in ("structure", "union"):
            out["m"] = {n: self._member(m) for n, m in d.get("members", {}).items()}
        for k in ("member", "key", "value"):
            if k in d:
                out[k] = self._member(d[k])
        return out

    # ── Endpoints ──────────────────────────────────────────────────────────────────────────────────────────────

    def _resolve_endpoints(self, ruleset):
        if ruleset is None:
            self.unsupported = f"{self.sdk_id} has no endpoint rules."
            return
        groups = {}
        for name, op in self.operations.items():
            static = op.pop("_static", None) or {}
            key = json.dumps(static, sort_keys=True)
            if key not in groups:
                groups[key] = len(groups)
                self.endpoint_groups.append(self._endpoints(ruleset, static))
            if groups[key]:
                op["endpoint"] = groups[key]
        if all(g.get("error") for g in self.endpoint_groups[:1]):
            self.unsupported = f"{self.sdk_id}: {self.endpoint_groups[0]['error']}"

    def _endpoints(self, ruleset, static):
        out = {}
        regions = {}
        for p in PARTITIONS:
            template = endpoint_for(ruleset, p["placeholder"], static)
            if "error" in template:
                out[p["name"]] = {"error": template["error"]}
                continue
            entry = generalize(template, p["placeholder"])
            out[p["name"]] = entry
            for region in p["regions"]:
                actual = endpoint_for(ruleset, region, static)
                expected = specialize(entry, region)
                if "error" in actual:
                    regions[region] = {"error": actual["error"]}
                elif strip_defaults(actual, region) != strip_defaults(expected, region):
                    regions[region] = strip_defaults(actual, region)
        if regions:
            out["regions"] = dict(sorted(regions.items()))
        errors = {v.get("error") for k, v in out.items() if k != "regions"}
        if errors == {None}:
            return out
        if all(isinstance(v, dict) and "error" in v for k, v in out.items() if k != "regions"):
            return {"error": next(iter(errors))}
        return out

    def document(self, name):
        shapes = {}
        for sid in sorted(self.used, key=lambda s: self._ref(s)):
            shapes[self._ref(sid)] = self._shape(sid)
        doc = {"format": FORMAT, "name": name, "sdkId": self.sdk_id, "model": self.slug, "version": self.version,
               "protocol": self.protocol, "service": self.id.split("#")[1], "signingName": self.signing_name,
               "iam": self.iam_prefix}
        if self.query_compatible:
            doc["queryCompatible"] = 1
        if self.xml_namespace:
            doc["xmlNamespace"] = self.xml_namespace
        if self.unsupported:
            doc["unsupported"] = self.unsupported
        doc["endpoints"] = self.endpoint_groups
        doc["operations"] = self.operations
        doc["shapes"] = shapes
        return doc


# ── Endpoint rule sets (smithy.rules) ──────────────────────────────────────────────────────────────────────────

class RuleError(Exception):
    pass


def endpoint_for(ruleset, region, static):
    params = {}
    for name, p in ruleset.get("parameters", {}).items():
        if "default" in p:
            params[name] = p["default"]
    params.update({"Region": region, "UseFIPS": False, "UseDualStack": False})
    params.update(static)
    for name, p in ruleset.get("parameters", {}).items():
        if p.get("required") and params.get(name) is None:
            return {"error": f"the endpoint needs {name}, which aws_call doesn't set"}
    try:
        result = rules(ruleset["rules"], params)
    except RuleError as e:
        return {"error": str(e)}
    if result is None:
        return {"error": "no endpoint rule matched"}
    if result[0] == "error":
        return {"error": result[1]}
    endpoint = result[1]
    out = {"url": endpoint["url"]}
    schemes = endpoint.get("properties", {}).get("authSchemes") or []
    sigv4 = [s for s in schemes if s.get("name") == "sigv4"]
    if schemes and not sigv4:
        return {"error": "the endpoint signs with " + ", ".join(s.get("name", "?") for s in schemes)}
    if sigv4:
        s = sigv4[0]
        if s.get("signingRegion"):
            out["signingRegion"] = s["signingRegion"]
        if s.get("signingName"):
            out["signingName"] = s["signingName"]
    if endpoint.get("headers"):
        return {"error": "the endpoint needs headers that aws_call doesn't send"}
    return out


def generalize(endpoint, placeholder):
    out = {}
    for k, v in endpoint.items():
        out[k] = v.replace(placeholder, "{region}") if isinstance(v, str) else v
    if out.get("signingRegion") == "{region}":
        del out["signingRegion"]
    return out


def specialize(entry, region):
    out = {}
    for k, v in entry.items():
        out[k] = v.replace("{region}", region) if isinstance(v, str) else v
    return out


def strip_defaults(endpoint, region):
    out = dict(endpoint)
    if out.get("signingRegion") == region:
        del out["signingRegion"]
    return out


def rules(rule_list, scope):
    for rule in rule_list:
        local = dict(scope)
        if all(condition(c, local) for c in rule.get("conditions", [])):
            kind = rule["type"]
            if kind == "endpoint":
                e = rule["endpoint"]
                return ("endpoint", {"url": value(e["url"], local),
                                     "properties": value(e.get("properties", {}), local),
                                     "headers": value(e.get("headers", {}), local)})
            if kind == "error":
                return ("error", value(rule["error"], local))
            if kind == "tree":
                r = rules(rule["rules"], local)
                if r is None:
                    raise RuleError("no rule of a matching tree matched")
                return r
            raise RuleError("unknown rule type " + kind)
    return None


def condition(c, scope):
    result = call(c, scope)
    if result is None or result is False:
        return False
    if "assign" in c:
        scope[c["assign"]] = result
    return True


def value(v, scope):
    if isinstance(v, str):
        return template(v, scope)
    if isinstance(v, bool) or isinstance(v, (int, float)) or v is None:
        return v
    if isinstance(v, list):
        return [value(x, scope) for x in v]
    if isinstance(v, dict):
        if "ref" in v:
            return scope.get(v["ref"])
        if "fn" in v:
            return call(v, scope)
        return {k: value(x, scope) for k, x in v.items()}
    raise RuleError("unknown value " + repr(v))


def template(s, scope):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "{" and s.startswith("{{", i):
            out.append("{")
            i += 2
        elif c == "}" and s.startswith("}}", i):
            out.append("}")
            i += 2
        elif c == "{":
            j = s.index("}", i)
            ref = s[i + 1:j]
            if "#" in ref:
                name, path = ref.split("#", 1)
                v = attr(scope.get(name), path)
            else:
                v = scope.get(ref)
            if v is None:
                raise RuleError("template reference " + ref + " is unset")
            out.append(str(v).lower() if isinstance(v, bool) else str(v))
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def attr(obj, path):
    for part in re.findall(r"[^.\[\]]+|\[\d+\]", path):
        if obj is None:
            return None
        if part.startswith("["):
            idx = int(part[1:-1])
            obj = obj[idx] if isinstance(obj, list) and idx < len(obj) else None
        else:
            obj = obj.get(part) if isinstance(obj, dict) else None
    return obj


def call(c, scope):
    fn = c["fn"]
    argv = [value(a, scope) for a in c.get("argv", [])]
    if fn == "isSet":
        return argv[0] is not None
    if fn == "not":
        return not argv[0]
    if fn == "booleanEquals":
        return argv[0] == argv[1]
    if fn == "stringEquals":
        return argv[0] is not None and argv[0] == argv[1]
    if fn == "getAttr":
        return attr(argv[0], argv[1])
    if fn == "aws.partition":
        return partition(argv[0])
    if fn == "substring":
        s, start, stop, reverse = argv
        if s is None or not s.isascii() or start >= stop or len(s) < stop:
            return None
        return s[len(s) - stop:len(s) - start] if reverse else s[start:stop]
    if fn == "isValidHostLabel":
        s, sub = argv
        if s is None:
            return False
        labels = s.split(".") if sub else [s]
        return all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,62}", lab) is not None for lab in labels)
    if fn == "uriEncode":
        return urllib.parse.quote(argv[0], safe="") if argv[0] is not None else None
    if fn in ("parseURL", "aws.parseArn", "aws.isVirtualHostableS3Bucket"):
        # Only reached with an Endpoint, an ARN or a bucket set, which the catalog's evaluations never set.
        return None
    raise RuleError("unknown function " + fn)


def partition(region):
    if region is None:
        return None
    chosen = None
    for p in PARTITIONS:
        if region in p["regions"] or region == p["placeholder"]:
            chosen = p
            break
    if chosen is None:
        for p in PARTITIONS:
            if re.match(p["regex"], region):
                chosen = p
                break
    if chosen is None:
        chosen = PARTITIONS[0]
    return {"name": chosen["name"], "dnsSuffix": chosen["dnsSuffix"],
            "dualStackDnsSuffix": chosen["dualStackDnsSuffix"], "supportsFIPS": True, "supportsDualStack": True,
            "implicitGlobalRegion": chosen["implicitGlobalRegion"]}


if __name__ == "__main__":
    main()
