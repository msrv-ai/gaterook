/**
 * AWS connector ({@code aws}), report 09: resources through Cloud Control (List/Get) and CloudWatch metrics and
 * alarms (RPCv2 CBOR), read with AssumeRole + ExternalId and a generated read-only session policy. An
 * ExecutionInterceptor allowlist stops every other operation before the network; the environment's Cloud policy
 * (services, regions, ARN masks) narrows what agents see, and configuration that holds secrets is masked before the
 * core's masker runs.
 */
package ai.msrv.gaterook.connectors.aws;
